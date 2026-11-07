package app.peermesh

import app.peermesh.core.factory.EventFactory
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.map.MapActionMetadata
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemType
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventMeta
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.ingest.EventSources
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudToBleBridgeM2D2ContractTest {
    @Test
    fun relayOriginCanonicalAndDedicatedMapEventsUseExistingBleBridgePolicy() {
        val cores = listOf(markerAdd(), confirmation())

        cores.forEach { core ->
            val persisted = entity(core, EventSources.RELAY)
            val bridged = CloudToBleBridge.bridgeableCore(persisted)

            assertNotNull(bridged)
            assertEquals(core, bridged)
            assertTrue(CloudToBleBridge.shouldBridge(EventSources.RELAY, core.type))
        }
    }

    @Test
    fun bridgeReusesPersistedEventCoreWithoutSemanticMutation() {
        val core = confirmation()
        val persisted = entity(core, EventSources.RELAY)

        val bridged = CloudToBleBridge.bridgeableCore(persisted)

        assertEquals(core.id, bridged?.id)
        assertEquals(core.type, bridged?.type)
        assertEquals(core.actor, bridged?.actor)
        assertEquals(core.time, bridged?.time)
        assertEquals(core.ref, bridged?.ref)
        assertEquals(core.meta, bridged?.meta)
        assertEquals(core.data, bridged?.data)
    }

    @Test
    fun localMeshAndDtnOriginEventsAreNotRebridgedAsRelayEvents() {
        val core = markerAdd()
        listOf("local", EventSources.MESH, EventSources.DTN).forEach { source ->
            assertNull(CloudToBleBridge.bridgeableCore(entity(core, source)))
        }
    }

    @Test
    fun bridgeEligibilityAndDecodeFailureCannotMutateCommittedMapState() {
        val core = markerAdd()
        val persisted = entity(core, EventSources.RELAY)
        val before = persisted.copy()

        assertEquals(core, CloudToBleBridge.bridgeableCore(persisted))
        assertEquals(before, persisted)
        assertNull(CloudToBleBridge.bridgeableCore(persisted.copy(payload_json = "{malformed")))
        assertEquals(before, persisted)
    }

    @Test
    fun existingNonMapBridgeBehaviorRemainsUnchanged() {
        val chat = EventFactory.chatMessage("chat", "did:owner", 100, 1, "help")
        val ack = EventFactory.sysAck("ack", "did:owner", 100, 2, "chat", "delivered")

        assertNotNull(CloudToBleBridge.bridgeableCore(entity(chat, EventSources.RELAY)))
        assertNull(CloudToBleBridge.bridgeableCore(entity(ack, EventSources.RELAY)))
        assertTrue(CloudToBleBridge.shouldBridge(EventSources.RELAY, EventTypes.CHAT_MSG))
    }

    private fun markerAdd(): EventCore = EventFactory.mapMarkerAdd(
        eventId = "marker-add",
        actorDid = "did:owner",
        createdSeconds = 1_700_000_000,
        lamport = 11,
        markerId = "marker-1",
        layerId = "risk",
        lat = 10.0,
        lon = 106.0,
        radiusM = 20.0,
        label = "Flood",
        note = "Deep water",
        kind = "risk",
        ttlSeconds = 3600,
    ).copy(meta = EventMeta(ttl_s = 3600, kid = "kid-1"))

    private fun confirmation(): EventCore = EventFactory.mapConfirmation(
        MapItemType.MARKER,
        MapConfirmation(
            MapActionMetadata("confirm", "marker-1", "did:observer", 1_700_000_001, 12),
            MapInformationSource.FIRSTHAND,
            "Seen",
        ),
    ).copy(meta = EventMeta(kid = "kid-1"))

    private fun entity(core: EventCore, source: String): EventEntity = EventEntity(
        id = 1,
        gid = "g",
        event_id = core.id,
        type = core.type,
        source = source,
        created_t = core.time.t,
        time_lt = core.time.lt ?: 0,
        actor_did = core.actor.did,
        ref_root = core.ref.root,
        ref_parent = core.ref.parent,
        ref_target = core.ref.target,
        ttl_s = core.meta?.ttl_s,
        payload_json = CanonicalJson.json.encodeToString(EventCore.serializer(), core),
        inserted_at = core.time.t + 1,
    )
}
