package app.peermesh.data.map.processing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapActionMetadata
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.map.MapCoordinationAcknowledgement
import app.peermesh.core.map.MapSituationUpdate
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.entity.RemovedMemberEntity
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.reducer.DedicatedMapActionReducer
import app.peermesh.data.reducer.DedicatedMapActionReductionResult
import app.peermesh.data.reducer.MapEventReducerDispatcher
import app.peermesh.data.reducer.RoomCanonicalMapEventReducer
import app.peermesh.data.reducer.RoomDedicatedMapActionReducer
import app.peermesh.data.reducer.CanonicalMapEventReducer
import app.peermesh.data.map.materialization.RoomMapItemActionProjectionDeriver
import app.peermesh.data.map.replay.DefaultPendingActionSemanticEvaluator
import app.peermesh.data.map.replay.PendingReplayTarget
import app.peermesh.data.map.replay.RoomTargetPendingReplayCoordinator
import app.peermesh.data.map.replay.RoomTargetProjectionRefresher
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMapEventProcessorTest {
    private lateinit var db: AppDatabase
    private lateinit var processor: RoomMapEventProcessor

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        db.userProfileDao().upsert(UserProfileEntity("g", "owner", "Owner", null, 1, "profile", 1, 0, "owner"))
        db.userProfileDao().upsert(UserProfileEntity("g", "peer", "Peer", null, 1, "profile-peer", 1, 0, "peer"))
        processor = RoomMapEventProcessor(db, DefaultCanonicalMapEventPolicy(), MapEventReducerDispatcher(
            RoomCanonicalMapEventReducer(db.mapDao(), db.mapItemVoteDao()),
            RoomDedicatedMapActionReducer(db.mapItemActionDao(), DefaultDedicatedMapActionPolicy,
                Sha256MapEventFingerprint, DefaultMapItemActionEntityFactory,
                RoomMapItemActionProjectionDeriver(db.mapItemActionDao()), db.mapItemDomainStateDao()),
        ))
    }
    @After fun tearDown() = db.close()

    @Test fun acceptedSituationAddCommitsEventCanonicalRowAndOwnerState() {
        val result = process(markerAdd("add", "owner", 10, "m"))
        assertTrue(result is MapEventProcessingResult.Accepted)
        assertEquals("owner", db.mapDao().findMarker("g", "m")!!.actor_did)
        val state = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        assertEquals("owner", state.owner_did)
        assertEquals("active", state.situation_lifecycle)
        assertEquals("firsthand", state.creator_information_source)
        assertNotNull(db.eventsDao().findById("g", "add"))
    }

    @Test fun duplicateAndConflictingAddAreDistinctAndDoNotOverwrite() {
        val add = markerAdd("same", "owner", 10, "m")
        assertTrue(process(add) is MapEventProcessingResult.Accepted)
        assertTrue(process(add) is MapEventProcessingResult.ExactDuplicate)
        assertTrue(process(markerAdd("other", "owner", 11, "m")) is MapEventProcessingResult.Rejected)
        assertNull(db.eventsDao().findById("g", "other"))
        assertEquals("same", db.mapDao().findMarker("g", "m")!!.last_event_id)
    }

    @Test fun sameEventIdentityWithDifferentPayloadIsConflictingDuplicate() {
        val first = markerAdd("identity", "owner", 10, "m")
        assertTrue(process(first) is MapEventProcessingResult.Accepted)
        val changed = first.copy(data = buildJsonObject {
            (first.data as kotlinx.serialization.json.JsonObject).forEach { (key, value) -> put(key, value) }
            put("label", "changed")
        })
        assertTrue(process(changed) is MapEventProcessingResult.ConflictingDuplicate)
        assertEquals("identity", db.mapDao().findMarker("g", "m")!!.last_event_id)
        assertEquals(1, db.eventsDao().countByType("g", app.peermesh.core.types.EventTypes.MAP_MARKER_ADD))
    }

    @Test fun nonOwnerMixedUpdateStoresOnlyLegacyContribution() {
        process(markerAdd("add", "owner", 10, "m"))
        val update = EventFactory.mapMarkerUpdate("vote", "peer", 11, 0, "m", "changed", 8.0, 9.0,
            50.0, "changed", "legacy note", "risk", confidence = 2)
        assertTrue(process(update) is MapEventProcessingResult.Accepted)
        val marker = db.mapDao().findMarker("g", "m")!!
        assertEquals(1.0, marker.lat, 0.0)
        assertEquals("add", marker.last_event_id)
        assertEquals("legacy note", db.mapItemVoteDao().find("g", "m", "peer")!!.note)
        assertEquals("active", db.mapItemDomainStateDao().find("g", "marker", "m")!!.situation_lifecycle)
    }

    @Test fun malformedLegacyContributionIsRejectedWithoutEventOrVote() {
        process(markerAdd("add", "owner", 10, "m"))
        val malformed = EventFactory.mapMarkerUpdate("bad-vote", "peer", 11, 0, "m", "l", 8.0, 9.0,
            null, null, null, "risk", confidence = 9)
        assertEquals(MapEventRejectionReason.INVALID_LEGACY_CONTRIBUTION,
            (process(malformed) as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", "bad-vote"))
        assertNull(db.mapItemVoteDao().find("g", "m", "peer"))
    }

    @Test fun areaNonOwnerContributionChangesOnlyVote() {
        val add = EventFactory.mapAreaAdd("area", "owner", 10, 0, "a", "l", buildJsonArray {}, null, null, "risk",
            domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
        assertTrue(process(add) is MapEventProcessingResult.Accepted)
        val update = EventFactory.mapAreaUpdate("area-vote", "peer", 11, 0, "a", "changed", buildJsonArray { add(JsonPrimitive(9)) }, "changed", "note", "risk", confidence = 2)
        assertTrue(process(update) is MapEventProcessingResult.Accepted)
        assertEquals("area", db.mapDao().findArea("g", "a")!!.last_event_id)
        assertEquals("note", db.mapItemVoteDao().find("g", "a", "peer")!!.note)
    }

    @Test fun staleCanonicalEventIsStoredAsNoChange() {
        process(markerAdd("add", "owner", 10, "m"))
        val stale = EventFactory.mapMarkerUpdate("stale", "owner", 9, 0, "m", "l", 8.0, 9.0, null, null, null, "risk")
        assertTrue(process(stale) is MapEventProcessingResult.AcceptedNoChange)
        assertEquals("add", db.mapDao().findMarker("g", "m")!!.last_event_id)
        assertNotNull(db.eventsDao().findById("g", "stale"))
        assertTrue(process(stale) is MapEventProcessingResult.ExactDuplicate)
    }

    @Test fun canonicalFlowDoesNotAdvanceActionDerivedPointers() {
        process(markerAdd("add", "owner", 10, "m"))
        db.mapItemDomainStateDao().updateLatestConfirmation("g", app.peermesh.core.map.MapItemType.MARKER, "m", MapActionOrderKey(11, 0, "peer", "confirm"))
        db.mapItemDomainStateDao().updateLatestSituationUpdate("g", app.peermesh.core.map.MapItemType.MARKER, "m", MapActionOrderKey(12, 0, "peer", "update"))
        db.mapItemDomainStateDao().updateLatestMaterialChange("g", app.peermesh.core.map.MapItemType.MARKER, "m", MapActionOrderKey(13, 0, "peer", "material"))
        val before = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        assertTrue(process(EventFactory.mapMarkerUpdate("canonical-upd", "owner", 20, 1, "m", "l", 2.0, 3.0, null, null, null, "risk")) is MapEventProcessingResult.Accepted)
        val after = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        assertEquals(before.latest_confirmation_event_id, after.latest_confirmation_event_id)
        assertEquals(before.latest_update_event_id, after.latest_update_event_id)
        assertEquals(before.latest_material_event_id, after.latest_material_event_id)
    }

    @Test fun ownerDeleteTombstonesAndLaterUpdateCannotRevive() {
        process(markerAdd("add", "owner", 10, "m"))
        assertTrue(process(EventFactory.mapMarkerDelete("del", "owner", 20, 0, "m")) is MapEventProcessingResult.Accepted)
        assertEquals("deleted", db.mapDao().findMarker("g", "m")!!.status)
        val update = EventFactory.mapMarkerUpdate("upd", "owner", 21, 0, "m", "l", 2.0, 3.0, null, null, null, "risk")
        assertEquals(MapEventRejectionReason.TARGET_DELETED, (process(update) as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", "upd"))
    }

    @Test fun ownerDeleteAtExpiryIsRejectedBeforeTombstone() {
        assertTrue(process(EventFactory.mapMarkerAdd("bounded-del", "owner", 10, 0, "m", "l", 1.0, 2.0, null, null, null, "risk", ttlSeconds = 10,
            domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))) is MapEventProcessingResult.Accepted)
        val result = process(EventFactory.mapMarkerDelete("bounded-del-event", "owner", 20, 0, "m"))
        assertEquals(MapEventRejectionReason.TARGET_EXPIRED, (result as MapEventProcessingResult.Rejected).reason)
        assertEquals("active", db.mapDao().findMarker("g", "m")!!.status)
        assertNull(db.eventsDao().findById("g", "bounded-del-event"))
    }

    @Test fun ownerAreaDeleteBeforeLegacyOwnerEditCutoffIsAccepted() {
        val add = EventFactory.mapAreaAdd("bounded-area-before", "owner", 10, 0, "a-before", "l", buildJsonArray {}, null, null, "risk", ttlSeconds = 2000)
        assertTrue(process(add) is MapEventProcessingResult.Accepted)
        val result = process(EventFactory.mapAreaDelete("bounded-area-before-del", "owner", 1009, 1, "a-before"))
        assertTrue(result is MapEventProcessingResult.Accepted)
        assertEquals("deleted", db.mapDao().findArea("g", "a-before")!!.status)
    }

    @Test fun ownerAreaDeleteAtOwnerEditCutoffIsRejected() {
        // Legacy compatibility derives owner_edit_until from canonical created_t (10) and TTL (2000): 1010.
        val add = EventFactory.mapAreaAdd("bounded-area", "owner", 10, 0, "a", "l", buildJsonArray {}, null, null, "risk", ttlSeconds = 2000)
        assertTrue(process(add) is MapEventProcessingResult.Accepted)
        val result = process(EventFactory.mapAreaDelete("bounded-area-del", "owner", 1010, 1, "a"))
        assertEquals(MapEventRejectionReason.OWNER_EDIT_WINDOW_CLOSED, (result as MapEventProcessingResult.Rejected).reason)
        assertEquals("active", db.mapDao().findArea("g", "a")!!.status)
        assertNull(db.eventsDao().findById("g", "bounded-area-del"))
    }

    @Test fun repeatedOwnerDeleteIsAcceptedWithoutRevivingTarget() {
        assertTrue(process(EventFactory.mapMarkerAdd("repeat-add", "owner", 10, 0, "repeat", "l", 1.0, 2.0, null, null, null, "risk", ttlSeconds = 10000)) is MapEventProcessingResult.Accepted)
        assertTrue(process(EventFactory.mapMarkerDelete("repeat-del-1", "owner", 20, 0, "repeat")) is MapEventProcessingResult.Accepted)
        val repeated = process(EventFactory.mapMarkerDelete("repeat-del-2", "owner", 21, 0, "repeat"))
        assertTrue(repeated is MapEventProcessingResult.Accepted)
        assertEquals("deleted", db.mapDao().findMarker("g", "repeat")!!.status)
        assertNotNull(db.eventsDao().findById("g", "repeat-del-2"))
    }

    @Test fun ownerAreaUpdateSucceedsAndNonOwnerAreaDeleteIsRejected() {
        val add = EventFactory.mapAreaAdd("area", "owner", 10, 0, "a", "l", buildJsonArray {}, null, null, "risk",
            domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
        assertTrue(process(add) is MapEventProcessingResult.Accepted)
        val update = EventFactory.mapAreaUpdate("area-upd", "owner", 11, 1, "a", "l", buildJsonArray { add(JsonPrimitive(1)) }, null, null, "risk")
        assertTrue(process(update) is MapEventProcessingResult.Accepted)
        val delete = EventFactory.mapAreaDelete("area-del", "peer", 12, 1, "a")
        assertEquals(MapEventRejectionReason.OWNER_REQUIRED, (process(delete) as MapEventProcessingResult.Rejected).reason)
        assertEquals("area-upd", db.mapDao().findArea("g", "a")!!.last_event_id)
    }

    @Test fun missingTargetDedicatedEventIsPendingWhileNonMapEventIsRejected() {
        val action = EventFactory.mapConfirmation(app.peermesh.core.map.MapItemType.MARKER,
            app.peermesh.core.map.MapConfirmation(
                app.peermesh.core.map.MapActionMetadata("action", "m", "owner", 10, 0), null, null))
        assertTrue(process(action) is MapEventProcessingResult.Accepted)
        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "action")!!.materialization_state)
        val sys = EventFactory.sysGroup("sys", "owner", 10, 0, "group")
        assertEquals(MapEventRejectionReason.UNSUPPORTED_MAP_EVENT,
            (process(sys) as MapEventProcessingResult.Rejected).reason)
    }

    @Test fun missingTargetDedicatedEventWithRootIsDeferredForDependencyRepair() {
        val base = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("deferred-action", "missing", "owner", 10, 0), null, null))
        val action = base.copy(ref = base.ref.copy(root = "canonical-add"))

        assertEquals(
            MapEventProcessingResult.AcceptedDeferred("deferred-action", "canonical-add"),
            process(action),
        )
        assertEquals("pending_target", db.mapItemActionDao()
            .findByActionId("g", "deferred-action")!!.materialization_state)
    }

    @Test fun canonicalMarkerAddReplaysExactTargetPendingRowsAndRejectsIncompatibleSibling() {
        val confirm = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("pending-confirm", "m", "peer", 11, 2), null, null))
        val wrongFamily = EventFactory.mapAcknowledgement(MapItemType.MARKER,
            MapCoordinationAcknowledgement(MapActionMetadata("pending-ack", "m", "peer", 12, 0)))
        val unrelated = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("other-confirm", "other", "peer", 11, 0), null, null))
        assertTrue(process(confirm) is MapEventProcessingResult.Accepted)
        assertTrue(process(wrongFamily) is MapEventProcessingResult.Accepted)
        assertTrue(process(unrelated) is MapEventProcessingResult.Accepted)

        assertTrue(process(markerAdd("target-add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)

        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "pending-confirm")!!.materialization_state)
        val rejected = db.mapItemActionDao().findByActionId("g", "pending-ack")!!
        assertEquals("rejected", rejected.materialization_state)
        assertEquals("action_not_allowed_for_category", rejected.rejection_reason)
        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "other-confirm")!!.materialization_state)
        val state = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        assertEquals(1, state.confirmation_actor_count)
        assertEquals("pending-confirm", state.latest_confirmation_event_id)
    }

    @Test fun canonicalAddRollsBackWhenReplayFindsCanonicalKindCategoryMismatch() {
        val pending = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("pending", "m", "peer", 11, 0), null, null))
        assertTrue(process(pending) is MapEventProcessingResult.Accepted)
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER corrupt_domain_state_after_add
            AFTER INSERT ON map_item_domain_state WHEN NEW.gid = 'g' AND NEW.item_id = 'm'
            BEGIN
              UPDATE map_item_domain_state
              SET category = 'coordination', situation_lifecycle = NULL
              WHERE gid = NEW.gid AND item_type = NEW.item_type AND item_id = NEW.item_id;
            END
        """.trimIndent())

        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.PersistenceFailure)

        assertNull(db.eventsDao().findById("g", "add"))
        assertNull(db.mapDao().findMarker("g", "m"))
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "m"))
        val stored = db.mapItemActionDao().findByActionId("g", "pending")!!
        assertEquals("pending_target", stored.materialization_state)
        assertNull(stored.rejection_reason)
    }

    @Test fun canonicalAddRollsBackWhenPendingActionHasNoDurableEvent() {
        val pending = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("pending", "m", "peer", 11, 0), null, null))
        assertTrue(process(pending) is MapEventProcessingResult.Accepted)
        assertEquals(1, db.eventsDao().deleteByEventId("g", "pending"))

        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.PersistenceFailure)

        assertNull(db.eventsDao().findById("g", "add"))
        assertNull(db.mapDao().findMarker("g", "m"))
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "m"))
        val stored = db.mapItemActionDao().findByActionId("g", "pending")!!
        assertEquals("pending_target", stored.materialization_state)
        assertNull(stored.rejection_reason)
    }

    @Test fun coordinatorRejectsDurableOrderingSemanticAndFingerprintContradictions() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val withNote = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("pending", "m", "peer", 11, 0), null, "original"))
        persistPendingWithDurable(withNote)

        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET action_t = 12 WHERE gid='g' AND action_id='pending'")
        assertReplayPairFails("m", "durable_event_action_identity_contradiction")
        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "pending")!!.materialization_state)

        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET action_t = 11, action_note = 'corrupt' WHERE gid='g' AND action_id='pending'")
        assertReplayPairFails("m", "durable_event_action_semantic_contradiction")

        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET action_note = 'original', event_fingerprint = '00' WHERE gid='g' AND action_id='pending'")
        assertReplayPairFails("m", "durable_event_fingerprint_mismatch")
        val stored = db.mapItemActionDao().findByActionId("g", "pending")!!
        assertEquals("pending_target", stored.materialization_state)
        assertNull(stored.rejection_reason)
    }

    @Test fun invalidPendingPairRollsBackEarlierSiblingTransition() {
        val first = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("first", "m", "peer", 11, 0), null, null))
        val second = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("second", "m", "owner", 12, 0), null, null))
        assertTrue(process(first) is MapEventProcessingResult.Accepted)
        assertTrue(process(second) is MapEventProcessingResult.Accepted)
        assertEquals(1, db.eventsDao().deleteByEventId("g", "second"))

        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.PersistenceFailure)

        listOf("first", "second").forEach { id ->
            val row = db.mapItemActionDao().findByActionId("g", id)!!
            assertEquals("pending_target", row.materialization_state)
            assertNull(row.rejection_reason)
        }
        assertNull(db.mapDao().findMarker("g", "m"))
    }

    @Test fun duplicateAndReplaySharePersistedPairInvariant() {
        val pending = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("pending", "m", "peer", 11, 0), null, null))
        assertTrue(process(pending) is MapEventProcessingResult.Accepted)
        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET action_lt = 9 WHERE gid='g' AND action_id='pending'")

        assertTrue(process(pending) is MapEventProcessingResult.InvalidPersistedState)
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.PersistenceFailure)
        val row = db.mapItemActionDao().findByActionId("g", "pending")!!
        assertEquals("pending_target", row.materialization_state)
        assertNull(row.rejection_reason)
    }

    @Test fun canonicalAreaAddReplaysPendingAreaAction() {
        val confirm = EventFactory.mapConfirmation(MapItemType.AREA,
            MapConfirmation(MapActionMetadata("area-confirm", "a", "peer", 11, 0), null, null))
        assertTrue(process(confirm) is MapEventProcessingResult.Accepted)
        val add = EventFactory.mapAreaAdd("area-add", "owner", 10, 0, "a", "l", buildJsonArray {},
            null, null, "risk", domainExtension = MapCanonicalAddExtension(
                MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND,
            ))
        assertTrue(process(add) is MapEventProcessingResult.Accepted)
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "area-confirm")!!.materialization_state)
        assertEquals(1, db.mapItemDomainStateDao().find("g", "area", "a")!!.confirmation_actor_count)
    }

    @Test fun coordinatorRejectsCanonicalKindCategoryMismatchBeforePendingTransition() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        persistPendingConfirmation("pending", "m")
        val before = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        db.openHelper.writableDatabase.execSQL("""
            UPDATE map_item_domain_state SET category = 'coordination', situation_lifecycle = NULL
            WHERE gid = 'g' AND item_type = 'marker' AND item_id = 'm'
        """.trimIndent())

        assertReplayFails("m")

        val pending = db.mapItemActionDao().findByActionId("g", "pending")!!
        assertEquals("pending_target", pending.materialization_state)
        assertNull(pending.rejection_reason)
        assertEquals(before.confirmation_actor_count,
            db.mapItemDomainStateDao().find("g", "marker", "m")!!.confirmation_actor_count)
    }

    @Test fun coordinatorRejectsSituationWithoutLifecycleBeforePendingTransition() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        persistPendingConfirmation("pending", "m")
        val before = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        db.openHelper.writableDatabase.execSQL("""
            UPDATE map_item_domain_state SET situation_lifecycle = NULL
            WHERE gid = 'g' AND item_type = 'marker' AND item_id = 'm'
        """.trimIndent())

        assertReplayFails("m")

        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "pending")!!.materialization_state)
        assertEquals(before.confirmation_actor_count,
            db.mapItemDomainStateDao().find("g", "marker", "m")!!.confirmation_actor_count)
    }

    @Test fun coordinatorRejectsCoordinationWithLifecycleBeforePendingTransition() {
        val add = EventFactory.mapMarkerAdd("coord-add", "owner", 10, 0, "c", "l", 1.0, 2.0,
            null, null, null, "camp", domainExtension = MapCanonicalAddExtension(MapItemCategory.COORDINATION, null))
        assertTrue(process(add) is MapEventProcessingResult.Accepted)
        val pendingEvent = EventFactory.mapAcknowledgement(MapItemType.MARKER,
            MapCoordinationAcknowledgement(MapActionMetadata("pending", "c", "peer", 11, 0)))
        persistPending(pendingEvent)
        val before = db.mapItemDomainStateDao().find("g", "marker", "c")!!
        db.openHelper.writableDatabase.execSQL("""
            UPDATE map_item_domain_state SET situation_lifecycle = 'active'
            WHERE gid = 'g' AND item_type = 'marker' AND item_id = 'c'
        """.trimIndent())

        assertReplayFails("c")

        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "pending")!!.materialization_state)
        assertEquals(before.acknowledgement_actor_count,
            db.mapItemDomainStateDao().find("g", "marker", "c")!!.acknowledgement_actor_count)
    }

    @Test fun contradictorySnapshotHasSameInvalidPersistedOutcomeForLiveAndReplay() {
        assertTrue(process(markerAdd("live-add", "owner", 10, "live")) is MapEventProcessingResult.Accepted)
        db.openHelper.writableDatabase.execSQL("""
            UPDATE map_item_domain_state SET situation_lifecycle = NULL
            WHERE gid = 'g' AND item_type = 'marker' AND item_id = 'live'
        """.trimIndent())
        val live = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("live-action", "live", "peer", 11, 0), null, null))
        assertTrue(process(live) is MapEventProcessingResult.InvalidPersistedState)

        assertTrue(process(markerAdd("replay-add", "owner", 10, "replay")) is MapEventProcessingResult.Accepted)
        persistPendingConfirmation("replay-pending", "replay")
        db.openHelper.writableDatabase.execSQL("""
            UPDATE map_item_domain_state SET situation_lifecycle = NULL
            WHERE gid = 'g' AND item_type = 'marker' AND item_id = 'replay'
        """.trimIndent())
        assertReplayFails("replay")
        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "replay-pending")!!.materialization_state)
    }

    @Test fun replayUsesEarliestResolutionAsIrreversibleCutoff() {
        val after = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("confirm-after", "m", "peer", 21, 0), null, null))
        val resolution = EventFactory.mapSituationStatus(MapItemType.MARKER,
            app.peermesh.core.map.MapSituationStatusUpdate(
                MapActionMetadata("resolution", "m", "peer", 20, 0),
                app.peermesh.core.map.MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null,
            ))
        val before = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("confirm-before", "m", "peer", 19, 0), null, null))
        // Persist in deliberately different arrival order; replay must use total action order.
        assertTrue(process(after) is MapEventProcessingResult.Accepted)
        assertTrue(process(resolution) is MapEventProcessingResult.Accepted)
        assertTrue(process(before) is MapEventProcessingResult.Accepted)
        assertTrue(process(markerAdd("resolved-add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "confirm-before")!!.materialization_state)
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "resolution")!!.materialization_state)
        val rejected = db.mapItemActionDao().findByActionId("g", "confirm-after")!!
        assertEquals("rejected", rejected.materialization_state)
        assertEquals("target_resolved", rejected.rejection_reason)
        val state = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        assertEquals("no_longer_active", state.situation_lifecycle)
        assertEquals("resolution", state.latest_lifecycle_event_id)
    }

    @Test fun existingTargetLiveActionDrainsSameTargetBacklogBeforeFinalProjection() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val pendingEvent = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("late-pending", "m", "owner", 11, 0), null, null))
        persistPendingWithDurable(pendingEvent)

        val live = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("live-confirm", "m", "peer", 12, 0), null, null))
        assertTrue(process(live) is MapEventProcessingResult.Accepted)

        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "late-pending")!!.materialization_state)
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "live-confirm")!!.materialization_state)
        val state = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        assertEquals(2, state.confirmation_actor_count)
        assertEquals("live-confirm", state.latest_confirmation_event_id)
    }

    @Test fun removedActorIsRejectedInsideTransaction() {
        db.removedMemberDao().upsert(RemovedMemberEntity("g", "owner", "admin", "removed", 9, 9, 0, "admin"))
        assertEquals(MapEventRejectionReason.ACTOR_NOT_MEMBER,
            (process(markerAdd("add", "owner", 10, "m")) as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", "add"))
        assertNull(db.mapDao().findMarker("g", "m"))
    }

    @Test fun expiryAndOwnerEditBoundariesAreExclusiveWithExpiryPrecedence() {
        val bounded = EventFactory.mapMarkerAdd("add", "owner", 10, 0, "m", "l", 1.0, 2.0, null, null, null, "risk", ttlSeconds = 10,
            domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
        assertTrue(process(bounded) is MapEventProcessingResult.Accepted)
        val atBoundary = EventFactory.mapMarkerUpdate("upd", "owner", 20, 0, "m", "l", 2.0, 3.0,
            null, null, null, "risk")
        assertEquals(MapEventRejectionReason.TARGET_EXPIRED, (process(atBoundary) as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", "upd"))
    }

    @Test fun reducerFailureAfterEventInsertRollsBackEventAndCanonicalState() {
        val failing = RoomMapEventProcessor(db, DefaultCanonicalMapEventPolicy(), MapEventReducerDispatcher(
            object : CanonicalMapEventReducer {
                override fun apply(gid: String, event: app.peermesh.core.model.EventCore, decision: CanonicalMapEventPolicyDecision): app.peermesh.data.reducer.CanonicalMapReductionResult {
                    throw IllegalStateException("injected reducer failure")
                }
            },
            object : DedicatedMapActionReducer {
                override fun apply(context: DedicatedMapActionContext) = DedicatedMapActionReductionResult.DeferredToM2C2
            },
        ))
        val result = failing.process(MapEventProcessingRequest("g", markerAdd("fail", "owner", 10, "m"), "test", 10))
        assertTrue(result is MapEventProcessingResult.PersistenceFailure)
        assertNull(db.eventsDao().findById("g", "fail"))
        assertNull(db.mapDao().findMarker("g", "m"))
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "m"))
    }

    @Test fun areaAddAndCoordinationLifecycleAndTypeConflictAreEnforced() {
        val area = EventFactory.mapAreaAdd("area", "owner", 10, 0, "a", "l", buildJsonArray {}, null, null, "risk",
            domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
        assertTrue(process(area) is MapEventProcessingResult.Accepted)
        assertEquals("active", db.mapItemDomainStateDao().find("g", "area", "a")!!.situation_lifecycle)
        val coordination = EventFactory.mapMarkerAdd("coord", "owner", 10, 0, "c", "l", 1.0, 2.0,
            null, null, null, "camp", domainExtension = MapCanonicalAddExtension(MapItemCategory.COORDINATION, null))
        assertTrue(process(coordination) is MapEventProcessingResult.Accepted)
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "c")!!.situation_lifecycle)
        val conflict = markerAdd("marker", "owner", 11, "a")
        assertEquals(MapEventRejectionReason.TARGET_TYPE_CONFLICT, (process(conflict) as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", "marker"))
    }

    @Test fun validSituationAndCoordinationActionsMaterializeFromImmutableAppliedHistory() {
        assertTrue(process(markerAdd("s-add", "owner", 10, "s")) is MapEventProcessingResult.Accepted)
        val situationBefore = db.mapItemDomainStateDao().find("g", "marker", "s")!!
        val confirm = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("confirm", "s", "peer", 11, 3), MapInformationSource.UNCERTAIN, "seen"))
        assertTrue(process(confirm) is MapEventProcessingResult.Accepted)
        val confirmRow = db.mapItemActionDao().findByActionId("g", "confirm")!!
        assertEquals("applied", confirmRow.materialization_state)
        assertEquals("confirm", confirmRow.action_type)
        assertEquals("uncertain", confirmRow.information_source)
        assertNull(confirmRow.rejection_reason)
        val situationAfter = db.mapItemDomainStateDao().find("g", "marker", "s")!!
        assertEquals(1, situationAfter.confirmation_actor_count)
        assertEquals("confirm", situationAfter.latest_confirmation_event_id)
        assertEquals(situationBefore.canonical_add_event_id, situationAfter.canonical_add_event_id)

        val coordination = EventFactory.mapMarkerAdd("c-add", "owner", 10, 0, "c", "l", 1.0, 2.0,
            null, null, null, "camp", domainExtension = MapCanonicalAddExtension(MapItemCategory.COORDINATION, null))
        assertTrue(process(coordination) is MapEventProcessingResult.Accepted)
        val coordinationBefore = db.mapItemDomainStateDao().find("g", "marker", "c")!!
        val ack = EventFactory.mapAcknowledgement(MapItemType.MARKER,
            MapCoordinationAcknowledgement(MapActionMetadata("ack", "c", "peer", 12, 4)))
        assertTrue(process(ack) is MapEventProcessingResult.Accepted)
        assertEquals("acknowledge", db.mapItemActionDao().findByActionId("g", "ack")!!.action_type)
        val coordinationAfter = db.mapItemDomainStateDao().find("g", "marker", "c")!!
        assertEquals(1, coordinationAfter.acknowledgement_actor_count)
        assertEquals(0, coordinationAfter.confirmation_actor_count)
        assertEquals(coordinationBefore.canonical_add_event_id, coordinationAfter.canonical_add_event_id)
    }

    @Test fun dedicatedActionsEnforceCategoryTypeMembershipDeletionAndWindowsWithoutPersistence() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val wrongCategory = EventFactory.mapAcknowledgement(MapItemType.MARKER,
            MapCoordinationAcknowledgement(MapActionMetadata("wrong-category", "m", "peer", 11, 0)))
        assertEquals(MapEventRejectionReason.ACTION_NOT_ALLOWED_FOR_CATEGORY,
            (process(wrongCategory) as MapEventProcessingResult.Rejected).reason)

        val wrongType = EventFactory.mapConfirmation(MapItemType.AREA,
            MapConfirmation(MapActionMetadata("wrong-type", "m", "peer", 11, 0), null, null))
        assertEquals(MapEventRejectionReason.TARGET_TYPE_CONFLICT, (process(wrongType) as MapEventProcessingResult.Rejected).reason)

        db.removedMemberDao().upsert(RemovedMemberEntity("g", "peer", "admin", "removed", 9, 9, 0, "admin"))
        val removed = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("removed", "m", "peer", 11, 0), null, null))
        assertEquals(MapEventRejectionReason.ACTOR_NOT_MEMBER, (process(removed) as MapEventProcessingResult.Rejected).reason)
        db.openHelper.writableDatabase.execSQL("DELETE FROM removed_members WHERE groupId = 'g' AND peerId = 'peer'")

        db.openHelper.writableDatabase.execSQL("UPDATE map_item_domain_state SET expires_at = 20, interaction_until = 20 WHERE gid = 'g' AND item_id = 'm'")
        db.openHelper.writableDatabase.execSQL("UPDATE map_markers SET expires_at = 20 WHERE gid = 'g' AND marker_id = 'm'")
        val expired = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("expired", "m", "peer", 20, 0), null, null))
        assertEquals(MapEventRejectionReason.TARGET_EXPIRED, (process(expired) as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", "expired")); assertNull(db.mapItemActionDao().findByActionId("g", "expired"))

        val marker = db.mapDao().findMarker("g", "m")!!
        db.mapDao().upsertMarker(marker.copy(status = "deleted"))
        val deleted = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("deleted", "m", "peer", 19, 0), null, null))
        assertEquals(MapEventRejectionReason.TARGET_DELETED, (process(deleted) as MapEventProcessingResult.Rejected).reason)
    }

    @Test fun dedicatedDuplicatePairClassificationPreservesFirstRowAndDetectsHalfPairs() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("action", "m", "peer", 11, 1), null, "first"))
        assertTrue(process(action) is MapEventProcessingResult.Accepted)
        val first = db.mapItemActionDao().findByActionId("g", "action")!!
        val projection = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        assertTrue(process(action) is MapEventProcessingResult.ExactDuplicate)
        val changed = action.copy(data = buildJsonObject {
            (action.data as kotlinx.serialization.json.JsonObject).forEach { (key, value) -> put(key, value) }
            put("action_note", "changed")
        })
        assertTrue(process(changed) is MapEventProcessingResult.ConflictingDuplicate)
        assertEquals(first, db.mapItemActionDao().findByActionId("g", "action"))
        assertEquals(projection, db.mapItemDomainStateDao().find("g", "marker", "m"))

        val missingAction = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("missing-action", "m", "peer", 12, 0), null, null))
        db.eventsDao().insert(MapEventEntityFactory.create(MapEventProcessingRequest("g", missingAction, "test", 12)))
        assertTrue(process(missingAction) is MapEventProcessingResult.InvalidPersistedState)

        db.eventsDao().deleteByEventId("g", "action")
        assertTrue(process(action) is MapEventProcessingResult.InvalidPersistedState)
        assertNull(db.eventsDao().findById("g", "action"))
        assertEquals(first, db.mapItemActionDao().findByActionId("g", "action"))
    }

    @Test fun changedIncomingActionIdUsesStoredEventPairBeforeConflictClassification() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val original = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("same-id", "m", "peer", 11, 0), null, "original"))
        assertTrue(process(original) is MapEventProcessingResult.Accepted)
        val storedAction = db.mapItemActionDao().findByActionId("g", "same-id")!!
        val storedEvent = db.eventsDao().findById("g", "same-id")!!
        val projection = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        val changedActionId = original.copy(data = buildJsonObject {
            (original.data as kotlinx.serialization.json.JsonObject).forEach { (key, value) -> put(key, value) }
            put("action_id", "different-action-id")
        })

        assertTrue(process(changedActionId) is MapEventProcessingResult.ConflictingDuplicate)
        assertEquals(storedAction, db.mapItemActionDao().findByActionId("g", "same-id"))
        assertNull(db.mapItemActionDao().findByActionId("g", "different-action-id"))
        assertEquals(storedEvent, db.eventsDao().findById("g", "same-id"))
        assertEquals(projection, db.mapItemDomainStateDao().find("g", "marker", "m"))
    }

    @Test fun newDedicatedEventStillRequiresGenericEventValidation() {
        val valid = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("generic-invalid", "missing", "peer", 11, 0), null, null))
        val invalid = valid.copy(actor = valid.actor.copy(did = ""))

        val result = process(invalid)
        assertEquals(MapEventRejectionReason.INVALID_REQUEST, (result as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", "generic-invalid"))
        assertNull(db.mapItemActionDao().findByActionId("g", "generic-invalid"))
        assertEquals(0, db.mapItemActionDao().findPendingTarget("g", "marker", "missing").size)
    }

    @Test fun incomingConflictCannotMaskMissingStoredActionPair() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val original = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("same-id", "m", "peer", 11, 0), null, "original"))
        assertTrue(process(original) is MapEventProcessingResult.Accepted)
        val before = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        db.openHelper.writableDatabase.execSQL("DELETE FROM map_item_actions WHERE gid='g' AND action_id='same-id'")
        val changed = original.copy(data = buildJsonObject {
            (original.data as kotlinx.serialization.json.JsonObject).forEach { (key, value) -> put(key, value) }
            put("action_note", "changed")
        })
        assertTrue(process(changed) is MapEventProcessingResult.InvalidPersistedState)
        assertNull(db.mapItemActionDao().findByActionId("g", "same-id"))
        assertEquals(before, db.mapItemDomainStateDao().find("g", "marker", "m"))
    }

    @Test fun incomingConflictCannotMaskCorruptStoredActionPair() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val original = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("corrupt-id", "m", "peer", 11, 0), null, "original"))
        assertTrue(process(original) is MapEventProcessingResult.Accepted)
        val before = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET action_t = 99 WHERE gid='g' AND action_id='corrupt-id'")
        val changed = original.copy(data = buildJsonObject {
            (original.data as kotlinx.serialization.json.JsonObject).forEach { (key, value) -> put(key, value) }
            put("action_note", "changed")
        })
        assertTrue(process(changed) is MapEventProcessingResult.InvalidPersistedState)
        val stored = db.mapItemActionDao().findByActionId("g", "corrupt-id")!!
        assertEquals(99L, stored.action_t)
        assertEquals(before, db.mapItemDomainStateDao().find("g", "marker", "m"))
    }

    @Test fun projectionAffectedRowInvariantRollsBackEventActionAndDomainMutation() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val before = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        val deletingPolicy = DedicatedMapActionPolicy { context ->
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM map_item_domain_state WHERE gid='g' AND item_type='marker' AND item_id='m'",
            )
            DefaultDedicatedMapActionPolicy.evaluate(context)
        }
        val failing = RoomMapEventProcessor(db, DefaultCanonicalMapEventPolicy(), MapEventReducerDispatcher(
            RoomCanonicalMapEventReducer(db.mapDao(), db.mapItemVoteDao()),
            RoomDedicatedMapActionReducer(db.mapItemActionDao(), deletingPolicy, Sha256MapEventFingerprint,
                DefaultMapItemActionEntityFactory, RoomMapItemActionProjectionDeriver(db.mapItemActionDao()),
                db.mapItemDomainStateDao()),
        ))
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("projection-fail", "m", "peer", 11, 0), null, null))
        assertTrue(failing.process(MapEventProcessingRequest("g", action, "test", 11)) is MapEventProcessingResult.InvalidPersistedState)
        assertNull(db.eventsDao().findById("g", "projection-fail"))
        assertNull(db.mapItemActionDao().findByActionId("g", "projection-fail"))
        assertEquals(before, db.mapItemDomainStateDao().find("g", "marker", "m"))
    }

    @Test fun unresolvedPersistedCategoryIsSemanticRejectionWithoutRows() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        db.openHelper.writableDatabase.execSQL("""
            UPDATE map_item_domain_state SET category = NULL, category_origin = 'unresolved',
              domain_version = NULL, state_origin = 'legacy_row_backfill', temporal_origin = 'legacy_unresolved'
            WHERE gid = 'g' AND item_type = 'marker' AND item_id = 'm'
        """.trimIndent())
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("unresolved", "m", "peer", 11, 0), null, null))
        assertEquals(MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED,
            (process(action) as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", "unresolved"))
        assertNull(db.mapItemActionDao().findByActionId("g", "unresolved"))
    }

    @Test fun dedicatedReducerFailureRollsBackDurableEventAndAction() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val failing = RoomMapEventProcessor(db, DefaultCanonicalMapEventPolicy(), MapEventReducerDispatcher(
            RoomCanonicalMapEventReducer(db.mapDao(), db.mapItemVoteDao()),
            object : DedicatedMapActionReducer {
                override fun apply(context: DedicatedMapActionContext): DedicatedMapActionReductionResult {
                    throw IllegalStateException("injected action failure")
                }
            },
        ))
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("fail-action", "m", "peer", 11, 0), null, null))
        val result = failing.process(MapEventProcessingRequest("g", action, "test", 11))
        assertTrue(result is MapEventProcessingResult.PersistenceFailure)
        assertNull(db.eventsDao().findById("g", "fail-action"))
        assertNull(db.mapItemActionDao().findByActionId("g", "fail-action"))
    }

    @Test fun appliedResolutionHistoryRejectsNewerConfirmAndKeepsOlderConfirmHistorical() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val status = EventFactory.mapSituationStatus(MapItemType.MARKER,
            app.peermesh.core.map.MapSituationStatusUpdate(
                MapActionMetadata("status-20", "m", "peer", 20, 0),
                app.peermesh.core.map.MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null,
            ))
        assertTrue(process(status) is MapEventProcessingResult.Accepted)
        val newer = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("confirm-21", "m", "peer", 21, 0), null, null))
        assertEquals(MapEventRejectionReason.TARGET_RESOLVED, (process(newer) as MapEventProcessingResult.Rejected).reason)
        val historical = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("confirm-19", "m", "peer", 19, 0), null, null))
        assertTrue(process(historical) is MapEventProcessingResult.Accepted)
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "confirm-19")!!.materialization_state)
    }

    @Test fun appliedResolutionHistoryRejectsNewerSituationUpdate() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        val status = EventFactory.mapSituationStatus(MapItemType.MARKER,
            app.peermesh.core.map.MapSituationStatusUpdate(
                MapActionMetadata("status-20", "m", "peer", 20, 0),
                app.peermesh.core.map.MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null,
            ))
        assertTrue(process(status) is MapEventProcessingResult.Accepted)
        val update = EventFactory.mapSituationUpdate(MapItemType.MARKER,
            app.peermesh.core.map.MapSituationUpdate(
                MapActionMetadata("update-21", "m", "peer", 21, 0), "new update", null,
                app.peermesh.core.map.MapSituationUpdateType.STILL_ACTIVE,
            ))
        assertEquals(MapEventRejectionReason.TARGET_RESOLVED, (process(update) as MapEventProcessingResult.Rejected).reason)
    }

    @Test fun repeatedResolutionIsAcceptedAndTieUsesLamportActorAndEventId() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        db.userProfileDao().upsert(UserProfileEntity("g", "a", "A", null, 1, "a", 1, 0, "a"))
        db.userProfileDao().upsert(UserProfileEntity("g", "z", "Z", null, 1, "z", 1, 0, "z"))
        val status = EventFactory.mapSituationStatus(MapItemType.MARKER,
            app.peermesh.core.map.MapSituationStatusUpdate(
                MapActionMetadata("status-20", "m", "z", 20, 2),
                app.peermesh.core.map.MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null,
            ))
        assertTrue(process(status) is MapEventProcessingResult.Accepted)
        val lamportBefore = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("confirm-lt-before", "m", "z", 20, 1), null, null))
        assertTrue(process(lamportBefore) is MapEventProcessingResult.Accepted)
        val actorBefore = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("confirm-actor-before", "m", "a", 20, 2), null, null))
        assertTrue(process(actorBefore) is MapEventProcessingResult.Accepted)
        val eventAfter = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("zzzz", "m", "z", 20, 2), null, null))
        assertEquals(MapEventRejectionReason.TARGET_RESOLVED, (process(eventAfter) as MapEventProcessingResult.Rejected).reason)
        val repeated = EventFactory.mapSituationStatus(MapItemType.MARKER,
            app.peermesh.core.map.MapSituationStatusUpdate(
                MapActionMetadata("status-21", "m", "peer", 21, 0),
                app.peermesh.core.map.MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null,
            ))
        assertTrue(process(repeated) is MapEventProcessingResult.Accepted)
        val state = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        assertEquals("no_longer_active", state.situation_lifecycle)
        assertEquals("status-21", state.latest_lifecycle_event_id)
        assertEquals("status-21", state.latest_material_event_id)
        assertEquals(0, state.confirmation_actor_count)
    }

    @Test fun materialBoundaryAndLatestPointersConvergeAcrossOppositeArrivalOrders() {
        db.userProfileDao().upsert(UserProfileEntity("g", "peer2", "Peer2", null, 1, "peer2", 1, 0, "peer2"))
        assertTrue(process(markerAdd("add-a", "owner", 10, "a")) is MapEventProcessingResult.Accepted)
        assertTrue(process(markerAdd("add-b", "owner", 10, "b")) is MapEventProcessingResult.Accepted)

        fun confirm(id: String, item: String, actor: String, t: Long, lt: Long) = EventFactory.mapConfirmation(
            MapItemType.MARKER, MapConfirmation(MapActionMetadata(id, item, actor, t, lt), null, null))
        fun update(id: String, item: String, t: Long, lt: Long, disposition: MapSituationUpdateType) =
            EventFactory.mapSituationUpdate(MapItemType.MARKER,
                MapSituationUpdate(MapActionMetadata(id, item, "peer", t, lt), "update", null, disposition))

        val a = listOf(
            confirm("a-old", "a", "peer", 20, 1),
            update("a-material", "a", 20, 2, MapSituationUpdateType.SITUATION_CHANGED),
            confirm("z-a-event", "a", "peer", 20, 2),
            confirm("a-new", "a", "peer2", 20, 2),
            update("a-still", "a", 21, 0, MapSituationUpdateType.STILL_ACTIVE),
        )
        val b = listOf(
            update("b-still", "b", 21, 0, MapSituationUpdateType.STILL_ACTIVE),
            confirm("b-new", "b", "peer2", 20, 2),
            confirm("z-b-event", "b", "peer", 20, 2),
            update("b-material", "b", 20, 2, MapSituationUpdateType.SITUATION_CHANGED),
            confirm("b-old", "b", "peer", 20, 1),
        )
        a.forEach { assertTrue(process(it) is MapEventProcessingResult.Accepted) }
        b.forEach { assertTrue(process(it) is MapEventProcessingResult.Accepted) }
        val left = db.mapItemDomainStateDao().find("g", "marker", "a")!!
        val right = db.mapItemDomainStateDao().find("g", "marker", "b")!!
        assertEquals(2, left.confirmation_actor_count)
        assertEquals(left.confirmation_actor_count, right.confirmation_actor_count)
        assertEquals("a-new", left.latest_confirmation_event_id)
        assertEquals("b-new", right.latest_confirmation_event_id)
        assertEquals("a-still", left.latest_update_event_id)
        assertEquals("b-still", right.latest_update_event_id)
        assertEquals("a-material", left.latest_material_event_id)
        assertEquals("b-material", right.latest_material_event_id)
    }

    @Test fun resolutionCutoffConvergesWhenPostResolutionConfirmationArrivesFirst() {
        assertTrue(process(markerAdd("add-a", "owner", 10, "a")) is MapEventProcessingResult.Accepted)
        assertTrue(process(markerAdd("add-b", "owner", 10, "b")) is MapEventProcessingResult.Accepted)
        fun status(id: String, item: String) = EventFactory.mapSituationStatus(MapItemType.MARKER,
            app.peermesh.core.map.MapSituationStatusUpdate(
                MapActionMetadata(id, item, "peer", 20, 0),
                app.peermesh.core.map.MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null))
        fun confirm(id: String, item: String) = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata(id, item, "peer", 21, 0), null, null))

        assertTrue(process(status("status-a", "a")) is MapEventProcessingResult.Accepted)
        assertEquals(MapEventRejectionReason.TARGET_RESOLVED,
            (process(confirm("confirm-a", "a")) as MapEventProcessingResult.Rejected).reason)
        assertTrue(process(confirm("confirm-b", "b")) is MapEventProcessingResult.Accepted)
        assertTrue(process(status("status-b", "b")) is MapEventProcessingResult.Accepted)

        val left = db.mapItemDomainStateDao().find("g", "marker", "a")!!
        val right = db.mapItemDomainStateDao().find("g", "marker", "b")!!
        assertEquals("no_longer_active", left.situation_lifecycle)
        assertEquals(left.situation_lifecycle, right.situation_lifecycle)
        assertNull(left.latest_confirmation_event_id)
        assertNull(right.latest_confirmation_event_id)
        assertEquals(0, left.confirmation_actor_count)
        assertEquals(left.confirmation_actor_count, right.confirmation_actor_count)
        assertEquals("status-a", left.latest_lifecycle_event_id)
        assertEquals("status-b", right.latest_lifecycle_event_id)
        assertEquals("status-a", left.latest_material_event_id)
        assertEquals("status-b", right.latest_material_event_id)
        assertNull(db.mapItemActionDao().findByActionId("g", "confirm-a"))
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "confirm-b")!!.materialization_state)
    }

    @Test fun resolutionCutoffExcludesPostResolutionSituationUpdatesRegardlessOfDeliveryOrder() {
        assertTrue(process(markerAdd("add-a", "owner", 10, "a")) is MapEventProcessingResult.Accepted)
        assertTrue(process(markerAdd("add-b", "owner", 10, "b")) is MapEventProcessingResult.Accepted)
        fun status(id: String, item: String) = EventFactory.mapSituationStatus(MapItemType.MARKER,
            app.peermesh.core.map.MapSituationStatusUpdate(MapActionMetadata(id, item, "peer", 20, 0),
                app.peermesh.core.map.MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null))
        fun update(id: String, item: String, type: MapSituationUpdateType) = EventFactory.mapSituationUpdate(
            MapItemType.MARKER, MapSituationUpdate(MapActionMetadata(id, item, "peer", 21, 0), "post", null, type))

        assertTrue(process(status("status-a", "a")) is MapEventProcessingResult.Accepted)
        assertEquals(MapEventRejectionReason.TARGET_RESOLVED,
            (process(update("still-a", "a", MapSituationUpdateType.STILL_ACTIVE)) as MapEventProcessingResult.Rejected).reason)
        assertTrue(process(update("still-b", "b", MapSituationUpdateType.STILL_ACTIVE)) is MapEventProcessingResult.Accepted)
        assertTrue(process(status("status-b", "b")) is MapEventProcessingResult.Accepted)
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "a")!!.latest_update_event_id)
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "b")!!.latest_update_event_id)

        assertTrue(process(markerAdd("add-c", "owner", 10, "c")) is MapEventProcessingResult.Accepted)
        assertTrue(process(markerAdd("add-d", "owner", 10, "d")) is MapEventProcessingResult.Accepted)
        assertTrue(process(status("status-c", "c")) is MapEventProcessingResult.Accepted)
        assertEquals(MapEventRejectionReason.TARGET_RESOLVED,
            (process(update("changed-c", "c", MapSituationUpdateType.SITUATION_CHANGED)) as MapEventProcessingResult.Rejected).reason)
        assertTrue(process(update("changed-d", "d", MapSituationUpdateType.SITUATION_CHANGED)) is MapEventProcessingResult.Accepted)
        assertTrue(process(status("status-d", "d")) is MapEventProcessingResult.Accepted)
        listOf("c" to "status-c", "d" to "status-d").forEach { (item, statusId) ->
            val state = db.mapItemDomainStateDao().find("g", "marker", item)!!
            assertNull(state.latest_update_event_id)
            assertEquals(statusId, state.latest_material_event_id)
        }
    }

    @Test fun resolutionCutoffUsesTheCompleteComparator() {
        assertTrue(process(markerAdd("add", "owner", 10, "m")) is MapEventProcessingResult.Accepted)
        db.userProfileDao().upsert(UserProfileEntity("g", "a", "A", null, 1, "a", 1, 0, "a"))
        db.userProfileDao().upsert(UserProfileEntity("g", "z", "Z", null, 1, "z", 1, 0, "z"))
        fun confirm(id: String, actor: String, lt: Long) = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata(id, "m", actor, 20, lt), null, null))
        // These arrive before the resolution, so the projection must apply order, not insertion.
        listOf(confirm("low-lamport", "peer", 1), confirm("low-actor", "a", 2),
            confirm("aaa", "peer", 2), confirm("high-lamport", "peer", 3),
            confirm("high-actor", "z", 2), confirm("zzz", "peer", 2)).forEach {
            assertTrue(process(it) is MapEventProcessingResult.Accepted)
        }
        assertTrue(process(EventFactory.mapSituationStatus(MapItemType.MARKER,
            app.peermesh.core.map.MapSituationStatusUpdate(MapActionMetadata("mid", "m", "peer", 20, 2),
                app.peermesh.core.map.MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null))) is MapEventProcessingResult.Accepted)
        val state = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        assertEquals("aaa", state.latest_confirmation_event_id)
        assertEquals(0, state.confirmation_actor_count)
    }

    @Test fun malformedAppliedActionSemanticsAbortNewProjectionWrites() {
        assertTrue(process(markerAdd("add-update", "owner", 10, "u")) is MapEventProcessingResult.Accepted)
        val update = EventFactory.mapSituationUpdate(MapItemType.MARKER,
            MapSituationUpdate(MapActionMetadata("valid-update", "u", "peer", 11, 0), "valid", null,
                MapSituationUpdateType.STILL_ACTIVE))
        assertTrue(process(update) is MapEventProcessingResult.Accepted)
        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET action_note = '' WHERE gid = 'g' AND action_id = 'valid-update'")
        val afterBadUpdate = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("after-bad-update", "u", "peer", 12, 0), null, null))
        assertTrue(process(afterBadUpdate) is MapEventProcessingResult.InvalidPersistedState)
        assertNull(db.eventsDao().findById("g", "after-bad-update"))
        assertNull(db.mapItemActionDao().findByActionId("g", "after-bad-update"))

        fun coordination(item: String) = EventFactory.mapMarkerAdd("add-$item", "owner", 10, 0, item, "l", 1.0, 2.0,
            null, null, null, "camp", domainExtension = MapCanonicalAddExtension(MapItemCategory.COORDINATION, null))
        assertTrue(process(coordination("n")) is MapEventProcessingResult.Accepted)
        assertTrue(process(EventFactory.mapCoordinationNote(MapItemType.MARKER,
            app.peermesh.core.map.MapCoordinationNote(MapActionMetadata("valid-note", "n", "peer", 11, 0), "valid"))) is MapEventProcessingResult.Accepted)
        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET action_note = '' WHERE gid = 'g' AND action_id = 'valid-note'")
        assertTrue(process(EventFactory.mapAcknowledgement(MapItemType.MARKER,
            MapCoordinationAcknowledgement(MapActionMetadata("after-bad-note", "n", "peer", 12, 0)))) is MapEventProcessingResult.InvalidPersistedState)
        assertNull(db.eventsDao().findById("g", "after-bad-note"))

        assertTrue(process(coordination("a")) is MapEventProcessingResult.Accepted)
        assertTrue(process(EventFactory.mapAcknowledgement(MapItemType.MARKER,
            MapCoordinationAcknowledgement(MapActionMetadata("valid-ack", "a", "peer", 11, 0)))) is MapEventProcessingResult.Accepted)
        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET action_note = 'corrupt' WHERE gid = 'g' AND action_id = 'valid-ack'")
        assertTrue(process(EventFactory.mapCoordinationNote(MapItemType.MARKER,
            app.peermesh.core.map.MapCoordinationNote(MapActionMetadata("after-bad-ack", "a", "peer", 12, 0), "valid"))) is MapEventProcessingResult.InvalidPersistedState)
        assertNull(db.eventsDao().findById("g", "after-bad-ack"))
    }

    @Test fun repeatedAcknowledgementsCountDistinctActorsAndNotesDoNotChangeProjection() {
        db.userProfileDao().upsert(UserProfileEntity("g", "peer2", "Peer2", null, 1, "peer2", 1, 0, "peer2"))
        val add = EventFactory.mapMarkerAdd("coord-add", "owner", 10, 0, "c", "l", 1.0, 2.0,
            null, null, null, "camp", domainExtension = MapCanonicalAddExtension(MapItemCategory.COORDINATION, null))
        assertTrue(process(add) is MapEventProcessingResult.Accepted)
        listOf("ack-1" to "peer", "ack-2" to "peer", "ack-3" to "peer2").forEach { (id, actor) ->
            assertTrue(process(EventFactory.mapAcknowledgement(MapItemType.MARKER,
                MapCoordinationAcknowledgement(MapActionMetadata(id, "c", actor, 11, 0)))) is MapEventProcessingResult.Accepted)
        }
        val before = db.mapItemDomainStateDao().find("g", "marker", "c")!!
        assertEquals(2, before.acknowledgement_actor_count)
        val note = EventFactory.mapCoordinationNote(MapItemType.MARKER,
            app.peermesh.core.map.MapCoordinationNote(MapActionMetadata("note", "c", "peer", 12, 0), "note"))
        assertTrue(process(note) is MapEventProcessingResult.Accepted)
        assertEquals(before, db.mapItemDomainStateDao().find("g", "marker", "c"))
    }

    @Test fun localRemovedActorIsRejectedWhenDistinctFromValidEventActor() {
        db.removedMemberDao().upsert(RemovedMemberEntity("g", "peer", "admin", "removed", 9, 9, 0, "admin"))
        val event = markerAdd("local-distinct-removed", "owner", 10, "local-distinct")
        val result = processor.process(MapEventProcessingRequest("g", event, "local", event.time.t, "peer"))
        assertEquals(MapEventRejectionReason.LOCAL_MEMBER_REMOVED, (result as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", event.id))
        assertNull(db.mapDao().findMarker("g", "local-distinct"))
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "local-distinct"))
    }

    @Test fun validLocalActorUsesNormalSemanticProcessing() {
        val event = markerAdd("valid-local", "owner", 10, "valid-local-marker")
        assertTrue(processor.process(MapEventProcessingRequest("g", event, "local", event.time.t, "owner")) is MapEventProcessingResult.Accepted)
        assertNotNull(db.eventsDao().findById("g", event.id))
    }

    @Test fun remoteRequestDoesNotRequireLocalParticipant() {
        val event = markerAdd("remote-no-local", "owner", 10, "remote-marker")
        assertTrue(processor.process(MapEventProcessingRequest("g", event, "mesh", event.time.t, null)) is MapEventProcessingResult.Accepted)
        assertNotNull(db.eventsDao().findById("g", event.id))
    }

    @Test fun canonicalDuplicatesAreClassifiedBeforeCurrentLocalRemoval() {
        val event = markerAdd("canonical-duplicate-removed", "owner", 10, "duplicate-marker")
        assertTrue(process(event) is MapEventProcessingResult.Accepted)
        val durable = db.eventsDao().findById("g", event.id)!!
        val marker = db.mapDao().findMarker("g", "duplicate-marker")!!
        val state = db.mapItemDomainStateDao().find("g", "marker", "duplicate-marker")!!
        db.removedMemberDao().upsert(RemovedMemberEntity("g", "owner", "admin", "removed", 11, 11, 0, "admin"))

        assertEquals(MapEventProcessingResult.ExactDuplicate(event.id), processLocal(event, "owner"))
        val changed = event.copy(data = buildJsonObject {
            (event.data as kotlinx.serialization.json.JsonObject).forEach { (key, value) -> put(key, value) }
            put("label", "changed")
        })
        assertEquals(MapEventProcessingResult.ConflictingDuplicate(event.id), processLocal(changed, "owner"))
        assertEquals(durable, db.eventsDao().findById("g", event.id))
        assertEquals(marker, db.mapDao().findMarker("g", "duplicate-marker"))
        assertEquals(state, db.mapItemDomainStateDao().find("g", "marker", "duplicate-marker"))
        assertEquals(MapEventProcessingResult.ExactDuplicate(event.id), process(event))
    }

    @Test fun dedicatedDuplicatesAndInvalidPairAreClassifiedBeforeCurrentLocalRemoval() {
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("dedicated-duplicate-removed", "missing", "peer", 20, 0), null, "original"))
        assertTrue(process(action) is MapEventProcessingResult.Accepted)
        val durable = db.eventsDao().findById("g", action.id)!!
        val actionRow = db.mapItemActionDao().findByActionId("g", "dedicated-duplicate-removed")!!
        db.removedMemberDao().upsert(RemovedMemberEntity("g", "peer", "admin", "removed", 21, 21, 0, "admin"))

        assertEquals(MapEventProcessingResult.ExactDuplicate(action.id), processLocal(action, "peer"))
        val changed = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("dedicated-duplicate-removed", "missing", "peer", 20, 0), null, "changed"))
        assertEquals(MapEventProcessingResult.ConflictingDuplicate(action.id), processLocal(changed, "peer"))
        assertEquals(durable, db.eventsDao().findById("g", action.id))
        assertEquals(actionRow, db.mapItemActionDao().findByActionId("g", "dedicated-duplicate-removed"))

        db.openHelper.writableDatabase.execSQL("DELETE FROM map_item_actions WHERE gid='g' AND action_id='dedicated-duplicate-removed'")
        assertTrue(processLocal(action, "peer") is MapEventProcessingResult.InvalidPersistedState)
    }

    @Test fun malformedLocalRequestDoesNotMasqueradeAsRemote() {
        val event = markerAdd("blank-local", "owner", 10, "blank-local-marker")
        assertEquals(MapEventRejectionReason.INVALID_REQUEST,
            (processor.process(MapEventProcessingRequest("g", event, "local", event.time.t, "")) as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", event.id))
    }

    private fun markerAdd(id: String, actor: String, time: Long, marker: String) = EventFactory.mapMarkerAdd(
        id, actor, time, 0, marker, "l", 1.0, 2.0, null, null, null, "risk",
        domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND),
    )
    private fun process(core: app.peermesh.core.model.EventCore) = processor.process(MapEventProcessingRequest("g", core, "test", core.time.t))
    private fun processLocal(core: app.peermesh.core.model.EventCore, localActor: String) =
        processor.process(MapEventProcessingRequest("g", core, "local", core.time.t, localActor))

    private fun persistPendingConfirmation(actionId: String, itemId: String) = persistPending(
        EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata(actionId, itemId, "peer", 11, 0), null, null)),
    )

    private fun persistPending(core: app.peermesh.core.model.EventCore) {
        val interaction = app.peermesh.core.map.MapItemEventCodec.decode(core)!!
        val entity = (DefaultMapItemActionEntityFactory.create(
            MapItemActionPersistenceInput("g", core, interaction), Sha256MapEventFingerprint.sha256(core),
            MapItemActionInitialState.PENDING_TARGET,
        ) as app.peermesh.data.db.PersistenceParseResult.Success).value
        assertTrue((db.mapItemActionDao().insertIgnore(entity) as app.peermesh.data.db.PersistenceWriteResult.Success).value != -1L)
    }

    private fun persistPendingWithDurable(core: app.peermesh.core.model.EventCore) {
        assertTrue(db.eventsDao().insert(MapEventEntityFactory.create(
            MapEventProcessingRequest("g", core, "test", core.time.t),
        )) != -1L)
        persistPending(core)
    }

    private fun assertReplayFails(itemId: String) {
        val coordinator = RoomTargetPendingReplayCoordinator(
            db.mapItemActionDao(), db.eventsDao(), db.mapItemDomainStateDao(), db.mapDao(),
            DefaultPendingActionSemanticEvaluator,
            RoomTargetProjectionRefresher(
                RoomMapItemActionProjectionDeriver(db.mapItemActionDao()), db.mapItemDomainStateDao(),
            ),
        )
        try {
            db.runInTransaction { coordinator.replay(PendingReplayTarget("g", MapItemType.MARKER, itemId)) }
            fail("expected canonical/domain invariant failure")
        } catch (expected: IllegalStateException) {
            assertEquals("canonical_domain_state_mismatch", expected.message)
        }
    }

    private fun assertReplayPairFails(itemId: String, detail: String) {
        val coordinator = RoomTargetPendingReplayCoordinator(
            db.mapItemActionDao(), db.eventsDao(), db.mapItemDomainStateDao(), db.mapDao(),
            DefaultPendingActionSemanticEvaluator,
            RoomTargetProjectionRefresher(
                RoomMapItemActionProjectionDeriver(db.mapItemActionDao()), db.mapItemDomainStateDao(),
            ),
        )
        try {
            db.runInTransaction { coordinator.replay(PendingReplayTarget("g", MapItemType.MARKER, itemId)) }
            fail("expected persisted action/event pair failure")
        } catch (expected: IllegalStateException) {
            assertEquals(detail, expected.message)
        }
    }

    @Test
    fun dedicatedActionBeforeMarkerPersistsPendingWithoutProjectionMutation() {
        val actions = listOf(
            EventFactory.mapConfirmation(MapItemType.MARKER, MapConfirmation(MapActionMetadata("p-confirm", "missing", "peer", 20, 1), MapInformationSource.UNCERTAIN, "seen")),
            EventFactory.mapSituationUpdate(MapItemType.MARKER, MapSituationUpdate(MapActionMetadata("p-update", "missing", "peer", 21, 2), "changed", MapInformationSource.FIRSTHAND, MapSituationUpdateType.SITUATION_CHANGED)),
            EventFactory.mapSituationStatus(MapItemType.MARKER, app.peermesh.core.map.MapSituationStatusUpdate(MapActionMetadata("p-status", "missing", "peer", 22, 3), app.peermesh.core.map.MapSituationLifecycleStatus.NO_LONGER_ACTIVE, "ended", MapInformationSource.REPORTED_BY_OTHERS)),
            EventFactory.mapAcknowledgement(MapItemType.MARKER, MapCoordinationAcknowledgement(MapActionMetadata("p-ack", "missing", "peer", 23, 4))),
            EventFactory.mapCoordinationNote(MapItemType.MARKER, app.peermesh.core.map.MapCoordinationNote(MapActionMetadata("p-note", "missing", "peer", 24, 5), "coordinate")),
        )
        actions.forEach { assertTrue(process(it) is MapEventProcessingResult.Accepted) }
        val rows = actions.map { db.mapItemActionDao().findByActionId("g", it.id)!! }
        rows.forEachIndexed { index, row ->
            assertEquals("pending_target", row.materialization_state)
            assertNull(row.rejection_reason)
            assertEquals(actions[index].id, row.event_id)
            assertEquals(actions[index].type, row.event_type)
            assertEquals("marker", row.item_type)
            assertEquals("missing", row.item_id)
            assertEquals("peer", row.actor_did)
            assertEquals(20L + index, row.action_t)
            assertEquals(1L + index, row.action_lt)
            assertEquals(Sha256MapEventFingerprint.sha256(actions[index]), row.event_fingerprint)
        }
        assertEquals("uncertain", rows[0].information_source)
        assertEquals("seen", rows[0].action_note)
        assertEquals("situation_changed", rows[1].update_disposition)
        assertEquals("no_longer_active", rows[2].target_lifecycle)
        assertEquals("coordinate", rows[4].action_note)
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "missing"))
        assertNull(db.mapDao().findMarker("g", "missing"))
    }

    @Test
    fun dedicatedActionBeforeAreaPersistsPendingWithoutProjectionMutation() {
        val action = EventFactory.mapConfirmation(MapItemType.AREA,
            MapConfirmation(MapActionMetadata("area-pending", "missing-area", "peer", 30, 6), null, null))
        assertTrue(process(action) is MapEventProcessingResult.Accepted)
        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "area-pending")!!.materialization_state)
        assertNull(db.mapItemDomainStateDao().find("g", "area", "missing-area"))
        assertNull(db.mapDao().findArea("g", "missing-area"))
    }

    @Test
    fun oppositeTargetTypeIsRejectedInsteadOfPersistedPending() {
        val area = EventFactory.mapAreaAdd("area-opposite", "owner", 10, 0, "same", "l", buildJsonArray {}, null, null, "risk",
            domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
        assertTrue(process(area) is MapEventProcessingResult.Accepted)
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("wrong-target", "same", "peer", 20, 0), null, null))
        val result = process(action) as MapEventProcessingResult.Rejected
        assertEquals(MapEventRejectionReason.TARGET_TYPE_CONFLICT, result.reason)
        assertNull(db.eventsDao().findById("g", "wrong-target"))
        assertNull(db.mapItemActionDao().findByActionId("g", "wrong-target"))
    }

    @Test
    fun pendingPersistenceRollbackLeavesNoDurableEventOrActionRow() {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_pending BEFORE INSERT ON map_item_actions BEGIN SELECT RAISE(ABORT, 'injected'); END")
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("rollback-pending", "absent", "peer", 20, 0), null, null))
        assertTrue(process(action) is MapEventProcessingResult.PersistenceFailure)
        assertNull(db.eventsDao().findById("g", "rollback-pending"))
        assertNull(db.mapItemActionDao().findByActionId("g", "rollback-pending"))
    }

    @Test fun pendingDuplicatesIgnoreMaterializationMetadataButRejectChangedWireContent() {
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("pending-dupe", "absent", "peer", 20, 0), null, "first"))
        assertTrue(process(action) is MapEventProcessingResult.Accepted)
        val first = db.mapItemActionDao().findByActionId("g", "pending-dupe")!!
        assertTrue(process(action) is MapEventProcessingResult.ExactDuplicate)
        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET materialization_state='applied' WHERE action_id='pending-dupe'")
        assertTrue(process(action) is MapEventProcessingResult.ExactDuplicate)
        db.openHelper.writableDatabase.execSQL("UPDATE map_item_actions SET materialization_state='rejected', rejection_reason='target_resolved' WHERE action_id='pending-dupe'")
        assertTrue(process(action) is MapEventProcessingResult.ExactDuplicate)
        val changed = action.copy(data = buildJsonObject {
            (action.data as kotlinx.serialization.json.JsonObject).forEach { (key, value) -> put(key, value) }
            put("action_note", "changed")
        })
        assertTrue(process(changed) is MapEventProcessingResult.ConflictingDuplicate)
        val stored = db.mapItemActionDao().findByActionId("g", "pending-dupe")!!
        assertEquals(first.action_note, stored.action_note)
        assertEquals(first.event_fingerprint, stored.event_fingerprint)
    }

    @Test fun pendingInsertionDoesNotMutateAnyExistingProjectionField() {
        assertTrue(process(markerAdd("orphan-add", "owner", 10, "orphan")) is MapEventProcessingResult.Accepted)
        db.openHelper.writableDatabase.execSQL("DELETE FROM map_markers WHERE gid='g' AND marker_id='orphan'")
        val before = db.mapItemDomainStateDao().find("g", "marker", "orphan")!!
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("orphan-pending", "orphan", "peer", 20, 0), null, null))
        assertTrue(process(action) is MapEventProcessingResult.Accepted)
        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "orphan-pending")!!.materialization_state)
        assertEquals(before, db.mapItemDomainStateDao().find("g", "marker", "orphan"))
    }

    @Test fun pendingDurableEventWithoutActionIsInvalidAndIsNotRepaired() {
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("pending-event-only", "missing-event-only", "peer", 20, 0), null, null))
        assertTrue(process(action) is MapEventProcessingResult.Accepted)
        db.openHelper.writableDatabase.execSQL("DELETE FROM map_item_actions WHERE gid='g' AND action_id='pending-event-only'")

        assertTrue(process(action) is MapEventProcessingResult.InvalidPersistedState)
        assertNotNull(db.eventsDao().findById("g", "pending-event-only"))
        assertNull(db.mapItemActionDao().findByActionId("g", "pending-event-only"))
    }

    @Test fun pendingActionWithoutDurableEventIsInvalidAndIsNotRepaired() {
        val action = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("pending-action-only", "missing-action-only", "peer", 20, 0), null, null))
        assertTrue(process(action) is MapEventProcessingResult.Accepted)
        assertEquals(1, db.eventsDao().deleteByEventId("g", "pending-action-only"))

        assertTrue(process(action) is MapEventProcessingResult.InvalidPersistedState)
        assertNull(db.eventsDao().findById("g", "pending-action-only"))
        assertNotNull(db.mapItemActionDao().findByActionId("g", "pending-action-only"))
    }

    @Test fun remoteMarkerUpdateBeforeAddIsDeferredThenReplayed() {
        val update = EventFactory.mapMarkerUpdate(
            "orphan-upd", "owner", 20, 1, "missing-marker", "updated-layer",
            3.0, 4.0, null, "U1", "replayed", "risk", rootEventId = "root-add",
        )
        assertEquals(MapEventProcessingResult.AcceptedDeferred("orphan-upd", "root-add"), process(update))
        assertNull(db.mapDao().findMarker("g", "missing-marker"))
        assertNotNull(db.eventsDao().findById("g", "orphan-upd"))
        assertEquals(
            "pending_missing_canonical",
            db.pendingCanonicalMapEventDao().find("g", "orphan-upd")!!.materialization_state,
        )

        assertTrue(process(markerAdd("root-add", "owner", 10, "missing-marker")) is MapEventProcessingResult.Accepted)

        val marker = db.mapDao().findMarker("g", "missing-marker")!!
        assertEquals("orphan-upd", marker.last_event_id)
        assertEquals("replayed", marker.note)
        assertEquals("applied", db.pendingCanonicalMapEventDao().find("g", "orphan-upd")!!.materialization_state)
    }

    @Test fun remoteAreaDeleteBeforeAddIsDeferredThenReplayed() {
        val delete = EventFactory.mapAreaDelete(
            "orphan-area-delete", "owner", 20, 1, "missing-area", rootEventId = "area-root",
        )
        assertTrue(process(delete) is MapEventProcessingResult.AcceptedDeferred)
        val add = EventFactory.mapAreaAdd(
            "area-root", "owner", 10, 0, "missing-area", "l", buildJsonArray {},
            null, null, "risk",
            domainExtension = MapCanonicalAddExtension(
                MapItemCategory.SITUATION,
                MapInformationSource.FIRSTHAND,
            ),
        )
        assertTrue(process(add) is MapEventProcessingResult.Accepted)
        assertEquals("deleted", db.mapDao().findArea("g", "missing-area")!!.status)
        assertEquals(
            "applied",
            db.pendingCanonicalMapEventDao().find("g", "orphan-area-delete")!!.materialization_state,
        )
    }

    @Test fun deferredMutationWithDifferentRootIsRejectedWithoutTouchingCanonicalItem() {
        val update = EventFactory.mapMarkerUpdate(
            "wrong-root-upd", "owner", 20, 1, "root-bound-marker", "l",
            9.0, 9.0, null, null, "wrong", "risk", rootEventId = "different-add",
        )
        assertTrue(process(update) is MapEventProcessingResult.AcceptedDeferred)
        assertTrue(process(markerAdd("actual-add", "owner", 10, "root-bound-marker")) is MapEventProcessingResult.Accepted)
        assertEquals("actual-add", db.mapDao().findMarker("g", "root-bound-marker")!!.last_event_id)
        val pending = db.pendingCanonicalMapEventDao().find("g", "wrong-root-upd")!!
        assertEquals("rejected", pending.materialization_state)
        assertEquals(MapEventRejectionReason.TARGET_TYPE_CONFLICT.persistedValue, pending.rejection_reason)
    }

    @Test fun roomSchemaVersionRemains53() {
        assertEquals(54, db.openHelper.writableDatabase.version)
    }

    @Test fun removedMembersCannotCreatePendingRowsAndLocalRemovedReasonIsPreserved() {
        db.removedMemberDao().upsert(RemovedMemberEntity("g", "peer", "admin", "removed", 9, 9, 0, "admin"))
        val remote = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("removed-pending", "absent", "peer", 20, 0), null, null))
        assertEquals(MapEventRejectionReason.ACTOR_NOT_MEMBER, (process(remote) as MapEventProcessingResult.Rejected).reason)
        val local = EventFactory.mapConfirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("local-removed", "absent", "peer", 20, 0), null, null))
        val localResult = processor.process(MapEventProcessingRequest("g", local, "test", 20, "peer"))
        assertEquals(MapEventRejectionReason.LOCAL_MEMBER_REMOVED, (localResult as MapEventProcessingResult.Rejected).reason)
        assertNull(db.eventsDao().findById("g", "removed-pending"))
        assertNull(db.mapItemActionDao().findByActionId("g", "removed-pending"))
    }

}
