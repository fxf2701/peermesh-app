package app.peermesh.transport.ble

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class EnvelopeDeduper(
    private val ttlSeconds: Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val entries = ConcurrentHashMap<String, Long>()

    fun seen(payload: ByteArray, nowSeconds: Long): Boolean {
        val key = hash(payload)
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

    fun forget(payload: ByteArray) {
        entries.remove(hash(payload))
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

    private fun hash(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val out = digest.digest(payload)
        return out.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 2_000
    }
}
