package app.peermesh.data.map.recovery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapActionMetadata
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.PersistenceWriteResult
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.map.materialization.RoomMapItemActionProjectionDeriver
import app.peermesh.data.map.processing.DefaultCanonicalMapEventPolicy
import app.peermesh.data.map.processing.DefaultDedicatedMapActionPolicy
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.RoomMapEventProcessor
import app.peermesh.data.map.processing.Sha256MapEventFingerprint
import app.peermesh.data.map.processing.DefaultMapItemActionEntityFactory
import app.peermesh.data.map.processing.MapItemActionPersistenceInput
import app.peermesh.data.map.processing.MapItemActionInitialState
import app.peermesh.data.map.processing.MapEventEntityFactory
import app.peermesh.data.map.replay.DefaultPendingActionSemanticEvaluator
import app.peermesh.data.map.replay.PendingReplayTarget
import app.peermesh.data.map.replay.RoomTargetPendingReplayCoordinator
import app.peermesh.data.map.replay.RoomTargetProjectionRefresher
import app.peermesh.data.reducer.MapEventReducerDispatcher
import app.peermesh.data.reducer.RoomCanonicalMapEventReducer
import app.peermesh.data.reducer.RoomDedicatedMapActionReducer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBoundedPendingRecoveryInstrumentationTest {
    private lateinit var db: AppDatabase
    private lateinit var processor: RoomMapEventProcessor

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        db.userProfileDao().upsert(UserProfileEntity("g", "owner", "Owner", null, 1, "owner", 1, 0, "owner"))
        db.userProfileDao().upsert(UserProfileEntity("g", "peer", "Peer", null, 1, "peer", 1, 0, "peer"))
        processor = makeProcessor(db)
    }

    @After fun tearDown() = db.close()

    @Test fun realDaoKeysetPagesAreBoundedIsolatedAndComplete() {
        listOf(
            row("e1", "marker", "one", 10, 1, "a", "pending_target"),
            row("e2", "marker", "two", 10, 1, "a", "pending_target"),
            row("e3", "marker", "three", 10, 1, "b", "pending_target"),
            row("e4", "marker", "four", 10, 2, "a", "pending_target"),
            row("applied", "marker", "five", 11, 0, "a", "applied"),
            row("rejected", "marker", "six", 12, 0, "a", "rejected", "invalid_action"),
            row("other-group", "marker", "seven", 1, 0, "a", "pending_target", gid = "other"),
        ).forEach { assertTrue(db.mapItemActionDao().insertIgnore(it) is PersistenceWriteResult.Success) }
        val first = db.mapItemActionDao().scanPending("g", null, null, null, null, 2)
        val second = db.mapItemActionDao().scanPending("g", first.last().action_t, first.last().action_lt,
            first.last().actor_did, first.last().event_id, 2)
        val all = first + second
        assertEquals(listOf("e1", "e2", "e3", "e4"), all.map { it.event_id })
        assertEquals(all.size, all.map { it.event_id }.toSet().size)
        assertEquals("e4", second.last().event_id)
    }

    @Test fun missingTargetStaysPendingAcrossRepeatedRecovery() {
        persistPending("missing", "missing-action", 10)
        val service = service()
        val request = BoundedPendingRecoveryRequest("g", 10)
        val first = service.recover(request) as BoundedPendingRecoveryResult.Completed
        assertEquals(1, first.skippedMissingTargetCount)
        val row = db.mapItemActionDao().findByActionId("g", "missing-action")!!
        assertEquals("pending_target", row.materialization_state)
        assertNull(row.rejection_reason)
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "missing"))
        val second = service.recover(request) as BoundedPendingRecoveryResult.Completed
        assertEquals(1, second.skippedMissingTargetCount)
        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "missing-action")!!.materialization_state)
    }

    @Test fun realTargetTransactionsIsolateRollbackAndPreserveFinalCursor() {
        listOf("a", "b", "c").forEach { id ->
            assertTrue(processor.process(MapEventProcessingRequest("g", add(id), "test", 1)) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
        }
        persistPending("a", "a-action", 10)
        persistPending("b", "b-action-1", 10)
        persistPending("b", "b-action-2", 11)
        persistPending("c", "c-action", 12)
        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET action_note = '' WHERE action_id = 'b-action-2'")

        val result = service().recover(BoundedPendingRecoveryRequest("g", 20)) as BoundedPendingRecoveryResult.Completed
        assertEquals(2, result.completedTargetCount)
        assertEquals(1, result.failedTargetCount)
        assertEquals("c-action", result.nextCursor!!.order.eventId)
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "a-action")!!.materialization_state)
        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "b-action-1")!!.materialization_state)
        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "b-action-2")!!.materialization_state)
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "c-action")!!.materialization_state)
        assertEquals(1, db.mapItemDomainStateDao().find("g", "marker", "a")!!.confirmation_actor_count)
        assertEquals(0, db.mapItemDomainStateDao().find("g", "marker", "b")!!.confirmation_actor_count)
        assertEquals(1, db.mapItemDomainStateDao().find("g", "marker", "c")!!.confirmation_actor_count)
    }

    @Test fun recoveryRerunIsTerminalStateAndProjectionIdempotent() {
        assertTrue(processor.process(MapEventProcessingRequest("g", add("rerun"), "test", 1)) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
        persistPending("rerun", "rerun-action", 10)
        val service = service()
        val request = BoundedPendingRecoveryRequest("g", 10)
        val first = service.recover(request) as BoundedPendingRecoveryResult.Completed
        val state = db.mapItemDomainStateDao().find("g", "marker", "rerun")!!
        val second = service.recover(request) as BoundedPendingRecoveryResult.Completed
        assertEquals(1, first.completedTargetCount)
        assertEquals(0, second.scannedRowCount)
        assertEquals(state, db.mapItemDomainStateDao().find("g", "marker", "rerun"))
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "rerun-action")!!.materialization_state)
    }

    @Test fun targetTriggeredAndBoundedRecoveryProduceEquivalentProjection() {
        val triggeredDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            listOf("owner", "peer").forEach { did -> triggeredDb.userProfileDao().upsert(UserProfileEntity("g", did, did, null, 1, did, 1, 0, did)) }
            val triggeredProcessor = makeProcessor(triggeredDb)
            val pending = EventFactory.mapConfirmation(MapItemType.MARKER,
                MapConfirmation(MapActionMetadata("eq-action", "eq", "peer", 10, 0), MapInformationSource.UNCERTAIN, "note"))
            assertTrue(triggeredProcessor.process(MapEventProcessingRequest("g", pending, "test", 10)) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            assertTrue(triggeredProcessor.process(MapEventProcessingRequest("g", add("eq"), "test", 1)) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)

            assertTrue(processor.process(MapEventProcessingRequest("g", add("eq"), "test", 1)) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            persistPending("eq", "eq-action", 10)
            assertEquals(1, (service().recover(BoundedPendingRecoveryRequest("g", 10)) as BoundedPendingRecoveryResult.Completed).completedTargetCount)
            val triggeredAction = triggeredDb.mapItemActionDao().findByActionId("g", "eq-action")!!
            val boundedAction = db.mapItemActionDao().findByActionId("g", "eq-action")!!
            assertEquals(triggeredAction.materialization_state, boundedAction.materialization_state)
            assertEquals(triggeredAction.rejection_reason, boundedAction.rejection_reason)
            assertEquals(triggeredDb.mapItemDomainStateDao().find("g", "marker", "eq"), db.mapItemDomainStateDao().find("g", "marker", "eq"))
        } finally {
            triggeredDb.close()
        }
    }

    private fun service(): RoomBoundedPendingRecoveryService {
        val coordinator = RoomTargetPendingReplayCoordinator(db.mapItemActionDao(), db.eventsDao(), db.mapItemDomainStateDao(), db.mapDao(),
            DefaultPendingActionSemanticEvaluator,
            RoomTargetProjectionRefresher(RoomMapItemActionProjectionDeriver(db.mapItemActionDao()), db.mapItemDomainStateDao()))
        return RoomBoundedPendingRecoveryService(db, coordinator)
    }

    private fun makeProcessor(database: AppDatabase) = RoomMapEventProcessor(database, DefaultCanonicalMapEventPolicy(), MapEventReducerDispatcher(
        RoomCanonicalMapEventReducer(database.mapDao(), database.mapItemVoteDao()),
        RoomDedicatedMapActionReducer(database.mapItemActionDao(), DefaultDedicatedMapActionPolicy,
            Sha256MapEventFingerprint, DefaultMapItemActionEntityFactory,
            RoomMapItemActionProjectionDeriver(database.mapItemActionDao()), database.mapItemDomainStateDao()),
    ))

    private fun persistPending(item: String, id: String, time: Long) {
        val core = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata(id, item, "peer", time, 0), MapInformationSource.UNCERTAIN, "note"))
        db.eventsDao().insert(MapEventEntityFactory.create(MapEventProcessingRequest("g", core, "test", core.time.t)))
        val decoded = app.peermesh.core.map.MapItemEventCodec.decode(core)!!
        val entity = (DefaultMapItemActionEntityFactory.create(MapItemActionPersistenceInput("g", core, decoded),
            Sha256MapEventFingerprint.sha256(core), MapItemActionInitialState.PENDING_TARGET) as PersistenceParseResult.Success).value
        assertTrue(db.mapItemActionDao().insertIgnore(entity) is PersistenceWriteResult.Success)
    }

    private fun add(id: String) = EventFactory.mapMarkerAdd("add-$id", "owner", 1, 0, id, "l", 1.0, 2.0,
        null, null, null, "risk", domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))

    private fun row(event: String, type: String, item: String, t: Long, lt: Long, actor: String, state: String,
                    reason: String? = null, gid: String = "g") = MapItemActionEntity(
        gid, event, event, "MAP.ITEM.CONFIRM", 1, type, item, "confirm", actor, t, lt,
        null, "note", null, null, "sha-$event", state, reason,
    )
}
