package app.peermesh.data.transport

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.id.EventIdFactory
import app.peermesh.core.model.Actor
import app.peermesh.core.model.AppBatchEnvelope
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.model.EventMeta
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.types.EventTypes
import app.peermesh.core.transport.EnvelopeBroadcaster
import app.peermesh.security.crypto.GroupKeyDeriver
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeSenderTest {
    @Test
    fun send_encryptsPayload_noPlaintextInEnvelope() {
        var captured: ByteArray? = null
        val broadcaster = object : EnvelopeBroadcaster {
            override fun broadcastEnvelope(blob: ByteArray): Boolean {
                captured = blob
                return true
            }
        }
        val passphrase = "secret-pass"
        val salt = ByteArray(16) { 2 }
        val gid = "gid-test"
        val groupKey = GroupKeyDeriver.deriveGroupKey(passphrase.toCharArray(), salt)
        val sender = EnvelopeSender(broadcaster) { loadedGid ->
            if (loadedGid == gid) groupKey else null
        }
        val now = System.currentTimeMillis() / 1000L
        val core = EventCore(
            ev = 1,
            id = EventIdFactory.generate(gid, "actor", now, EventIdFactory.generateNonce()),
            type = EventTypes.CHAT_MSG,
            actor = Actor(did = "actor"),
            time = EventTime(t = now, lt = 1),
            ref = EventRef(root = null, parent = null, target = null),
            meta = EventMeta(ttl_s = 3600, prio = "normal"),
            data = buildJsonObject {
                put("text", "hello secret")
            }
        )

        val ok = sender.send(gid, core, ttlSeconds = null)
        assertTrue(ok)
        waitForCapture { captured != null }
        val json = captured?.toString(Charsets.UTF_8) ?: ""
        val batch = CanonicalJson.json.decodeFromString<AppBatchEnvelope>(json)
        assertEquals(1, batch.items.size)
        val innerJson = Base64Url.decode(batch.items.single()).toString(Charsets.UTF_8)
        val envelope = CanonicalJson.json.decodeFromString<EventEnvelope>(innerJson)
        assertFalse(json.contains("\"text\""))
        assertFalse(json.contains("hello secret"))
        assertTrue(innerJson.contains("\"enc\""))
        assertNotNull(envelope.dtn)
        assertEquals(DtnTransportPolicy.DEFAULT_TTL_SECONDS, envelope.dtn?.ttl_seconds)
        assertEquals(DtnTransportPolicy.DEFAULT_MAX_HOPS, envelope.dtn?.max_hops)
        assertEquals(DtnTransportPolicy.PRIORITY_CHAT, envelope.dtn?.priority)
    }

    @Test
    fun send_sysAck_usesAckBackgroundPriority() {
        var captured: ByteArray? = null
        val broadcaster = object : EnvelopeBroadcaster {
            override fun broadcastEnvelope(blob: ByteArray): Boolean {
                captured = blob
                return true
            }
        }
        val passphrase = "secret-pass"
        val salt = ByteArray(16) { 3 }
        val gid = "gid-test"
        val groupKey = GroupKeyDeriver.deriveGroupKey(passphrase.toCharArray(), salt)
        val sender = EnvelopeSender(broadcaster) { loadedGid ->
            if (loadedGid == gid) groupKey else null
        }
        val now = System.currentTimeMillis() / 1000L
        val core = EventCore(
            ev = 1,
            id = EventIdFactory.generate(gid, "actor", now, EventIdFactory.generateNonce()),
            type = EventTypes.SYS_ACK,
            actor = Actor(did = "actor"),
            time = EventTime(t = now, lt = 1),
            ref = EventRef(root = null, parent = null, target = "msg-1"),
            meta = null,
            data = buildJsonObject {
                put("target_id", "msg-1")
                put("ack_type", "received")
            }
        )

        val ok = sender.send(gid, core, ttlSeconds = null)
        assertTrue(ok)
        waitForCapture(timeoutMs = 12_000L) { captured != null }
        val json = captured?.toString(Charsets.UTF_8) ?: ""
        val batch = CanonicalJson.json.decodeFromString<AppBatchEnvelope>(json)
        assertEquals(1, batch.items.size)
        val innerJson = Base64Url.decode(batch.items.single()).toString(Charsets.UTF_8)
        val envelope = CanonicalJson.json.decodeFromString<EventEnvelope>(innerJson)
        assertEquals(DtnTransportPolicy.PRIORITY_ACK_BACKGROUND, envelope.dtn?.priority)
    }

    private fun waitForCapture(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Timed out waiting for batch capture")
            }
            Thread.sleep(50L)
        }
    }
}
