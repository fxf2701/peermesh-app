package app.peermesh.data.relay

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.MapActionMetadata
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.map.MapCoordinationAcknowledgement
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.map.materialization.RoomMapItemActionProjectionDeriver
import app.peermesh.data.map.processing.DefaultCanonicalMapEventPolicy
import app.peermesh.data.map.processing.DefaultDedicatedMapActionPolicy
import app.peermesh.data.map.processing.DefaultMapItemActionEntityFactory
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.data.map.processing.RoomMapEventProcessor
import app.peermesh.data.map.processing.Sha256MapEventFingerprint
import app.peermesh.data.reducer.MapEventReducerDispatcher
import app.peermesh.data.reducer.RoomCanonicalMapEventReducer
import app.peermesh.data.reducer.RoomDedicatedMapActionReducer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
/**
 * M2D2 transport-attribution coverage over the accepted M2C Room processor fixture.
 *
 * These tests do not re-prove reducer, fingerprint, ordering, or replay semantics; those are
 * established by M2C. They prove that changing delivery source and retry metadata reaches that
 * same authority without changing its durable result.
 */
class MapMixedTransportDeduplicationM2D2InstrumentationTest {
    private lateinit var db: AppDatabase
    private lateinit var processor: RoomMapEventProcessor

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        listOf("owner", "peer").forEach { actor ->
            db.userProfileDao().upsert(UserProfileEntity(GID, actor, actor, null, 1, "profile-$actor", 1, 0, actor))
        }
        val actionDao = db.mapItemActionDao()
        val domainDao = db.mapItemDomainStateDao()
        val projectionDeriver = RoomMapItemActionProjectionDeriver(actionDao)
        processor = RoomMapEventProcessor(
            db,
            DefaultCanonicalMapEventPolicy(),
            MapEventReducerDispatcher(
                RoomCanonicalMapEventReducer(db.mapDao(), db.mapItemVoteDao()),
                RoomDedicatedMapActionReducer(
                    actionDao,
                    DefaultDedicatedMapActionPolicy,
                    Sha256MapEventFingerprint,
                    DefaultMapItemActionEntityFactory,
                    projectionDeriver,
                    domainDao,
                ),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun exactDuplicateMatrixAcrossLocalMeshAndRelayIsIdempotent() {
        sourcePairs.forEachIndexed { index, (firstSource, retrySource) ->
            val item = "exact-$index"
            val add = markerAdd("add-$index", item)
            assertTrue(process(add, firstSource) is MapEventProcessingResult.Accepted)
            val markerBefore = db.mapDao().findMarker(GID, item)
            val stateBefore = db.mapItemDomainStateDao().find(GID, "marker", item)

            assertTrue(process(add, retrySource, insertedAt = 9_000 + index.toLong()) is MapEventProcessingResult.ExactDuplicate)
            assertEquals(markerBefore, db.mapDao().findMarker(GID, item))
            assertEquals(stateBefore, db.mapItemDomainStateDao().find(GID, "marker", item))
            assertEquals(index + 1, db.eventsDao().countByType(GID, EventTypes.MAP_MARKER_ADD))

            val action = confirmation("confirm-$index", item)
            assertTrue(process(action, firstSource) is MapEventProcessingResult.Accepted)
            val actionBefore = db.mapItemActionDao().findByActionId(GID, action.id)
            val actionStateBefore = db.mapItemDomainStateDao().find(GID, "marker", item)
            assertTrue(process(action, retrySource, insertedAt = 10_000 + index.toLong()) is MapEventProcessingResult.ExactDuplicate)
            assertEquals(actionBefore, db.mapItemActionDao().findByActionId(GID, action.id))
            assertEquals(actionStateBefore, db.mapItemDomainStateDao().find(GID, "marker", item))
            assertEquals(index + 1, db.eventsDao().countByType(GID, EventTypes.MAP_ITEM_CONFIRM))
        }
    }

    @Test
    fun conflictingDuplicateMatrixAcrossLocalMeshAndRelayIsNonDestructive() {
        sourcePairs.forEachIndexed { index, (firstSource, conflictSource) ->
            val item = "conflict-$index"
            val add = markerAdd("conflict-add-$index", item)
            assertTrue(process(add, firstSource) is MapEventProcessingResult.Accepted)
            val persistedBefore = db.eventsDao().findById(GID, add.id)
            val markerBefore = db.mapDao().findMarker(GID, item)
            val stateBefore = db.mapItemDomainStateDao().find(GID, "marker", item)

            assertTrue(process(conflicting(add), conflictSource) is MapEventProcessingResult.ConflictingDuplicate)
            assertEquals(persistedBefore, db.eventsDao().findById(GID, add.id))
            assertEquals(markerBefore, db.mapDao().findMarker(GID, item))
            assertEquals(stateBefore, db.mapItemDomainStateDao().find(GID, "marker", item))

            val action = confirmation("conflict-confirm-$index", item)
            assertTrue(process(action, firstSource) is MapEventProcessingResult.Accepted)
            val actionBefore = db.mapItemActionDao().findByActionId(GID, action.id)
            val actionStateBefore = db.mapItemDomainStateDao().find(GID, "marker", item)
            assertTrue(process(conflicting(action), conflictSource) is MapEventProcessingResult.ConflictingDuplicate)
            assertEquals(actionBefore, db.mapItemActionDao().findByActionId(GID, action.id))
            assertEquals(actionStateBefore, db.mapItemDomainStateDao().find(GID, "marker", item))
        }
    }

    @Test
    fun relayEchoDoesNotChangeActionHistoryOrProjection() {
        val add = markerAdd("echo-add", "echo-item")
        val confirmation = confirmation("echo-confirm", "echo-item")
        assertTrue(process(add, LOCAL) is MapEventProcessingResult.Accepted)
        assertTrue(process(confirmation, MESH) is MapEventProcessingResult.Accepted)
        val marker = db.mapDao().findMarker(GID, "echo-item")
        val action = db.mapItemActionDao().findByActionId(GID, confirmation.id)
        val state = db.mapItemDomainStateDao().find(GID, "marker", "echo-item")

        listOf(RELAY, MESH, RELAY).forEach { source ->
            assertTrue(process(confirmation, source) is MapEventProcessingResult.ExactDuplicate)
        }

        assertEquals(marker, db.mapDao().findMarker(GID, "echo-item"))
        assertEquals(action, db.mapItemActionDao().findByActionId(GID, confirmation.id))
        assertEquals(state, db.mapItemDomainStateDao().find(GID, "marker", "echo-item"))
        assertEquals(1, db.mapItemActionDao().countConfirmationActors(GID, "marker", "echo-item"))
    }

    @Test
    fun mixedTransportRetryDoesNotChangePendingAppliedOrRejectedTerminalState() {
        val pending = confirmation("pending", "pending-item")
        assertTrue(process(pending, RELAY) is MapEventProcessingResult.Accepted)
        val pendingBefore = db.mapItemActionDao().findByActionId(GID, pending.id)
        assertEquals("pending_target", pendingBefore?.materialization_state)
        assertTrue(process(pending, RELAY) is MapEventProcessingResult.ExactDuplicate)
        assertEquals(pendingBefore, db.mapItemActionDao().findByActionId(GID, pending.id))

        assertTrue(process(markerAdd("pending-add", "pending-item"), LOCAL) is MapEventProcessingResult.Accepted)
        val applied = db.mapItemActionDao().findByActionId(GID, pending.id)
        assertEquals("applied", applied?.materialization_state)
        assertTrue(process(pending, MESH) is MapEventProcessingResult.ExactDuplicate)
        assertEquals(applied, db.mapItemActionDao().findByActionId(GID, pending.id))

        val rejectedPending = acknowledgement("rejected", "rejected-item")
        assertTrue(process(rejectedPending, RELAY) is MapEventProcessingResult.Accepted)
        assertEquals("pending_target", db.mapItemActionDao().findByActionId(GID, rejectedPending.id)?.materialization_state)
        assertTrue(process(markerAdd("rejected-add", "rejected-item"), LOCAL) is MapEventProcessingResult.Accepted)
        val rejected = db.mapItemActionDao().findByActionId(GID, rejectedPending.id)
        assertEquals("rejected", rejected?.materialization_state)
        assertEquals("action_not_allowed_for_category", rejected?.rejection_reason)
        assertTrue(process(rejectedPending, MESH) is MapEventProcessingResult.ExactDuplicate)
        assertEquals(rejected, db.mapItemActionDao().findByActionId(GID, rejectedPending.id))
    }

    @Test
    fun transportMetadataDoesNotAffectSemanticConvergence() {
        val add = markerAdd("metadata-add", "metadata-item")
        assertTrue(process(add, LOCAL, insertedAt = 50_000) is MapEventProcessingResult.Accepted)
        val persisted = db.eventsDao().findById(GID, add.id)
        val marker = db.mapDao().findMarker(GID, "metadata-item")
        val state = db.mapItemDomainStateDao().find(GID, "marker", "metadata-item")

        listOf(
            Triple(MESH, 1L, "queue-first"),
            Triple(RELAY, 99_999L, "cursor-opaque"),
            Triple(RELAY, 2L, "retry-7"),
        ).forEach { (source, insertedAt, ignoredTransportMetadata) ->
            assertTrue(ignoredTransportMetadata.isNotBlank())
            assertTrue(process(add, source, insertedAt) is MapEventProcessingResult.ExactDuplicate)
        }

        assertEquals(persisted, db.eventsDao().findById(GID, add.id))
        assertEquals(marker, db.mapDao().findMarker(GID, "metadata-item"))
        assertEquals(state, db.mapItemDomainStateDao().find(GID, "marker", "metadata-item"))
    }

    @Test
    fun roomSchemaRemainsVersion52() {
        assertEquals(54, db.openHelper.readableDatabase.version)
    }

    private fun process(core: EventCore, source: String, insertedAt: Long = core.time.t): MapEventProcessingResult =
        processor.process(
            MapEventProcessingRequest(
                gid = GID,
                core = core,
                source = source,
                insertedAtSeconds = insertedAt,
                localActorDid = if (source == LOCAL) core.actor.did else null,
            ),
        )

    private fun markerAdd(id: String, item: String): EventCore = EventFactory.mapMarkerAdd(
        id, "owner", 100, 1, item, "layer", 10.0, 106.0, 20.0,
        "Flood", "Deep water", "risk", ttlSeconds = 10_000,
        domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND),
    )

    private fun confirmation(id: String, item: String): EventCore = EventFactory.mapConfirmation(
        MapItemType.MARKER,
        MapConfirmation(MapActionMetadata(id, item, "peer", 110, 2), MapInformationSource.FIRSTHAND, "Seen"),
    )

    private fun acknowledgement(id: String, item: String): EventCore = EventFactory.mapAcknowledgement(
        MapItemType.MARKER,
        MapCoordinationAcknowledgement(MapActionMetadata(id, item, "peer", 111, 3)),
    )

    private fun conflicting(core: EventCore): EventCore = core.copy(data = buildJsonObject {
        (core.data as? JsonObject)?.forEach { (key, value) -> put(key, value) }
        put("m2d2_conflict", true)
    })

    private companion object {
        const val GID = "g"
        const val LOCAL = "local"
        const val MESH = "mesh"
        const val RELAY = "relay"
        val sourcePairs = listOf(
            LOCAL to MESH,
            MESH to LOCAL,
            LOCAL to RELAY,
            RELAY to LOCAL,
            MESH to RELAY,
            RELAY to MESH,
            RELAY to RELAY,
        )
    }
}
