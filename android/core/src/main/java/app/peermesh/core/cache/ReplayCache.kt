package app.peermesh.core.cache

import java.util.concurrent.ConcurrentHashMap

class ReplayCache(private val ttlSeconds: Long) {
    private val entries = ConcurrentHashMap<String, Long>()

    fun seen(key: String, nowSeconds: Long): Boolean {
        val existing = entries[key]
        if (existing == null) {
            entries[key] = nowSeconds + ttlSeconds
            return false
        }
        if (existing < nowSeconds) {
            entries[key] = nowSeconds + ttlSeconds
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
}
