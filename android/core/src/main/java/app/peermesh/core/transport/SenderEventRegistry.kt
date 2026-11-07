package app.peermesh.core.transport

import java.util.concurrent.ConcurrentHashMap

object SenderEventRegistry {
    private const val ACTIVE_TTL_MS = 9_000L
    private val activeEvents = ConcurrentHashMap<String, Long>()

    fun reserve(eventId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        prune(nowMs)
        return activeEvents.putIfAbsent(eventId, nowMs) == null
    }

    fun release(eventId: String) {
        activeEvents.remove(eventId)
    }

    fun isActive(eventId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        prune(nowMs)
        return activeEvents.containsKey(eventId)
    }

    fun prune(nowMs: Long = System.currentTimeMillis()) {
        activeEvents.entries.removeIf { (_, reservedAtMs) ->
            nowMs - reservedAtMs > ACTIVE_TTL_MS
        }
    }
}
