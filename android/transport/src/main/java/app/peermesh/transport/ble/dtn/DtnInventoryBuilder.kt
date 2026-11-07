package app.peermesh.transport.ble.dtn

class DtnInventoryBuilder(
    private val store: DtnInventoryStore,
    private val repairSuppressionStore: DtnRepairSuppressionStore? = null,
) {
    data class HaveWindow(
        val packet: DtnControlPacket,
        val cursor: DtnSyncCursor?,
        val suppressedIds: List<String>,
        val tier: DtnInventoryTier,
        val hasMoreInTier: Boolean,
    )

    fun buildHavePacket(
        localPeerId: String,
        remotePeerId: String? = null,
        gid: String,
        sessionId: String,
        nowSeconds: Long,
        limit: Int = DtnForwardPolicy.DEFAULT_INVENTORY_LIMIT,
    ): DtnControlPacket {
        return buildHaveWindow(
            localPeerId = localPeerId,
            remotePeerId = remotePeerId,
            gid = gid,
            sessionId = sessionId,
            nowSeconds = nowSeconds,
            tier = DtnInventoryTier.NORMAL,
            cursor = null,
            limit = limit,
        ).packet
    }

    fun buildHaveWindow(
        localPeerId: String,
        remotePeerId: String?,
        gid: String,
        sessionId: String,
        nowSeconds: Long,
        tier: DtnInventoryTier,
        cursor: DtnSyncCursor?,
        limit: Int = DtnForwardPolicy.DEFAULT_INVENTORY_LIMIT,
    ): HaveWindow {
        val scanLimit = maxOf(limit * 4, limit)
        var currentCursor = cursor
        var exhausted = false
        val matches = LinkedHashMap<String, DtnInventoryItem>()
        val suppressedIds = LinkedHashSet<String>()

        repeat(MAX_SCAN_BATCHES) {
            if (matches.size >= limit) return@repeat
            val window = store.listInventoryAfter(
                gid = gid,
                lastInsertedAt = currentCursor?.lastInsertedAt ?: 0L,
                lastEventId = currentCursor?.lastEventId.orEmpty(),
                limit = scanLimit,
            )
            if (window.isEmpty()) {
                exhausted = true
                return@repeat
            }
            currentCursor = window.maxWithOrNull(
                compareBy<DtnInventoryItem> { it.insertedAtSeconds }.thenBy { it.eventId }
            )?.let { DtnSyncCursor(lastInsertedAt = it.insertedAtSeconds, lastEventId = it.eventId) }

            val tierMatches = DtnForwardPolicy
                .sortForTransfer(
                    window.filter { DtnInventoryTier.classify(it, nowSeconds) == tier },
                    tier = tier,
                )
                .filterNot { it.eventId in matches }

            if (tierMatches.isEmpty()) return@repeat

            val candidateIds = tierMatches.map { it.eventId }
            val suppressed = repairSuppressionStore?.filterPeerSatisfied(
                peerId = remotePeerId.orEmpty(),
                eventIds = candidateIds,
                nowSeconds = nowSeconds,
            )?.toSet().orEmpty()

            tierMatches.forEach { item ->
                if (item.eventId in suppressed) {
                    suppressedIds += item.eventId
                } else if (matches.size < limit) {
                    matches[item.eventId] = item
                }
            }
        }

        return HaveWindow(
            packet = DtnControlPacket(
                type = DtnControlTypes.INV_HAVE,
                peerId = localPeerId,
                gid = gid,
                sessionId = sessionId,
                mode = DtnInventoryModes.ID_LIST,
                inventoryTier = tier.wireValue,
                ids = matches.keys.toList(),
                ts = nowSeconds,
            ),
            cursor = currentCursor,
            suppressedIds = suppressedIds.toList(),
            tier = tier,
            hasMoreInTier = !exhausted,
        )
    }

    private companion object {
        const val MAX_SCAN_BATCHES = 8
    }
}
