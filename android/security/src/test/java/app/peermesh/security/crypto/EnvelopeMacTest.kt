package app.peermesh.security.crypto

import app.peermesh.core.model.EncPayload
import app.peermesh.core.model.DtnMeta
import app.peermesh.core.model.EventEnvelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeMacTest {
    @Test
    fun computeAndVerifyMac_roundTrip() {
        val groupKey = ByteArray(32) { 7 }
        val envelope = EventEnvelope(
            v = 1,
            gid = "gid",
            mid = "mid",
            ts = 1700000000L,
            ttl = 6,
            dtn = DtnMeta(ttl_seconds = 6, hop_count = 0, max_hops = 20, priority = 3),
            enc = EncPayload(ct = "ciphertext")
        )

        val mac = EnvelopeMac.computeMac(groupKey, envelope)
        assertTrue(EnvelopeMac.verifyMac(groupKey, envelope, mac))

        val tampered = envelope.copy(mid = "mid2")
        assertFalse(EnvelopeMac.verifyMac(groupKey, tampered, mac))
    }
}
