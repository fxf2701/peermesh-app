package app.peermesh.transport.ble

import java.util.concurrent.ConcurrentHashMap

class EventIdDeduper(
    private val ttlSeconds: Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val entries = ConcurrentHashMap<String, Long>()

    fun seen(gid: String, eventId: String, nowSeconds: Long): Boolean {
        val key = "$gid:$eventId"
        val existing = entries[key]
        if (existing == null || existing < nowSeconds) {
            entries[key] = nowSeconds + ttlSeconds
            trimIfNeeded(nowSeconds)
            return false
        }
        return true
    }

    fun prune(nowSeconds: Long) {
        entries.forEach { (key, expiresAt) ->
            if (expiresAt < nowSeconds) {
                entries.remove(key, expiresAt)
            }
        }
    }

    private fun trimIfNeeded(nowSeconds: Long) {
        if (entries.size <= maxEntries) return
        prune(nowSeconds)
        if (entries.size <= maxEntries) return
        val overflow = entries.size - maxEntries
        var removed = 0
        val iter = entries.keys.iterator()
        while (iter.hasNext() && removed < overflow) {
            iter.next()
            iter.remove()
            removed += 1
        }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 2_000
    }
}
