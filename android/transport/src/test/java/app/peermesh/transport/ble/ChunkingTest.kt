package app.peermesh.transport.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkingTest {
    @Test
    fun splitAndReassemble_roundTrip() {
        val payload = ByteArray(200) { it.toByte() }
        val chunks = Chunking.split(payload, maxChunkSize = 20)
        val reassembler = Chunking.Reassembler(ttlSeconds = 10)
        var result: ByteArray? = null
        for (chunk in chunks) {
            val reassembled = reassembler.add("device-1", chunk, nowSeconds = 100)
            if (reassembled.complete) {
                result = reassembled.payload
            }
        }
        assertTrue(result != null)
        assertArrayEquals(payload, result)
    }

    @Test
    fun reassemblePayload_stripsChunkHeadersBeforeRechunking() {
        val payload = ByteArray(783) { (it % 251).toByte() }
        val largeChunks = Chunking.split(payload, maxChunkSize = 180)

        val reassembled = Chunking.reassemblePayload(
            chunks = largeChunks,
            senderKey = "device-1",
            nowSeconds = 100,
        )

        assertArrayEquals(payload, reassembled)
        assertTrue(largeChunks.size == 5)
    }
}
