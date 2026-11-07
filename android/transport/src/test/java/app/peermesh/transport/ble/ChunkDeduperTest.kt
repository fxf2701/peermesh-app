package app.peermesh.transport.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkDeduperTest {
    @Test
    fun seen_filtersDuplicatesWithinTtl() {
        val deduper = ChunkDeduper(ttlSeconds = 10)
        val chunk = Chunking.split(ByteArray(50) { 1 }, 20).first()
        assertFalse(deduper.seen("device-1", chunk, nowSeconds = 100))
        assertTrue(deduper.seen("device-1", chunk, nowSeconds = 105))
    }
}
