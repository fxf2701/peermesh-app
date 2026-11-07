package app.peermesh.data.relay

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventMeta
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.relay.RelayEncryptedBody
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.security.crypto.RelayPayloadCrypto
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayEventMapperTest {
    @Test
    fun mapWithReason_encryptsRelayBodyWithoutPlaintext() {
        val groupKey = ByteArray(32) { 4 }
        val core = sampleCore()
        val entity = sampleEntity(core)
        val mapper = RelayEventMapper(
            groupKeyLoader = { gid -> if (gid == entity.gid) groupKey else null },
            nowSeconds = { 1700000000L },
        )

        val result = mapper.mapWithReason(entity)

        val relayEvent = result.relayEvent
        assertNotNull(relayEvent)
        assertNull(result.dropReason)
        val bodyJson = relayEvent!!.body.toString()
        assertFalse(bodyJson.contains("hello relay secret"))
        val body = CanonicalJson.json.decodeFromJsonElement(RelayEncryptedBody.serializer(), relayEvent.body)
        assertEquals("AES-256-GCM", body.enc)
        val verified = RelayPayloadCrypto.verifyAndDecrypt(relayEvent, groupKey)
        assertEquals(core.id, verified?.core?.id)
    }

    @Test
    fun mapWithReason_rejectsMissingKeyId() {
        val groupKey = ByteArray(32) { 4 }
        val core = sampleCore().copy(meta = null)
        val entity = sampleEntity(core)
        val mapper = RelayEventMapper(
            groupKeyLoader = { gid -> if (gid == entity.gid) groupKey else null },
            nowSeconds = { 1700000000L },
        )

        val result = mapper.mapWithReason(entity)

        assertNull(result.relayEvent)
        assertEquals("missing_key_id", result.dropReason)
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
            data = CanonicalJson.json.parseToJsonElement("{\"text\":\"hello relay secret\"}"),
        )
    }

    private fun sampleEntity(core: EventCore): EventEntity {
        return EventEntity(
            gid = "gid",
            event_id = core.id,
            type = core.type,
            source = "mesh",
            created_t = core.time.t,
            time_lt = core.time.lt ?: 0L,
            actor_did = core.actor.did,
            ref_root = core.ref.root,
            ref_parent = core.ref.parent,
            ref_target = core.ref.target,
            ttl_s = core.meta?.ttl_s,
            payload_json = CanonicalJson.json.encodeToString(EventCore.serializer(), core),
            inserted_at = 1700000001L,
        )
    }
}
