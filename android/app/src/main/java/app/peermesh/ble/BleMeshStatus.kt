package app.peermesh.ble

import java.util.concurrent.CopyOnWriteArrayList

object BleMeshStatus {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var lastMeshStartStage: String? = null
    @Volatile var lastMeshStartReason: String? = null
    @Volatile var lastMeshStartAtMs: Long = 0L
    @Volatile var lastTransportStartAtMs: Long = 0L
    @Volatile var lastStartupStallDetectedAtMs: Long = 0L
    @Volatile var lastStartupRecoveryAtMs: Long = 0L
    @Volatile var lastScanStartAtMs: Long = 0L
    @Volatile var lastScanStartSkippedAtMs: Long = 0L
    @Volatile var lastScanStartSkippedReason: String? = null
    @Volatile var lastAdvertiseStartedAtMs: Long = 0L
    @Volatile var lastScanCallbackAtMs: Long = 0L
    @Volatile var lastAutoRecoverAtMs: Long = 0L
    @Volatile var lastAdaptiveState: String? = null
    @Volatile var lastAdaptiveStateAtMs: Long = 0L
    @Volatile var lastTrafficAtMs: Long = 0L
    @Volatile var lastTransportProgressAtMs: Long = 0L
    @Volatile var lastGattConnectedAtMs: Long = 0L
    @Volatile var lastStallDetectedAtMs: Long = 0L
    @Volatile var lastStallRecoveryAtMs: Long = 0L
    @Volatile var lastNearbyTotal: Int = -1
    @Volatile var lastNearbyTotalAtMs: Long = 0L

    fun nearbyTotalForUi(
        nowMs: Long = System.currentTimeMillis(),
        fallback: (() -> Int)? = null,
    ): Int {
        val cached = lastNearbyTotal
        val cachedAt = lastNearbyTotalAtMs
        if (cached >= 0 && cachedAt > 0L && nowMs - cachedAt <= NEARBY_TOTAL_CACHE_TTL_MS) {
            return cached
        }
        val resolved = fallback?.invoke() ?: cached.coerceAtLeast(0)
        if (resolved >= 0) {
            lastNearbyTotal = resolved
            lastNearbyTotalAtMs = nowMs
        }
        return resolved.coerceAtLeast(0)
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        listeners.forEach { listener ->
            runCatching { listener() }
        }
    }

    private const val NEARBY_TOTAL_CACHE_TTL_MS = 15_000L
}
