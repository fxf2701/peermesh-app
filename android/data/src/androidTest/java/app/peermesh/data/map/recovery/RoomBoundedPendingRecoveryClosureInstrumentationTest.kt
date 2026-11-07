package app.peermesh.data.map.recovery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.*
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.PersistenceWriteResult
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.map.materialization.RoomMapItemActionProjectionDeriver
import app.peermesh.data.map.processing.*
import app.peermesh.data.map.replay.*
import app.peermesh.data.reducer.*
import kotlinx.serialization.json.buildJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBoundedPendingRecoveryClosureInstrumentationTest {
    private lateinit var db: AppDatabase
    @Before fun setUp() { db = fixture(); addMarker(db, "owner", "target") }
    @After fun tearDown() = db.close()

    @Test fun staleDiscoveryAfterOtherReplayIsCompletedWithoutFailure() {
        addMarker(db, "owner", "stale")
        persist(db, confirm("stale-action", "stale", 10))
        val coordinator = coordinator(db)
        var interleaved = false
        var firstProjection: ProjectionSnapshot? = null
        val service = RoomBoundedPendingRecoveryService(db, coordinator) { block -> db.runInTransaction<PendingRecoveryTargetOutcome> {
            if (!interleaved) {
                interleaved = true
                coordinator.replay(PendingReplayTarget("g", MapItemType.MARKER, "stale"))
                firstProjection = snapshot(db, "stale")
            }
            block()
        } }
        val result = service.recover(BoundedPendingRecoveryRequest("g", 10)) as BoundedPendingRecoveryResult.Completed
        assertEquals(1, result.completedTargetCount)
        assertEquals(0, result.failedTargetCount)
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "stale-action")!!.materialization_state)
        assertNull(db.mapItemActionDao().findByActionId("g", "stale-action")!!.rejection_reason)
        assertEquals(firstProjection, snapshot(db, "stale"))
        assertEquals(1, snapshot(db, "stale").confirmationActorCount)
        val rerun = service.recover(BoundedPendingRecoveryRequest("g", 10)) as BoundedPendingRecoveryResult.Completed
        assertEquals(0, rerun.scannedRowCount)
        assertEquals(firstProjection, snapshot(db, "stale"))
    }

    @Test fun oppositePendingArrivalOrdersConvergeOnPersistedRoomState() {
        val left = fixture(); val right = fixture()
        try {
            addMarker(left, "owner", "order"); addMarker(right, "owner", "order")
            val actions = lifecycleActions("order")
            actions.forEach { persist(left, it) }; actions.asReversed().forEach { persist(right, it) }
            recover(left); recover(right)
            val ls = terminalDetails(left, actions)
            val rs = terminalDetails(right, actions)
            assertEquals(ls, rs)
            assertEquals(snapshot(left, "order"), snapshot(right, "order"))
            assertEquals("no_longer_active", snapshot(left, "order").lifecycle)
            assertEquals("life-resolve-repeat", snapshot(left, "order").latestLifecycle?.eventId)
            assertEquals("life-resolve-repeat", snapshot(left, "order").latestMaterialChange?.eventId)
        } finally { left.close(); right.close() }
    }

    @Test fun oppositeCoordinationArrivalOrdersConvergeOnAcknowledgementProjection() {
        val left = fixture(); val right = fixture()
        try {
            addCoordinationMarker(left, "coord"); addCoordinationMarker(right, "coord")
            val actions = coordinationActions("coord")
            actions.forEach { persist(left, it) }; actions.asReversed().forEach { persist(right, it) }
            recover(left); recover(right)
            assertEquals(terminalDetails(left, actions), terminalDetails(right, actions))
            val leftSnapshot = snapshot(left, "coord")
            assertEquals(leftSnapshot, snapshot(right, "coord"))
            assertEquals(2, leftSnapshot.acknowledgementActorCount)
            assertEquals(0, leftSnapshot.confirmationActorCount)
            assertNull(leftSnapshot.lifecycle)
            assertNull(leftSnapshot.latestConfirmation)
            assertNull(leftSnapshot.latestSituationUpdate)
            assertNull(leftSnapshot.latestLifecycle)
            assertNull(leftSnapshot.latestMaterialChange)
            actions.forEach { assertEquals("applied", left.mapItemActionDao().findByActionId("g", it.id)!!.materialization_state) }
        } finally { left.close(); right.close() }
    }

    @Test fun lifecycleRichTargetTriggeredAndBoundedRecoveryConverge() {
        val triggered = fixture(); val bounded = fixture()
        try {
            val actions = lifecycleActions("life")
            val triggeredProcessor = processor(triggered)
            actions.forEach { assertTrue(triggeredProcessor.process(MapEventProcessingRequest("g", it, "test", it.time.t)) is MapEventProcessingResult.Accepted) }
            assertTrue(triggeredProcessor.process(MapEventProcessingRequest("g", markerAdd("life"), "test", 1)) is MapEventProcessingResult.Accepted)
            addMarker(bounded, "owner", "life")
            actions.forEach { persist(bounded, it) }
            assertEquals(1, recover(bounded).completedTargetCount)
            actions.forEach { id ->
                val left = triggered.mapItemActionDao().findByActionId("g", id.id)!!
                val right = bounded.mapItemActionDao().findByActionId("g", id.id)!!
                assertEquals(left.materialization_state, right.materialization_state)
                assertEquals(left.rejection_reason, right.rejection_reason)
            }
            val triggeredSnapshot = snapshot(triggered, "life")
            val boundedSnapshot = snapshot(bounded, "life")
            assertEquals(triggeredSnapshot, boundedSnapshot)
            assertEquals("no_longer_active", boundedSnapshot.lifecycle)
            assertEquals("life-confirm-b", boundedSnapshot.latestConfirmation?.eventId)
            assertEquals("life-change", boundedSnapshot.latestSituationUpdate?.eventId)
            assertEquals("life-resolve-repeat", boundedSnapshot.latestLifecycle?.eventId)
            assertEquals("life-resolve-repeat", boundedSnapshot.latestMaterialChange?.eventId)
            assertEquals(0, boundedSnapshot.confirmationActorCount)
            assertEquals(0, boundedSnapshot.acknowledgementActorCount)
            assertEquals("applied", bounded.mapItemActionDao().findByActionId("g", "life-historical")!!.materialization_state)
            assertEquals("applied", bounded.mapItemActionDao().findByActionId("g", "life-resolve")!!.materialization_state)
            assertEquals("applied", bounded.mapItemActionDao().findByActionId("g", "life-resolve-repeat")!!.materialization_state)
            assertEquals("rejected", bounded.mapItemActionDao().findByActionId("g", "life-post")!!.materialization_state)
            assertEquals("target_resolved", bounded.mapItemActionDao().findByActionId("g", "life-post")!!.rejection_reason)
            assertLifecycleFixtureTerminalHistory(triggered, actions)
            assertLifecycleFixtureTerminalHistory(bounded, actions)
        } finally { triggered.close(); bounded.close() }
    }

    @Test fun orderedLiveProcessingAndBoundedRecoveryHaveSameSemanticOutcomes() {
        val live = fixture(); val bounded = fixture()
        try {
            val actions = lifecycleActions("ordered")
            addMarker(live, "owner", "ordered")
            val liveResults = actions.sortedWith(eventCoreActionOrderComparator).associate { action ->
                action.id to processor(live).process(MapEventProcessingRequest("g", action, "test", action.time.t))
            }
            val liveOutcomes = liveResults.mapValues { semanticOutcome(it.value) }
            addMarker(bounded, "owner", "ordered")
            actions.forEach { persist(bounded, it) }
            recover(bounded)
            assertEquals(liveOutcomes, terminalOutcomes(bounded, actions))
            assertEquals(snapshot(live, "ordered"), snapshot(bounded, "ordered"))
            assertEquals("rejected", terminalOutcomes(bounded, actions).getValue("life-post"))
            val postResult = liveResults.getValue("life-post") as MapEventProcessingResult.Rejected
            assertEquals(MapEventRejectionReason.TARGET_RESOLVED, postResult.reason)
        } finally { live.close(); bounded.close() }
    }

    @Test fun actionBeforeTargetAndTargetBeforeActionConvergeSemantically() {
        val beforeTarget = fixture(); val targetBefore = fixture()
        try {
            val actions = lifecycleActions("arrival")
            val pendingProcessor = processor(beforeTarget)
            actions.forEach { assertTrue(pendingProcessor.process(MapEventProcessingRequest("g", it, "test", it.time.t)) is MapEventProcessingResult.Accepted) }
            assertTrue(pendingProcessor.process(MapEventProcessingRequest("g", markerAdd("arrival"), "test", 1)) is MapEventProcessingResult.Accepted)
            addMarker(targetBefore, "owner", "arrival")
            val liveOutcomes = actions.sortedWith(eventCoreActionOrderComparator).associate { action ->
                action.id to semanticOutcome(processor(targetBefore).process(MapEventProcessingRequest("g", action, "test", action.time.t)))
            }
            assertEquals(liveOutcomes, terminalOutcomes(beforeTarget, actions))
            assertEquals(snapshot(targetBefore, "arrival"), snapshot(beforeTarget, "arrival"))
        } finally { beforeTarget.close(); targetBefore.close() }
    }

    @Test fun areaActionBeforeTargetAndTargetBeforeActionConvergeSemantically() {
        val beforeTarget = fixture(); val targetBefore = fixture()
        try {
            val action = confirm(MapItemType.AREA, "area-arrival-confirm", "area-arrival", 10)
            assertTrue(processor(beforeTarget).process(MapEventProcessingRequest("g", action, "test", 10)) is MapEventProcessingResult.Accepted)
            assertTrue(processor(beforeTarget).process(MapEventProcessingRequest("g", areaAdd("area-arrival"), "test", 1)) is MapEventProcessingResult.Accepted)
            assertTrue(processor(targetBefore).process(MapEventProcessingRequest("g", areaAdd("area-arrival"), "test", 1)) is MapEventProcessingResult.Accepted)
            assertEquals("applied", semanticOutcome(processor(targetBefore).process(MapEventProcessingRequest("g", action, "test", 10))))
            assertEquals("applied", beforeTarget.mapItemActionDao().findByActionId("g", action.id)!!.materialization_state)
            assertEquals(
                beforeTarget.mapItemDomainStateDao().find("g", "area", "area-arrival"),
                targetBefore.mapItemDomainStateDao().find("g", "area", "area-arrival"),
            )
        } finally { beforeTarget.close(); targetBefore.close() }
    }

    @Test fun markerAndAreaPendingTargetsRemainRoomIsolated() {
        val area = EventFactory.mapAreaAdd("area-add", "owner", 1, 0, "same", "l", buildJsonArray {}, null, null, "risk",
            domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
        assertTrue(processor(db).process(MapEventProcessingRequest("g", area, "test", 1)) is MapEventProcessingResult.Accepted)
        persist(db, confirm("marker-action", "target", 10)); persist(db, confirm(MapItemType.AREA, "area-action", "same", 11))
        val result = recover(db)
        assertEquals(2, result.completedTargetCount)
        assertEquals(1, db.mapItemDomainStateDao().find("g", "marker", "target")!!.confirmation_actor_count)
        assertEquals(1, db.mapItemDomainStateDao().find("g", "area", "same")!!.confirmation_actor_count)
    }

    @Test fun oldOrRepeatedOrphanScansNeverCreatePolicyOrPlaceholderState() {
        persist(db, confirm("orphan-action", "orphan", 1))
        val before = db.mapItemActionDao().findByActionId("g", "orphan-action")!!
        val eventBefore = db.eventsDao().findById("g", "orphan-action")!!
        repeat(3) { recover(db) }
        val row = db.mapItemActionDao().findByActionId("g", "orphan-action")!!
        assertEquals("pending_target", row.materialization_state)
        assertNull(row.rejection_reason)
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "orphan"))
        assertEquals(before, row)
        assertEquals(eventBefore, db.eventsDao().findById("g", "orphan-action"))
        assertEquals(1, db.mapItemActionDao().findPendingTarget("g", "marker", "orphan").size)
    }

    private fun recover(database: AppDatabase) = RoomBoundedPendingRecoveryService(database, coordinator(database)).recover(BoundedPendingRecoveryRequest("g", 20)) as BoundedPendingRecoveryResult.Completed
    private fun coordinator(database: AppDatabase) = RoomTargetPendingReplayCoordinator(database.mapItemActionDao(), database.eventsDao(), database.mapItemDomainStateDao(), database.mapDao(), DefaultPendingActionSemanticEvaluator, RoomTargetProjectionRefresher(RoomMapItemActionProjectionDeriver(database.mapItemActionDao()), database.mapItemDomainStateDao()))
    private fun snapshot(database: AppDatabase, item: String): ProjectionSnapshot {
        val state = database.mapItemDomainStateDao().find("g", "marker", item)!!
        fun order(t: Long?, lt: Long?, actor: String?, event: String?): MapActionOrderKey? {
            if (t == null && lt == null && actor == null && event == null) return null
            assertTrue("persisted pointer tuple must be complete", t != null && lt != null && actor != null && event != null)
            return MapActionOrderKey(t!!, lt!!, actor!!, event!!)
        }
        return ProjectionSnapshot(state.situation_lifecycle,
            order(state.latest_confirmation_t, state.latest_confirmation_lt, state.latest_confirmation_actor, state.latest_confirmation_event_id),
            order(state.latest_update_t, state.latest_update_lt, state.latest_update_actor, state.latest_update_event_id),
            order(state.latest_lifecycle_t, state.latest_lifecycle_lt, state.latest_lifecycle_actor, state.latest_lifecycle_event_id),
            order(state.latest_material_t, state.latest_material_lt, state.latest_material_actor, state.latest_material_event_id),
            state.confirmation_actor_count, state.acknowledgement_actor_count)
    }
    private fun assertLifecycleFixtureTerminalHistory(database: AppDatabase, actions: List<app.peermesh.core.model.EventCore>) {
        val rows = actions.map { database.mapItemActionDao().findByActionId("g", it.id)!! }
        val pendingCount = rows.count { it.materialization_state == "pending_target" }
        val appliedCount = rows.count { it.materialization_state == "applied" }
        val rejectedCount = rows.count { it.materialization_state == "rejected" }
        val historyRowCount = rows.size
        assertEquals(0, pendingCount)
        assertEquals(7, appliedCount)
        assertEquals(1, rejectedCount)
        assertEquals(actions.size, historyRowCount)
    }
    private fun terminalOutcomes(database: AppDatabase, actions: List<app.peermesh.core.model.EventCore>) = actions.associate { action ->
        action.id to database.mapItemActionDao().findByActionId("g", action.id)!!.materialization_state
    }
    private fun terminalDetails(database: AppDatabase, actions: List<app.peermesh.core.model.EventCore>) = actions.associate { action ->
        val row = database.mapItemActionDao().findByActionId("g", action.id)!!
        action.id to (row.materialization_state to row.rejection_reason)
    }
    private fun semanticOutcome(result: MapEventProcessingResult) = when (result) {
        is MapEventProcessingResult.Accepted, is MapEventProcessingResult.AcceptedNoChange -> "applied"
        is MapEventProcessingResult.Rejected -> "rejected"
        else -> error("unexpected live processing result: $result")
    }
    private fun fixture(): AppDatabase { val d = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build(); listOf("owner", "peer", "peer2").forEach { d.userProfileDao().upsert(UserProfileEntity("g", it, it, null, 1, it, 1, 0, it)) }; return d }
    private fun processor(database: AppDatabase) = RoomMapEventProcessor(database, DefaultCanonicalMapEventPolicy(), MapEventReducerDispatcher(RoomCanonicalMapEventReducer(database.mapDao(), database.mapItemVoteDao()), RoomDedicatedMapActionReducer(database.mapItemActionDao(), DefaultDedicatedMapActionPolicy, Sha256MapEventFingerprint, DefaultMapItemActionEntityFactory, RoomMapItemActionProjectionDeriver(database.mapItemActionDao()), database.mapItemDomainStateDao())))
    private fun addMarker(database: AppDatabase, owner: String, id: String) { assertTrue(processor(database).process(MapEventProcessingRequest("g", EventFactory.mapMarkerAdd("add-$id", owner, 1, 0, id, "l", 1.0, 2.0, null, null, null, "risk", domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND)), "test", 1)) is MapEventProcessingResult.Accepted) }
    private fun addCoordinationMarker(database: AppDatabase, id: String) { assertTrue(processor(database).process(MapEventProcessingRequest("g", EventFactory.mapMarkerAdd("add-$id", "owner", 1, 0, id, "l", 1.0, 2.0, null, null, null, "camp", domainExtension = MapCanonicalAddExtension(MapItemCategory.COORDINATION, null)), "test", 1)) is MapEventProcessingResult.Accepted) }
    private fun confirm(id: String, item: String, time: Long) = confirm(MapItemType.MARKER, id, item, time)
    private fun confirm(type: MapItemType, id: String, item: String, time: Long) = EventFactory.mapConfirmation(type, MapConfirmation(MapActionMetadata(id, item, "peer", time, 0), MapInformationSource.UNCERTAIN, "note"))
    private fun lifecycleActions(item: String) = listOf(
        EventFactory.mapConfirmation(MapItemType.MARKER, MapConfirmation(MapActionMetadata("life-confirm-a", item, "peer", 10, 0), MapInformationSource.UNCERTAIN, "a")),
        EventFactory.mapConfirmation(MapItemType.MARKER, MapConfirmation(MapActionMetadata("life-confirm-b", item, "peer2", 13, 0), MapInformationSource.UNCERTAIN, "b")),
        EventFactory.mapSituationUpdate(MapItemType.MARKER, MapSituationUpdate(MapActionMetadata("life-still", item, "peer", 11, 0), "still", MapInformationSource.FIRSTHAND, MapSituationUpdateType.STILL_ACTIVE)),
        EventFactory.mapSituationUpdate(MapItemType.MARKER, MapSituationUpdate(MapActionMetadata("life-change", item, "peer", 12, 0), "change", MapInformationSource.FIRSTHAND, MapSituationUpdateType.SITUATION_CHANGED)),
        EventFactory.mapSituationStatus(MapItemType.MARKER, MapSituationStatusUpdate(MapActionMetadata("life-resolve", item, "peer", 14, 0), MapSituationLifecycleStatus.NO_LONGER_ACTIVE, "resolved", MapInformationSource.FIRSTHAND)),
        EventFactory.mapSituationStatus(MapItemType.MARKER, MapSituationStatusUpdate(MapActionMetadata("life-resolve-repeat", item, "peer", 15, 0), MapSituationLifecycleStatus.NO_LONGER_ACTIVE, "resolved", MapInformationSource.FIRSTHAND)),
        EventFactory.mapConfirmation(MapItemType.MARKER, MapConfirmation(MapActionMetadata("life-historical", item, "peer2", 9, 0), MapInformationSource.UNCERTAIN, "historical")),
        EventFactory.mapConfirmation(MapItemType.MARKER, MapConfirmation(MapActionMetadata("life-post", item, "peer2", 16, 0), MapInformationSource.UNCERTAIN, "post")),
    )
    private fun coordinationActions(item: String) = listOf(
        EventFactory.mapAcknowledgement(MapItemType.MARKER, MapCoordinationAcknowledgement(MapActionMetadata("coord-ack-a", item, "peer", 10, 0))),
        EventFactory.mapAcknowledgement(MapItemType.MARKER, MapCoordinationAcknowledgement(MapActionMetadata("coord-ack-b", item, "peer2", 11, 0))),
        EventFactory.mapAcknowledgement(MapItemType.MARKER, MapCoordinationAcknowledgement(MapActionMetadata("coord-ack-a-repeat", item, "peer", 12, 0))),
        EventFactory.mapCoordinationNote(MapItemType.MARKER, MapCoordinationNote(MapActionMetadata("coord-note", item, "peer", 13, 0), "note")),
    )
    private fun markerAdd(id: String) = EventFactory.mapMarkerAdd("add-$id", "owner", 1, 0, id, "l", 1.0, 2.0, null, null, null, "risk", domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
    private fun areaAdd(id: String) = EventFactory.mapAreaAdd("add-$id", "owner", 1, 0, id, "l", buildJsonArray {}, null, null, "risk", domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
    private fun persist(database: AppDatabase, core: app.peermesh.core.model.EventCore) { database.eventsDao().insert(MapEventEntityFactory.create(MapEventProcessingRequest("g", core, "test", core.time.t))); val decoded = app.peermesh.core.map.MapItemEventCodec.decode(core)!!; val entity = (DefaultMapItemActionEntityFactory.create(MapItemActionPersistenceInput("g", core, decoded), Sha256MapEventFingerprint.sha256(core), MapItemActionInitialState.PENDING_TARGET) as PersistenceParseResult.Success).value; assertTrue(database.mapItemActionDao().insertIgnore(entity) is PersistenceWriteResult.Success) }
}

private data class ProjectionSnapshot(
    val lifecycle: String?,
    val latestConfirmation: MapActionOrderKey?,
    val latestSituationUpdate: MapActionOrderKey?,
    val latestLifecycle: MapActionOrderKey?,
    val latestMaterialChange: MapActionOrderKey?,
    val confirmationActorCount: Int,
    val acknowledgementActorCount: Int,
)

private val eventCoreActionOrderComparator = compareBy<app.peermesh.core.model.EventCore>(
    { it.time.t },
    { it.time.lt },
    { it.actor.did },
    { it.id },
)
