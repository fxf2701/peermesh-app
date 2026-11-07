package app.peermesh

import app.peermesh.ble.BlePeerCloudStateRegistry
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.id.EventIdFactory
import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventMeta
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.ingest.EventSources
import app.peermesh.transport.ble.dtn.DtnCloudStates
import app.peermesh.transport.ble.dtn.DtnCloudStatus
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudToBleBridgeTest {
    @Before
    fun setUp() {
        BlePeerCloudStateRegistry.clearForTest()
    }

    @Test
    fun relayOriginatedChatIsBridgeable() {
        val entity = event(type = EventTypes.CHAT_MSG, source = EventSources.RELAY)

        val core = CloudToBleBridge.bridgeableCore(entity)

        assertNotNull(core)
        assertEquals(entity.event_id, core?.id)
    }

    @Test
    fun relayOriginatedAckIsNotBridgeable() {
        val entity = event(type = EventTypes.SYS_ACK, source = EventSources.RELAY)

        val core = CloudToBleBridge.bridgeableCore(entity)

        assertNull(core)
    }

    @Test
    fun relayOriginatedMemberRemovedIsNotBridgeable() {
        val entity = event(type = EventTypes.SYS_MEMBER_REMOVED, source = EventSources.RELAY)

        val core = CloudToBleBridge.bridgeableCore(entity)

        assertNull(core)
    }

    @Test
    fun meshOriginatedChatIsNotRebridgedToBle() {
        assertTrue(!CloudToBleBridge.shouldBridge(EventSources.MESH, EventTypes.CHAT_MSG))
        assertTrue(!CloudToBleBridge.shouldBridge(EventSources.DTN, EventTypes.CHAT_MSG))
    }

    @Test
    fun bridgeRequiresRecentPeerWithDisabledOrStaleCloudState() {
        assertTrue(!BlePeerCloudStateRegistry.shouldBridgeGroup("g", nowMs = 100_000L))

        BlePeerCloudStateRegistry.update(
            peerId = "peer-a",
            gid = "g",
            cloudStatus = DtnCloudStatus(state = DtnCloudStates.OK, lastSuccessAtSeconds = 95L),
            seenAtSeconds = 100L,
        )
        assertTrue(!BlePeerCloudStateRegistry.shouldBridgeGroup("g", nowMs = 100_000L))

        BlePeerCloudStateRegistry.update(
            peerId = "peer-b",
            gid = "g",
            cloudStatus = DtnCloudStatus(state = DtnCloudStates.STALE, lastSuccessAtSeconds = 10L),
            seenAtSeconds = 100L,
        )
        assertTrue(BlePeerCloudStateRegistry.shouldBridgeGroup("g", nowMs = 100_000L))
    }

    @Test
    fun invalidRelayPayloadIsDropped() {
        val entity = EventEntity(
            id = 1L,
            gid = "g",
            event_id = "broken",
            type = EventTypes.CHAT_MSG,
            source = EventSources.RELAY,
            created_t = 100L,
            time_lt = 1L,
            actor_did = "actor",
            ref_root = null,
            ref_parent = null,
            ref_target = null,
            ttl_s = null,
            payload_json = "{not json}",
            inserted_at = 100L,
        )

        val core = CloudToBleBridge.bridgeableCore(entity)

        assertNull(core)
    }

    private fun event(type: String, source: String): EventEntity {
        val core = EventCore(
            ev = 1,
            id = EventIdFactory.generate(
                gid = "g",
                deviceId = "device-1",
                ts = 100L,
                nonce = ByteArray(16) { 1 },
            ),
            type = type,
            actor = Actor(did = "actor"),
            time = EventTime(t = 100L, lt = 1L),
            ref = EventRef(root = null, parent = null, target = refTargetFor(type)),
            meta = EventMeta(kid = "kid-1"),
            data = dataFor(type),
        )
        return EventEntity(
            id = 1L,
            gid = "g",
            event_id = core.id,
            type = type,
            source = source,
            created_t = 100L,
            time_lt = 1L,
            actor_did = "actor",
            ref_root = null,
            ref_parent = null,
            ref_target = null,
            ttl_s = null,
            payload_json = CanonicalJson.json.encodeToString(EventCore.serializer(), core),
            inserted_at = 100L,
        )
    }

    private fun refTargetFor(type: String): String? {
        return if (type == EventTypes.SYS_MEMBER_REMOVED) {
            "target-peer"
        } else {
            null
        }
    }

    private fun dataFor(type: String): kotlinx.serialization.json.JsonElement {
        return if (type == EventTypes.SYS_MEMBER_REMOVED) {
            kotlinx.serialization.json.buildJsonObject {
                put("target_peer_id", kotlinx.serialization.json.JsonPrimitive("target-peer"))
            }
        } else {
            kotlinx.serialization.json.JsonObject(emptyMap())
        }
    }
}
