package app.peermesh.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.data.db.entity.MapItemDomainStateEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapItemM2b2aDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun domainLookupBatchAndInsertIgnoreUseFullIdentity() {
        val dao = db.mapItemDomainStateDao()
        assertInserted(dao.insertIgnore(domain("marker", owner = "first")))
        assertInserted(dao.insertIgnore(domain("area", owner = "area-owner")))
        assertEquals("first", dao.find("g", "marker", "same")!!.owner_did)
        assertEquals("area-owner", dao.find("g", "area", "same")!!.owner_did)
        assertEquals(listOf("marker"), dao.findByItemIds("g", "marker", listOf("same", "missing")).map { it.item_type })
        assertIgnored(dao.insertIgnore(domain("marker", owner = "replacement")))
        assertEquals("first", dao.find("g", "marker", "same")!!.owner_did)
    }

    @Test fun targetedUpdatesChangeOnlyMaterializedFieldsAndRejectPartialTuple() {
        val dao = db.mapItemDomainStateDao()
        assertInserted(dao.insertIgnore(domain("marker", owner = "foundation")))
        assertRows(1, dao.updateLifecycle("g", MapItemType.MARKER, "same", MapSituationLifecycleStatus.NO_LONGER_ACTIVE, order(20, 2, "did:x", "life")))
        assertRows(1, dao.updateLatestConfirmation("g", MapItemType.MARKER, "same", order(21, 3, "did:y", "confirm")))
        assertRows(1, dao.updateLatestSituationUpdate("g", MapItemType.MARKER, "same", order(22, 4, "did:z", "update")))
        assertRows(1, dao.updateLatestMaterialChange("g", MapItemType.MARKER, "same", order(23, 5, "did:m", "material")))
        assertRows(1, dao.updateAggregateCounts("g", MapItemType.MARKER, "same", 4, 5))
        assertRejected(dao.updateLatestConfirmation("g", MapItemType.MARKER, "same", order(30, -1, "", "")))
        val row = dao.find("g", "marker", "same")!!
        assertEquals("foundation", row.owner_did)
        assertEquals("situation", row.category)
        assertEquals("explicit_domain_v1", row.category_origin)
        assertEquals("canonical", row.canonical_add_event_id)
        assertEquals("no_longer_active", row.situation_lifecycle)
        assertEquals("confirm", row.latest_confirmation_event_id)
        assertEquals(4, row.confirmation_actor_count)
        assertEquals(5, row.acknowledgement_actor_count)
    }

    @Test fun actionInsertIgnoreLookupsAndFingerprintsPreserveFirstContent() {
        val dao = db.mapItemActionDao()
        assertInserted(dao.insertIgnore(action(fingerprint = "sha-first", note = "first")))
        assertIgnored(dao.insertIgnore(action(fingerprint = "sha-first", note = "first")))
        assertIgnored(dao.insertIgnore(action(fingerprint = "sha-second", note = "replacement")))
        assertEquals("first", dao.findByActionId("g", "event")!!.action_note)
        assertEquals("sha-first", dao.findByEventId("g", "event")!!.event_fingerprint)
        assertEquals("sha-first", dao.findByActionId("g", "event")!!.event_fingerprint)

        // A caller can compare either exact or conflicting later fingerprints without DAO policy.
        assertEquals("sha-first", dao.findByActionId("g", "event")!!.event_fingerprint)
        assertTrue(dao.findByActionId("g", "event")!!.event_fingerprint != "sha-conflict")
        assertRejected(dao.insertIgnore(action(actionId = "other", eventId = "event", fingerprint = "sha-other")))
        assertNull(dao.findByActionId("g", "other"))
    }

    @Test fun pendingToAppliedHasOneWinnerAndClearsReason() {
        val dao = db.mapItemActionDao()
        assertInserted(dao.insertIgnore(action()))
        assertRows(1, dao.transitionPendingToApplied("g", "event"))
        assertRows(0, dao.transitionPendingToApplied("g", "event"))
        assertRows(0, dao.transitionPendingToRejected("g", "event", "late_reject"))
        val row = dao.findByActionId("g", "event")!!
        assertEquals("applied", row.materialization_state)
        assertNull(row.rejection_reason)
    }

    @Test fun pendingToRejectedHasOneWinnerAndRequiresStableReason() {
        val dao = db.mapItemActionDao()
        assertInserted(dao.insertIgnore(action()))
        assertRejected(dao.transitionPendingToRejected("g", "event", ""))
        assertRejected(dao.transitionPendingToRejected("g", "event", "Localized reason"))
        assertRows(1, dao.transitionPendingToRejected("g", "event", "unknown_target"))
        assertRows(0, dao.transitionPendingToRejected("g", "event", "again"))
        assertRows(0, dao.transitionPendingToApplied("g", "event"))
        val row = dao.findByEventId("g", "event")!!
        assertEquals("rejected", row.materialization_state)
        assertEquals("unknown_target", row.rejection_reason)
    }

    @Test fun existingMarkerAndAreaDaoBehaviorIsUnchanged() {
        val map = db.mapDao()
        map.upsertMarker(MapMarkerEntity("g", "m", "l", 1.0, 2.0, null, "marker", null, "risk", 1,
            "active", 10, 10, "did:o", expires_at = 100, last_event_id = "em", last_event_t = 10,
            last_event_lt = 0, last_event_actor = "did:o"))
        map.upsertArea(MapAreaEntity("g", "a", "l", "[]", "area", null, "risk", 1, "active", 10, 10,
            "did:o", expires_at = 100, last_event_id = "ea", last_event_t = 10, last_event_lt = 0,
            last_event_actor = "did:o"))
        assertEquals("marker", map.findMarker("g", "m")!!.label)
        assertEquals("area", map.findArea("g", "a")!!.label)
        assertEquals(listOf("m"), map.listActiveMarkers("g", 50, 10).map { it.marker_id })
        assertEquals(listOf("a"), map.listActiveAreas("g", 50, 10).map { it.area_id })
    }

    private fun domain(itemType: String, owner: String) = MapItemDomainStateEntity(
        "g", itemType, "same", "situation", "explicit_domain_v1", "firsthand", 1,
        "explicit_domain_v1", owner, "canonical", 10, 0, owner, "active", 100, 50, 100,
        "explicit_domain_v1", 0, 0, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null,
    )

    @Test fun publicInsertsRejectInvalidRowsBeforeSQLite() {
        val actions = db.mapItemActionDao()
        listOf(
            action(actionId = "wrong", eventId = "event"), action(action_t = 0), action(action_lt = -1),
            action(itemType = "future"), action(actionType = "future"), action(state = "future"),
            action(version = 2), action(state = "rejected"), action(state = "applied", rejectionReason = "unknown_target"),
        ).forEach { assertRejected(actions.insertIgnore(it)) }
        assertNull(actions.findByActionId("g", "wrong"))
        assertNull(actions.findByActionId("g", "event"))

        val states = db.mapItemDomainStateDao()
        assertRejected(states.insertIgnore(domain("marker", "o").copy(canonical_add_actor = null)))
        assertRejected(states.insertIgnore(domain("marker", "o").copy(confirmation_actor_count = -1)))
        assertRejected(states.insertIgnore(domain("marker", "o").copy(expires_at = null, interaction_until = 10)))
        assertRejected(states.insertIgnore(domain("marker", "o").copy(domain_version = 2)))
        assertRejected(states.insertIgnore(domain("marker", "o").copy(
            category = null, category_origin = "unresolved", domain_version = 1,
            state_origin = "explicit_domain_v1",
        )))
        assertNull(states.find("g", "marker", "same"))
    }

    private fun action(actionId: String = "event", eventId: String = actionId, fingerprint: String = "sha", note: String? = null,
        action_t: Long = 10, action_lt: Long = 0, itemType: String = "marker", actionType: String = "confirm",
        state: String = "pending_target", version: Int = 1, rejectionReason: String? = null,
    ) = MapItemActionEntity("g", actionId, eventId, "MAP.ITEM.CONFIRM", version, itemType, "same", actionType,
        "did:a", action_t, action_lt, null, note, null, null, fingerprint, state, rejectionReason)

    private fun order(t: Long, lt: Long, actor: String, event: String) = MapActionOrderKey(t, lt, actor, event)
    private fun assertInserted(result: PersistenceWriteResult<Long>) = assertTrue(result is PersistenceWriteResult.Success && result.value >= 0)
    private fun assertIgnored(result: PersistenceWriteResult<Long>) = assertEquals(PersistenceWriteResult.Success(-1L), result)
    private fun assertRows(expected: Int, result: PersistenceWriteResult<Int>) = assertEquals(PersistenceWriteResult.Success(expected), result)
    private fun assertRejected(result: PersistenceWriteResult<*>) = assertTrue(result is PersistenceWriteResult.Rejected)
}
