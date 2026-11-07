package app.peermesh.transport.ble.dtn

import java.util.concurrent.ConcurrentHashMap

interface DtnRepairSuppressionStore {
    fun markLocallySatisfied(
        eventIds: Collection<String>,
        nowSeconds: Long,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    )

    fun isLocallySatisfied(eventId: String, nowSeconds: Long): Boolean

    fun filterLocallySatisfied(eventIds: Collection<String>, nowSeconds: Long): Set<String>

    fun markLocallyPendingRelay(
        eventIds: Collection<String>,
        nowSeconds: Long,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    )

    fun isLocallyPendingRelay(eventId: String, nowSeconds: Long): Boolean

    fun filterLocallyPendingRelay(eventIds: Collection<String>, nowSeconds: Long): Set<String>

    fun clearLocallyPendingRelay(eventIds: Collection<String>)

    fun markLocallyForwardedSuccess(
        eventIds: Collection<String>,
        nowSeconds: Long,
        ttlSeconds: Long = FORWARDED_SUCCESS_TTL_SECONDS,
    )

    fun isLocallyForwardedSuccess(eventId: String, nowSeconds: Long): Boolean

    fun filterLocallyForwardedSuccess(eventIds: Collection<String>, nowSeconds: Long): Set<String>

    fun markPeerSatisfied(
        peerId: String,
        eventIds: Collection<String>,
        nowSeconds: Long,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    )

    fun filterPeerSatisfied(peerId: String, eventIds: Collection<String>, nowSeconds: Long): Set<String>

    companion object {
        const val DEFAULT_TTL_SECONDS: Long = 24L * 60L * 60L
        const val FORWARDED_SUCCESS_TTL_SECONDS: Long = DEFAULT_TTL_SECONDS
    }
}

class InMemoryDtnRepairSuppressionStore : DtnRepairSuppressionStore {
    private val localSatisfiedUntilByEventId = ConcurrentHashMap<String, Long>()
    private val localPendingRelayUntilByEventId = ConcurrentHashMap<String, Long>()
    private val localForwardedSuccessUntilByEventId = ConcurrentHashMap<String, Long>()
    private val peerSatisfiedUntilByPeerAndEventId = ConcurrentHashMap<String, Long>()

    override fun markLocallySatisfied(
        eventIds: Collection<String>,
        nowSeconds: Long,
        ttlSeconds: Long,
    ) {
        if (eventIds.isEmpty() || ttlSeconds <= 0L) return
        pruneExpired(nowSeconds)
        val expiresAt = nowSeconds + ttlSeconds
        eventIds.distinct().forEach { eventId ->
            localSatisfiedUntilByEventId[eventId] = expiresAt
        }
    }

    override fun isLocallySatisfied(eventId: String, nowSeconds: Long): Boolean {
        val expiresAt = localSatisfiedUntilByEventId[eventId] ?: return false
        if (expiresAt > nowSeconds) return true
        localSatisfiedUntilByEventId.remove(eventId, expiresAt)
        return false
    }

    override fun filterLocallySatisfied(eventIds: Collection<String>, nowSeconds: Long): Set<String> {
        if (eventIds.isEmpty()) return emptySet()
        pruneExpired(nowSeconds)
        return eventIds.asSequence()
            .distinct()
            .filter { eventId -> isLocallySatisfied(eventId, nowSeconds) }
            .toCollection(LinkedHashSet())
    }

    override fun markLocallyPendingRelay(
        eventIds: Collection<String>,
        nowSeconds: Long,
        ttlSeconds: Long,
    ) {
        if (eventIds.isEmpty() || ttlSeconds <= 0L) return
        pruneExpired(nowSeconds)
        val expiresAt = nowSeconds + ttlSeconds
        eventIds.distinct().forEach { eventId ->
            localPendingRelayUntilByEventId[eventId] = expiresAt
        }
    }

    override fun isLocallyPendingRelay(eventId: String, nowSeconds: Long): Boolean {
        val expiresAt = localPendingRelayUntilByEventId[eventId] ?: return false
        if (expiresAt > nowSeconds) return true
        localPendingRelayUntilByEventId.remove(eventId, expiresAt)
        return false
    }

    override fun filterLocallyPendingRelay(eventIds: Collection<String>, nowSeconds: Long): Set<String> {
        if (eventIds.isEmpty()) return emptySet()
        pruneExpired(nowSeconds)
        return eventIds.asSequence()
            .distinct()
            .filter { eventId -> isLocallyPendingRelay(eventId, nowSeconds) }
            .toCollection(LinkedHashSet())
    }

    override fun clearLocallyPendingRelay(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        eventIds.distinct().forEach { eventId ->
            localPendingRelayUntilByEventId.remove(eventId)
        }
    }

    override fun markLocallyForwardedSuccess(
        eventIds: Collection<String>,
        nowSeconds: Long,
        ttlSeconds: Long,
    ) {
        if (eventIds.isEmpty() || ttlSeconds <= 0L) return
        pruneExpired(nowSeconds)
        val expiresAt = nowSeconds + ttlSeconds
        eventIds.distinct().forEach { eventId ->
            localForwardedSuccessUntilByEventId[eventId] = expiresAt
        }
    }

    override fun isLocallyForwardedSuccess(eventId: String, nowSeconds: Long): Boolean {
        val expiresAt = localForwardedSuccessUntilByEventId[eventId] ?: return false
        if (expiresAt > nowSeconds) return true
        localForwardedSuccessUntilByEventId.remove(eventId, expiresAt)
        return false
    }

    override fun filterLocallyForwardedSuccess(eventIds: Collection<String>, nowSeconds: Long): Set<String> {
        if (eventIds.isEmpty()) return emptySet()
        pruneExpired(nowSeconds)
        return eventIds.asSequence()
            .distinct()
            .filter { eventId -> isLocallyForwardedSuccess(eventId, nowSeconds) }
            .toCollection(LinkedHashSet())
    }

    override fun markPeerSatisfied(
        peerId: String,
        eventIds: Collection<String>,
        nowSeconds: Long,
        ttlSeconds: Long,
    ) {
        if (peerId.isBlank() || eventIds.isEmpty() || ttlSeconds <= 0L) return
        pruneExpired(nowSeconds)
        val expiresAt = nowSeconds + ttlSeconds
        eventIds.distinct().forEach { eventId ->
            peerSatisfiedUntilByPeerAndEventId[peerKey(peerId, eventId)] = expiresAt
        }
    }

    override fun filterPeerSatisfied(peerId: String, eventIds: Collection<String>, nowSeconds: Long): Set<String> {
        if (peerId.isBlank() || eventIds.isEmpty()) return emptySet()
        pruneExpired(nowSeconds)
        return eventIds.asSequence()
            .distinct()
            .filter { eventId ->
                val key = peerKey(peerId, eventId)
                val expiresAt = peerSatisfiedUntilByPeerAndEventId[key] ?: return@filter false
                if (expiresAt > nowSeconds) {
                    true
                } else {
                    peerSatisfiedUntilByPeerAndEventId.remove(key, expiresAt)
                    false
                }
            }
            .toCollection(LinkedHashSet())
    }

    private fun pruneExpired(nowSeconds: Long) {
        localSatisfiedUntilByEventId.entries.removeIf { (_, expiresAt) -> expiresAt <= nowSeconds }
        localPendingRelayUntilByEventId.entries.removeIf { (_, expiresAt) -> expiresAt <= nowSeconds }
        localForwardedSuccessUntilByEventId.entries.removeIf { (_, expiresAt) -> expiresAt <= nowSeconds }
        peerSatisfiedUntilByPeerAndEventId.entries.removeIf { (_, expiresAt) -> expiresAt <= nowSeconds }
    }

    private fun peerKey(peerId: String, eventId: String): String {
        return "$peerId::$eventId"
    }
}
