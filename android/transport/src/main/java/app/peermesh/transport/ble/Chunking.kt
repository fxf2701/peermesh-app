package app.peermesh.transport.ble

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.security.SecureRandom

object Chunking {
    private const val HEADER_SIZE = 10
    private const val MAX_CHUNKS = 255
    private val chunkCounter = AtomicInteger()
    private val chunkSessionSalt = SecureRandom().nextInt()

    fun split(payload: ByteArray, maxChunkSize: Int): List<ByteArray> {
        val chunkSize = maxChunkSize - HEADER_SIZE
        require(chunkSize > 0) { "maxChunkSize too small" }
        val total = (payload.size + chunkSize - 1) / chunkSize
        require(total <= MAX_CHUNKS) { "payload too large" }

        val crc32 = CRC32().apply { update(payload) }.value
        val chunkId = chunkCounter.incrementAndGet() xor chunkSessionSalt
        val chunks = ArrayList<ByteArray>(total)
        for (i in 0 until total) {
            val start = i * chunkSize
            val end = minOf(payload.size, start + chunkSize)
            val slice = payload.copyOfRange(start, end)
            val buffer = ByteBuffer.allocate(HEADER_SIZE + slice.size)
            buffer.putInt(chunkId)
            buffer.put(i.toByte())
            buffer.put(total.toByte())
            buffer.putInt(crc32.toInt())
            buffer.put(slice)
            chunks.add(buffer.array())
        }
        return chunks
    }

    fun payloadBytesFromChunkBytes(chunkBytes: Int): Int {
        return (chunkBytes - HEADER_SIZE).coerceAtLeast(0)
    }

    internal fun reassemblePayload(chunks: List<ByteArray>, senderKey: String, nowSeconds: Long): ByteArray? {
        val reassembler = Reassembler(ttlSeconds = Long.MAX_VALUE)
        chunks.forEach { chunk ->
            val reassembled = reassembler.add(senderKey, chunk, nowSeconds)
            if (!reassembled.checksumOk) return null
            if (reassembled.complete) return reassembled.payload
        }
        return null
    }

    data class Reassembled(
        val complete: Boolean,
        val payload: ByteArray?,
        val checksumOk: Boolean,
        val chunkId: Int? = null,
        val index: Int = -1,
        val total: Int = 0,
        val received: Int = 0,
        val reason: String = "",
    )

    class Reassembler(
        private val ttlSeconds: Long,
        private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    ) {
        private data class ReassemblyKey(
            val senderKey: String,
            val chunkId: Int,
        )
        private data class Entry(
            val createdAt: Long,
            val total: Int,
            val crc32: Int,
            val parts: Array<ByteArray?>,
            var receivedCount: Int = 0,
        )
        private val entries = ConcurrentHashMap<ReassemblyKey, Entry>()

        fun add(senderKey: String, chunk: ByteArray, nowSeconds: Long): Reassembled {
            if (chunk.size < HEADER_SIZE) return Reassembled(false, null, true, reason = "short")
            val buffer = ByteBuffer.wrap(chunk)
            val chunkId = buffer.int
            val index = buffer.get().toInt() and 0xFF
            val total = buffer.get().toInt() and 0xFF
            val crc32 = buffer.int
            if (total <= 0 || total > MAX_CHUNKS) {
                return Reassembled(
                    complete = false,
                    payload = null,
                    checksumOk = true,
                    chunkId = chunkId,
                    index = index,
                    total = total,
                    reason = "invalid_total",
                )
            }

            val payload = ByteArray(chunk.size - HEADER_SIZE)
            buffer.get(payload)

            val key = ReassemblyKey(senderKey, chunkId)
            val entry = entries[key] ?: run {
                val created = Entry(nowSeconds, total, crc32, arrayOfNulls(total))
                entries.putIfAbsent(key, created) ?: created
            }
            if (entry.crc32 != crc32) {
                entries.remove(key)
                return Reassembled(
                    complete = false,
                    payload = null,
                    checksumOk = false,
                    chunkId = chunkId,
                    index = index,
                    total = total,
                    reason = "crc_header_mismatch",
                )
            }

            // Defensive: reject inconsistent total across chunks for the same (senderKey, chunkId)
            if (entry.total != total) {
                entries.remove(key)
                return Reassembled(
                    complete = false,
                    payload = null,
                    checksumOk = true,
                    chunkId = chunkId,
                    index = index,
                    total = total,
                    received = entry.parts.count { it != null },
                    reason = "total_mismatch",
                )
            }
            if (index >= entry.total) {
                return Reassembled(
                    complete = false,
                    payload = null,
                    checksumOk = true,
                    chunkId = chunkId,
                    index = index,
                    total = entry.total,
                    reason = "index_oob",
                )
            }

            val result: Reassembled
            synchronized(entry) {
                if (entry.parts[index] == null) {
                    entry.parts[index] = payload
                    entry.receivedCount += 1
                }
                val received = entry.receivedCount
                result = if (received == entry.total) {
                    val size = entry.parts.sumOf { it!!.size }
                    val out = ByteArray(size)
                    var offset = 0
                    entry.parts.forEach { part ->
                        val bytes = part!!
                        System.arraycopy(bytes, 0, out, offset, bytes.size)
                        offset += bytes.size
                    }
                    entries.remove(key)
                    val actualCrc = CRC32().apply { update(out) }.value.toInt()
                    val checksumOk = actualCrc == entry.crc32
                    Reassembled(
                        complete = true,
                        payload = out,
                        checksumOk = checksumOk,
                        chunkId = chunkId,
                        index = index,
                        total = entry.total,
                        received = received,
                        reason = "complete",
                    )
                } else {
                    Reassembled(
                        complete = false,
                        payload = null,
                        checksumOk = true,
                        chunkId = chunkId,
                        index = index,
                        total = entry.total,
                        received = received,
                        reason = "waiting",
                    )
                }
            }

            if (!result.complete) {
                trimIfNeeded(nowSeconds)
            }
            return result
        }

        fun prune(nowSeconds: Long) {
            entries.forEach { (key, entry) ->
                if (nowSeconds - entry.createdAt > ttlSeconds) {
                    entries.remove(key, entry)
                }
            }
        }

        private fun trimIfNeeded(nowSeconds: Long) {
            if (entries.size <= maxEntries) return
            prune(nowSeconds)
            if (entries.size <= maxEntries) return
            var oldestKey: ReassemblyKey? = null
            var oldestTime = Long.MAX_VALUE
            entries.forEach { (key, entry) ->
                if (entry.createdAt < oldestTime) {
                    oldestTime = entry.createdAt
                    oldestKey = key
                }
            }
            oldestKey?.let { entries.remove(it) }
        }
    }

    private const val DEFAULT_MAX_ENTRIES = 200
}
