package app.peermesh.transport.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeDeduperTest {
    @Test
    fun seen_filtersDuplicatesWithinTtl() {
        val deduper = EnvelopeDeduper(ttlSeconds = 5)
        val payload = "test".toByteArray()
        assertFalse(deduper.seen(payload, nowSeconds = 100))
        assertTrue(deduper.seen(payload, nowSeconds = 102))
    }

    @Test
    fun forget_keepsReceiverRejectedPacketRecoverable() {
        val deduper = EnvelopeDeduper(ttlSeconds = 5)
        val payload = "retryable".toByteArray()
        assertFalse(deduper.seen(payload, nowSeconds = 100))
        deduper.forget(payload)
        assertFalse(deduper.seen(payload, nowSeconds = 101))
    }
}
