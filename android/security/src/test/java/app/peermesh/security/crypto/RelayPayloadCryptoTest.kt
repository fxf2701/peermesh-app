package app.peermesh.security.crypto

import app.peermesh.core.id.RelayEventIdFactory
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventMeta
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.relay.RelayEvent
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayPayloadCryptoTest {
    @Test
    fun encryptVerifyDecrypt_roundTrip() {
        val groupKey = ByteArray(32) { 9 }
        val core = sampleCore()
        val eid = RelayEventIdFactory.fromEventId(core.id)
        val body = RelayPayloadCrypto.encryptBody(
            gid = "gid",
            eid = eid,
            ts = core.time.t,
            exp = core.time.t + 3600,
            core = core,
            groupKey = groupKey,
            kid = "gk1",
        )
        val unsigned = RelayEvent(
            eid = eid,
            gid = "gid",
            ts = core.time.t,
            exp = core.time.t + 3600,
            body = CanonicalJson.json.encodeToJsonElement(body),
            sig = "",
        )
        val signed = unsigned.copy(sig = RelayPayloadCrypto.signRelayEvent(unsigned, groupKey))

        val verified = RelayPayloadCrypto.verifyAndDecrypt(signed, groupKey)

        assertNotNull(verified)
        assertEquals(core.id, verified?.core?.id)
        assertEquals("gk1", verified?.body?.kid)
    }

    @Test
    fun verifyAndDecrypt_rejectsTamperedExpiry() {
        val groupKey = ByteArray(32) { 9 }
        val core = sampleCore()
        val eid = RelayEventIdFactory.fromEventId(core.id)
        val body = RelayPayloadCrypto.encryptBody(
            gid = "gid",
            eid = eid,
            ts = core.time.t,
            exp = core.time.t + 3600,
            core = core,
            groupKey = groupKey,
            kid = "gk1",
        )
        val unsigned = RelayEvent(
            eid = eid,
            gid = "gid",
            ts = core.time.t,
            exp = core.time.t + 3600,
            body = CanonicalJson.json.encodeToJsonElement(body),
            sig = "",
        )
        val signed = unsigned.copy(sig = RelayPayloadCrypto.signRelayEvent(unsigned, groupKey))

        val tampered = signed.copy(exp = signed.exp + 1)

        assertNull(RelayPayloadCrypto.verifyAndDecrypt(tampered, groupKey))
    }

    private fun sampleCore(): EventCore {
        return EventCore(
            ev = 1,
            id = "event-id",
            type = "CHAT.MSG",
            actor = Actor(did = "device"),
            time = EventTime(t = 1700000000L, lt = 1L),
            ref = EventRef(root = "thread", parent = null, target = null),
            meta = EventMeta(kid = "gk1"),
            data = CanonicalJson.json.parseToJsonElement("{\"text\":\"hi relay\"}"),
        )
    }
}
