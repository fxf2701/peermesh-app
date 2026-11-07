package app.peermesh.nearby

import java.util.concurrent.ConcurrentHashMap

object NearbyRoomPeerSnapshotStore {
    private val peersByDeviceId = ConcurrentHashMap<String, PeerSnapshot>()

    fun record(deviceId: String, roomId: String?, seenAtMs: Long, rssi: Int) {
        peersByDeviceId.compute(deviceId) { _, existing ->
            when {
                existing == null -> PeerSnapshot(deviceId, roomId, seenAtMs, rssi)
                seenAtMs > existing.seenAtMs -> PeerSnapshot(deviceId, roomId, seenAtMs, rssi)
                seenAtMs == existing.seenAtMs && rssi > existing.rssi -> PeerSnapshot(deviceId, roomId, seenAtMs, rssi)
                else -> existing
            }
        }
    }

    fun nearbyDeviceIds(
        limit: Int = 10,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
    ): List<String> {
        pruneExpired(nowMs, ttlMs)
        return peersByDeviceId.values
            .sortedWith(
                compareByDescending<PeerSnapshot> { it.seenAtMs }
                    .thenByDescending { it.rssi }
            )
            .map { it.deviceId }
            .take(limit)
    }

    fun nearbyDeviceIdsForRoom(
        roomId: String,
        limit: Int = 10,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
    ): List<String> {
        pruneExpired(nowMs, ttlMs)
        return peersByDeviceId.values
            .filter { it.roomId == roomId }
            .sortedWith(
                compareByDescending<PeerSnapshot> { it.seenAtMs }
                    .thenByDescending { it.rssi }
            )
            .map { it.deviceId }
            .take(limit)
    }

    fun nearbyPeers(
        limit: Int = 10,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
    ): List<PeerSnapshot> {
        pruneExpired(nowMs, ttlMs)
        return peersByDeviceId.values
            .sortedWith(
                compareByDescending<PeerSnapshot> { it.seenAtMs }
                    .thenByDescending { it.rssi }
            )
            .take(limit)
    }

    fun nearbyPeersForRoom(
        roomId: String,
        limit: Int = 10,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
    ): List<PeerSnapshot> {
        pruneExpired(nowMs, ttlMs)
        return peersByDeviceId.values
            .filter { it.roomId == roomId }
            .sortedWith(
                compareByDescending<PeerSnapshot> { it.seenAtMs }
                    .thenByDescending { it.rssi }
            )
            .take(limit)
    }

    private fun pruneExpired(nowMs: Long, ttlMs: Long) {
        peersByDeviceId.entries.removeIf { nowMs - it.value.seenAtMs > ttlMs }
    }

    data class PeerSnapshot(
        val deviceId: String,
        val roomId: String?,
        val seenAtMs: Long,
        val rssi: Int,
    )

    private const val DEFAULT_TTL_MS = 15_000L
}
