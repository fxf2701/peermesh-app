package app.peermesh.transport.ble

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class ChunkDeduper(
    private val ttlSeconds: Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Key(
        val senderKey: String,
        val chunkId: Int,
        val index: Int,
    )
    private val entries = ConcurrentHashMap<Key, Long>()
    private val minHeaderSize = 10

    fun seen(senderKey: String, chunk: ByteArray, nowSeconds: Long): Boolean {
        if (chunk.size < minHeaderSize) return false
        val buffer = ByteBuffer.wrap(chunk)
        val chunkId = buffer.int
        val index = buffer.get().toInt() and 0xFF
        val key = Key(senderKey, chunkId, index)
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
        private const val DEFAULT_MAX_ENTRIES = 5_000
    }
}
