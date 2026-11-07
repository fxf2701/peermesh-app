package app.peermesh.data.ingest

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.map.MapActionMetadata
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.map.MapDomainPayloadContract
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemEventCodec
import app.peermesh.core.map.MapItemType
import app.peermesh.core.model.Actor
import app.peermesh.core.model.AppBatchEnvelope
import app.peermesh.core.model.AppBatchItemV2
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.dao.ChatDao
import app.peermesh.data.db.dao.DeletedEventDao
import app.peermesh.data.db.dao.EventDedupeDao
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.dao.GroupDao
import app.peermesh.data.db.dao.LiveTrailDao
import app.peermesh.data.db.dao.RemovedMemberDao
import app.peermesh.data.db.dao.UserProfileDao
import app.peermesh.data.db.entity.EventDedupeEntity
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.data.map.processing.MapEventRejectionReason
import app.peermesh.security.crypto.AppBatchCrypto
import app.peermesh.security.crypto.EnvelopeCrypto
import app.peermesh.security.crypto.SecureKeyStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class EnvelopeReceiverM2D1ContractTest {
    @Test
    fun appBatchV1PreservesCanonicalMapIdentity() {
        val original = canonicalCore("canonical")
        val captured = mutableListOf<MapEventProcessingRequest>()
        val receiver = receiver { request ->
            captured += request
            MapEventProcessingResult.Accepted(request.core.id)
        }

        assertTrue(receiver.onEnvelopeReceivedWithMeta(v1Batch(original), EventSources.MESH).accepted)

        assertEquals(original, captured.single().core)
        assertEquals(original.id, captured.single().core.id)
        assertEquals(original.type, captured.single().core.type)
        assertEquals(original.actor.did, captured.single().core.actor.did)
        assertEquals(original.ref.target, captured.single().core.ref.target)
        assertEquals(original.time, captured.single().core.time)
        assertEquals(original.data, captured.single().core.data)
    }

    @Test
    fun appBatchV1PreservesDedicatedActionIdentity() {
        val base = actionCore("action-v1")
        val original = base.copy(data = JsonObject((base.data as JsonObject) + ("future_hint" to JsonPrimitive("kept"))))
        var captured: EventCore? = null
        val receiver = receiver {
            captured = it.core
            MapEventProcessingResult.Accepted(it.core.id)
        }

        assertTrue(receiver.onEnvelopeReceivedWithMeta(v1Batch(original), EventSources.MESH).accepted)

        assertEquals(original, captured)
        val payload = captured!!.data as JsonObject
        assertEquals(captured!!.id, payload[MapDomainPayloadContract.ACTION_ID_FIELD]?.let { (it as JsonPrimitive).content })
        assertEquals("marker-1", captured!!.ref.target)
        assertEquals("did:actor", captured!!.actor.did)
        assertEquals(1_700_000_123L, captured!!.time.t)
        assertEquals(41L, captured!!.time.lt)
        assertEquals("kept", (payload["future_hint"] as JsonPrimitive).content)
    }

    @Test
    fun appBatchV2ReceivePreservesDedicatedActionIdentity() {
        val original = actionCore("action-v2")
        var captured: EventCore? = null
        val receiver = receiver {
            captured = it.core
            MapEventProcessingResult.Accepted(it.core.id)
        }

        assertTrue(receiver.onEnvelopeReceivedWithMeta(v2Batch(original), EventSources.MESH).accepted)

        assertEquals(original, captured)
        assertEquals(original.id, captured!!.id)
        assertEquals(original.ref.target, captured!!.ref.target)
        assertEquals(original.actor, captured!!.actor)
        assertEquals(original.time, captured!!.time)
        assertEquals(original.data, captured!!.data)
    }

    @Test
    fun appBatchV2ReceivePreservesCanonicalMapIdentity() {
        val original = canonicalCore("canonical-v2")
        var captured: EventCore? = null
        val receiver = receiver {
            captured = it.core
            MapEventProcessingResult.Accepted(it.core.id)
        }

        assertTrue(receiver.onEnvelopeReceivedWithMeta(v2Batch(original), EventSources.MESH).accepted)

        assertEquals(original, captured)
    }

    @Test
    fun malformedSiblingDoesNotSuppressValidMapSibling() {
        val valid = canonicalCore("valid")
        val captured = mutableListOf<String>()
        val receiver = receiver {
            captured += it.core.id
            MapEventProcessingResult.Accepted(it.core.id)
        }

        val result = receiver.onEnvelopeReceivedWithMeta(
            signedBatch(AppBatchEnvelope(v = 1, ts = 1, items = listOf("%%%", encodedEnvelope(valid)))),
            EventSources.MESH,
        )

        assertTrue(result.accepted)
        assertEquals(listOf("valid"), captured)
    }

    @Test
    fun malformedCanonicalUnknownDedicatedAndWrongItemTypeSiblingsAreIsolated() {
        val malformedActionBase = actionCore("malformed-action")
        val wrongItemTypeBase = actionCore("wrong-item-type")
        val invalidSiblings = listOf(
            malformedActionBase.copy(
                data = JsonObject((malformedActionBase.data as JsonObject) +
                    (MapDomainPayloadContract.ACTION_ID_FIELD to JsonPrimitive("different-id"))),
            ),
            canonicalCore("malformed-canonical").copy(data = buildJsonObject { put("lon", 106.67) }),
            actionCore("unknown-action").copy(type = "MAP.ITEM.FUTURE"),
            wrongItemTypeBase.copy(
                data = JsonObject((wrongItemTypeBase.data as JsonObject) +
                    (MapDomainPayloadContract.ITEM_TYPE_FIELD to JsonPrimitive("point"))),
            ),
        )

        invalidSiblings.forEachIndexed { index, invalid ->
            val valid = canonicalCore("valid-$index")
            val captured = mutableListOf<String>()
            val receiver = receiver {
                captured += it.core.id
                MapEventProcessingResult.Accepted(it.core.id)
            }

            val result = receiver.onEnvelopeReceivedWithMeta(v1Batch(invalid, valid), EventSources.MESH)

            assertTrue("invalid sibling ${invalid.id}", result.accepted)
            assertEquals(listOf(valid.id), captured)
        }
    }

    @Test
    fun semanticallyRejectedSiblingDoesNotSuppressValidMapSibling() {
        assertRejectedSiblingIsolation(
            rejectedResult = { MapEventProcessingResult.Rejected(it, MapEventRejectionReason.OWNER_REQUIRED) },
        )
    }

    @Test
    fun unsupportedVersionSiblingDoesNotSuppressValidMapSibling() {
        val unsupportedBase = actionCore("unsupported")
        val unsupported = unsupportedBase.copy(
            data = JsonObject((unsupportedBase.data as JsonObject) +
                (MapDomainPayloadContract.EXTENSION_VERSION_FIELD to JsonPrimitive(2))),
        )
        val valid = canonicalCore("valid")
        val captured = mutableListOf<String>()
        val receiver = receiver {
            captured += it.core.id
            MapEventProcessingResult.Accepted(it.core.id)
        }

        val result = receiver.onEnvelopeReceivedWithMeta(v1Batch(unsupported, valid), EventSources.MESH)

        assertTrue(result.accepted)
        assertEquals(listOf("valid"), captured)
    }

    @Test
    fun exactDuplicateSiblingDoesNotSuppressNewValidSibling() {
        assertRejectedSiblingIsolation { MapEventProcessingResult.ExactDuplicate(it) }
    }

    @Test
    fun conflictingDuplicateSiblingDoesNotSuppressNewValidSibling() {
        assertRejectedSiblingIsolation { MapEventProcessingResult.ConflictingDuplicate(it) }
    }

    @Test
    fun batchLevelAcceptedResultReflectsAtLeastOneAcceptedSibling() {
        val receiver = receiver {
            if (it.core.id == "rejected") MapEventProcessingResult.ExactDuplicate(it.core.id)
            else MapEventProcessingResult.Accepted(it.core.id)
        }

        val result = receiver.onEnvelopeReceivedWithMeta(
            v1Batch(canonicalCore("rejected"), canonicalCore("accepted")),
            EventSources.MESH,
        )

        assertTrue(result.accepted)
        assertTrue(result.decoded)
        assertFalse(result.opaqueForwardAllowed)
        assertFalse(result.relayEligible)
        assertNotNull(result.relayOriginDeviceIds)
    }

    private fun assertRejectedSiblingIsolation(
        rejectedResult: (String) -> MapEventProcessingResult,
    ) {
        val processed = mutableListOf<String>()
        val receiver = receiver {
            processed += it.core.id
            if (it.core.id == "rejected") rejectedResult(it.core.id)
            else MapEventProcessingResult.Accepted(it.core.id)
        }

        val result = receiver.onEnvelopeReceivedWithMeta(
            v1Batch(canonicalCore("rejected"), canonicalCore("accepted")),
            EventSources.MESH,
        )

        assertTrue(result.accepted)
        assertEquals(listOf("rejected", "accepted"), processed)
    }

    private fun receiver(
        processor: (MapEventProcessingRequest) -> MapEventProcessingResult,
    ): EnvelopeReceiverImpl {
        val db = Mockito.mock(AppDatabase::class.java)
        Mockito.`when`(db.eventsDao()).thenReturn(Mockito.mock(EventsDao::class.java))
        Mockito.`when`(db.eventDedupeDao()).thenReturn(TrackingDedupeDao())
        Mockito.`when`(db.chatDao()).thenReturn(Mockito.mock(ChatDao::class.java))
        Mockito.`when`(db.deletedEventDao()).thenReturn(Mockito.mock(DeletedEventDao::class.java))
        Mockito.`when`(db.groupDao()).thenReturn(Mockito.mock(GroupDao::class.java))
        Mockito.`when`(db.userProfileDao()).thenReturn(Mockito.mock(UserProfileDao::class.java))
        Mockito.`when`(db.removedMemberDao()).thenReturn(Mockito.mock(RemovedMemberDao::class.java))
        Mockito.`when`(db.liveTrailDao()).thenReturn(Mockito.mock(LiveTrailDao::class.java))
        val keyStore = Mockito.mock(SecureKeyStore::class.java)
        Mockito.`when`(keyStore.loadAllGroupKeys(GID)).thenReturn(listOf(GROUP_KEY))
        return EnvelopeReceiverImpl(
            ingestor = EventIngestor(db, mapEventProcessor = processor),
            keyStore = keyStore,
        )
    }

    private fun v1Batch(vararg cores: EventCore): ByteArray = signedBatch(
        AppBatchEnvelope(v = 1, ts = 1_700_000_200L, items = cores.map(::encodedEnvelope)),
    )

    private fun encodedEnvelope(core: EventCore): String {
        val envelope = EnvelopeCrypto.encryptAndSign(
            gid = GID,
            mid = core.id,
            ts = core.time.t,
            ttl = 60,
            core = core,
            groupKey = GROUP_KEY,
        )
        return Base64Url.encode(CanonicalJson.json.encodeToString(envelope).toByteArray())
    }

    private fun v2Batch(core: EventCore): ByteArray {
        val encrypted = EnvelopeCrypto.encryptCore(core, GROUP_KEY)
        return signedBatch(
            AppBatchEnvelope(
                v = 2,
                ts = 1_700_000_200L,
                gid = GID,
                ttl = 60,
                items_v2 = listOf(AppBatchItemV2(mid = core.id, ct = encrypted.ct)),
            ),
        )
    }

    private fun signedBatch(batch: AppBatchEnvelope): ByteArray = CanonicalJson.json.encodeToString(
        AppBatchEnvelope.serializer(),
        AppBatchCrypto.sign(batch),
    ).toByteArray()

    private fun canonicalCore(id: String) = EventCore(
        ev = 1,
        id = id,
        type = EventTypes.MAP_MARKER_ADD,
        actor = Actor("did:canonical-owner"),
        time = EventTime(1_700_000_100L, 31L),
        ref = EventRef(root = "root-1", parent = "parent-1", target = "marker-1"),
        data = buildJsonObject {
            put("marker_id", "marker-1")
            put("lat", 10.75)
            put("lon", 106.67)
            put("future_optional", "preserved")
        },
    )

    private fun actionCore(id: String): EventCore = MapItemEventCodec.encode(
        MapItemType.MARKER,
        MapConfirmation(
            metadata = MapActionMetadata(
                actionId = id,
                itemId = "marker-1",
                actorDid = "did:actor",
                timestampSeconds = 1_700_000_123L,
                eventLamport = 41L,
            ),
            source = MapInformationSource.FIRSTHAND,
            note = "water is rising",
        ),
    )

    private class TrackingDedupeDao : EventDedupeDao {
        override fun upsert(entity: EventDedupeEntity) = Unit
        override fun countActive(gid: String, eventId: String, nowSeconds: Long) = 0
        override fun deleteExpired(nowSeconds: Long) = 0
        override fun deleteByGid(gid: String) = 0
    }

    private companion object {
        const val GID = "g"
        val GROUP_KEY = ByteArray(32) { (it + 1).toByte() }
    }
}
