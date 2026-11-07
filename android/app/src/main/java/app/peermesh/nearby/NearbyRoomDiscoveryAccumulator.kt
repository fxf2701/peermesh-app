package app.peermesh.nearby

class NearbyRoomDiscoveryAccumulator(
    private val maxMetadataPeersPerBurst: Int = DEFAULT_MAX_METADATA_PEERS_PER_BURST,
    private val maxUniqueRoomsPerBurst: Int = DEFAULT_MAX_UNIQUE_ROOMS_PER_BURST,
    private val minRoomRefreshIntervalMs: Long = DEFAULT_MIN_ROOM_REFRESH_INTERVAL_MS,
) {
    private val sightings = mutableListOf<MetadataSighting>()

    fun record(sighting: MetadataSighting) {
        sightings += sighting
    }

    fun clear() {
        sightings.clear()
    }

    fun evaluate(
        existingRoomsById: Map<String, ExistingRoomSnapshot>,
        nowMs: Long,
    ): DiscoveryBurstResult {
        val candidates = sightings
            .groupBy { it.advertisement.roomId }
            .values
            .mapNotNull { roomSightings ->
                val best = roomSightings.maxWithOrNull(
                    compareBy<MetadataSighting> { it.rssi }
                        .thenBy { it.seenAtMs }
                ) ?: return@mapNotNull null
                val existing = existingRoomsById[best.advertisement.roomId]
                RoomCandidate(
                    roomId = best.advertisement.roomId,
                    bestSighting = best,
                    advertiserCount = roomSightings.size,
                    existing = existing,
                    shouldBypassThrottle = shouldBypassThrottle(existing, best),
                )
            }
            .sortedWith(compareCandidates(nowMs))

        val metadataPeerBudget = maxMetadataPeersPerBurst.coerceAtLeast(0)
        val uniqueRoomBudget = maxUniqueRoomsPerBurst.coerceAtLeast(0)
        val acceptedPeerCandidates = candidates.take(metadataPeerBudget)

        val decisions = mutableListOf<RoomUpdateDecision>()
        var uniqueAccepted = 0
        acceptedPeerCandidates.forEach { candidate ->
            val decisionType = when {
                uniqueAccepted >= uniqueRoomBudget -> DecisionType.SKIP_BUDGET
                candidate.existing == null -> {
                    uniqueAccepted += 1
                    DecisionType.INSERT
                }
                candidate.shouldBypassThrottle -> {
                    uniqueAccepted += 1
                    DecisionType.REFRESH
                }
                candidate.bestSighting.seenAtMs - candidate.existing.lastSeenAtMs >= minRoomRefreshIntervalMs -> {
                    uniqueAccepted += 1
                    DecisionType.REFRESH
                }
                else -> DecisionType.SKIP_THROTTLED
            }
            decisions += RoomUpdateDecision(
                roomId = candidate.roomId,
                type = decisionType,
                advertisement = candidate.bestSighting.advertisement,
                sourceDeviceId = candidate.bestSighting.sourceDeviceId,
                seenAtMs = candidate.bestSighting.seenAtMs,
                rssi = candidate.bestSighting.rssi,
                advertiserCount = candidate.advertiserCount,
            )
        }

        val skippedByPeerBudget = (candidates - acceptedPeerCandidates.toSet()).map { candidate ->
            RoomUpdateDecision(
                roomId = candidate.roomId,
                type = DecisionType.SKIP_BUDGET,
                advertisement = candidate.bestSighting.advertisement,
                sourceDeviceId = candidate.bestSighting.sourceDeviceId,
                seenAtMs = candidate.bestSighting.seenAtMs,
                rssi = candidate.bestSighting.rssi,
                advertiserCount = candidate.advertiserCount,
            )
        }

        return DiscoveryBurstResult(
            decisions = decisions + skippedByPeerBudget,
            totalSightings = sightings.size,
            uniqueRoomSightings = candidates.size,
            acceptedMetadataPeers = acceptedPeerCandidates.size,
            acceptedUniqueRooms = decisions.count { it.type == DecisionType.INSERT || it.type == DecisionType.REFRESH },
        )
    }

    private fun compareCandidates(nowMs: Long): Comparator<RoomCandidate> {
        return compareByDescending<RoomCandidate> { it.existing == null }
            .thenByDescending { it.existing?.isStale == true }
            .thenByDescending { it.shouldBypassThrottle }
            .thenByDescending { nowMs - (it.existing?.lastSeenAtMs ?: 0L) }
            .thenByDescending { it.bestSighting.rssi }
            .thenByDescending { it.bestSighting.seenAtMs }
    }

    private fun shouldBypassThrottle(
        existing: ExistingRoomSnapshot?,
        best: MetadataSighting,
    ): Boolean {
        if (existing == null) return true
        val advertisement = best.advertisement
        val advertisedName = advertisement.roomName
        if (existing.isStale) return true
        if (!existing.hasAuthoritativeMetadata && existing.expiresAtMs != advertisement.expiresAtMs) return true
        if (advertisedName.isNotBlank() && existing.roomName != advertisedName) return true
        if (existing.protocolVersion != advertisement.protocolVersion) return true
        return false
    }

    data class MetadataSighting(
        val sourceDeviceId: String,
        val advertisement: NearbyRoomBleAdvertisement,
        val seenAtMs: Long,
        val rssi: Int,
    )

    data class ExistingRoomSnapshot(
        val roomId: String,
        val roomName: String,
        val expiresAtMs: Long,
        val participantCount: Int?,
        val protocolVersion: Int,
        val lastSeenAtMs: Long,
        val isStale: Boolean,
        val hasAuthoritativeMetadata: Boolean,
    )

    data class DiscoveryBurstResult(
        val decisions: List<RoomUpdateDecision>,
        val totalSightings: Int,
        val uniqueRoomSightings: Int,
        val acceptedMetadataPeers: Int,
        val acceptedUniqueRooms: Int,
    )

    data class RoomUpdateDecision(
        val roomId: String,
        val type: DecisionType,
        val advertisement: NearbyRoomBleAdvertisement,
        val sourceDeviceId: String,
        val seenAtMs: Long,
        val rssi: Int,
        val advertiserCount: Int,
    )

    enum class DecisionType {
        INSERT,
        REFRESH,
        SKIP_THROTTLED,
        SKIP_BUDGET,
    }

    private data class RoomCandidate(
        val roomId: String,
        val bestSighting: MetadataSighting,
        val advertiserCount: Int,
        val existing: ExistingRoomSnapshot?,
        val shouldBypassThrottle: Boolean,
    )

    companion object {
        const val DEFAULT_MAX_METADATA_PEERS_PER_BURST = 10
        const val DEFAULT_MAX_UNIQUE_ROOMS_PER_BURST = 10
        const val DEFAULT_MIN_ROOM_REFRESH_INTERVAL_MS = 10_000L
    }
}
