package app.peermesh.transport.ble

import app.peermesh.core.transport.EnvelopeBroadcaster
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtlForwarderTest {
    @Test
    fun forwardIfAllowed_blocksWhenTtlZero() {
        val transport = object : EnvelopeBroadcaster {
            var forwarded = false
            override fun broadcastEnvelope(blob: ByteArray): Boolean {
                forwarded = true
                return true
            }
        }
        val forwarder = TtlForwarder(transport)
        val payload = envelopeJson(ttl = 0).toByteArray()
        assertFalse(forwarder.forwardIfAllowed(payload))
        assertFalse(transport.forwarded)
    }

    @Test
    fun forwardIfAllowed_decrementsTtl() {
        val transport = object : EnvelopeBroadcaster {
            var forwardedPayload: ByteArray? = null
            override fun broadcastEnvelope(blob: ByteArray): Boolean {
                forwardedPayload = blob
                return true
            }
        }
        val forwarder = TtlForwarder(transport)
        val payload = envelopeJson(ttl = 2).toByteArray()
        assertTrue(forwarder.forwardIfAllowed(payload))
        val text = transport.forwardedPayload!!.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"ttl\":1"))
    }

    @Test
    fun forwardIfAllowed_keepsSignedDtnPayloadUnchanged() {
        val transport = object : EnvelopeBroadcaster {
            var forwardedPayload: ByteArray? = null
            override fun broadcastEnvelope(blob: ByteArray): Boolean {
                forwardedPayload = blob
                return true
            }
        }
        val forwarder = TtlForwarder(transport)
        val payload = envelopeJson(ttl = 100, dtn = ""","dtn":{"ttl_seconds":60,"hop_count":1,"max_hops":3,"priority":2}""").toByteArray()

        assertTrue(forwarder.forwardIfAllowed(payload, nowSeconds = 1_010))
        assertTrue(payload.contentEquals(transport.forwardedPayload))
    }

    @Test
    fun forwardIfAllowed_blocksExpiredDtnPayload() {
        val transport = object : EnvelopeBroadcaster {
            var forwarded = false
            override fun broadcastEnvelope(blob: ByteArray): Boolean {
                forwarded = true
                return true
            }
        }
        val forwarder = TtlForwarder(transport)
        val payload = envelopeJson(ttl = 100, dtn = ""","dtn":{"ttl_seconds":60,"hop_count":1,"max_hops":3,"priority":2}""").toByteArray()

        assertFalse(forwarder.forwardIfAllowed(payload, nowSeconds = 1_061))
        assertFalse(transport.forwarded)
    }

    @Test
    fun forwardIfAllowed_blocksDtnPayloadAtMaxHops() {
        val transport = object : EnvelopeBroadcaster {
            var forwarded = false
            override fun broadcastEnvelope(blob: ByteArray): Boolean {
                forwarded = true
                return true
            }
        }
        val forwarder = TtlForwarder(transport)
        val payload = envelopeJson(ttl = 100, dtn = ""","dtn":{"ttl_seconds":60,"hop_count":3,"max_hops":3,"priority":2}""").toByteArray()

        assertFalse(forwarder.forwardIfAllowed(payload, nowSeconds = 1_010))
        assertFalse(transport.forwarded)
    }

    private fun envelopeJson(ttl: Int, dtn: String = ""): String {
        return """{"v":1,"gid":"g","mid":"m","ts":1000,"ttl":$ttl$dtn,"enc":{"ct":"cipher"},"sig":{"mac":"sig"}}"""
    }
}
