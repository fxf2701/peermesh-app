package app.peermesh.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.data.db.entity.MapItemDomainStateEntity
import app.peermesh.data.map.MapActionHistoryCursor
import app.peermesh.data.map.MapItemIdentity
import app.peermesh.data.map.MapQueryResult
import app.peermesh.data.map.RoomMapItemActionQueryRepository
import app.peermesh.data.map.RoomMapItemDomainStateRepository
import app.peermesh.data.map.MapItemBatchLookupResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapItemM2b2bQueryTest {
    private lateinit var db: AppDatabase
    private val id = MapItemIdentity(MapItemType.MARKER, "i")
    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()

    @Test fun pendingQueriesAreAscendingBoundedIsolatedAndReadOnly() {
        insert("e4", 10, 2, "b", "pending_target")
        insert("e2", 10, 1, "a", "pending_target")
        insert("e1", 10, 1, "a", "pending_target")
        insert("e3", 10, 1, "b", "pending_target")
        insert("applied", 1, 0, "a", "applied")
        insert("rejected", 1, 0, "a", "rejected", reason = "invalid_action")
        insert("area", 1, 0, "a", "pending_target", itemType = "area")
        val repo = RoomMapItemActionQueryRepository(db.mapItemActionDao())
        assertEquals(listOf("e1", "e2", "e3", "e4"), success(repo.pendingTarget("g", id)).map { it.order.eventId })
        val first = success(repo.scanPending("g", null, 2))
        assertEquals(listOf("area", "e1"), first.map { it.order.eventId })
        assertEquals(listOf("e2", "e3", "e4"), success(repo.scanPending("g", first.last().order, 10)).map { it.order.eventId })
        assertEquals("pending_target", db.mapItemActionDao().findByEventId("g", "e1")!!.materialization_state)
        assertTrue(repo.scanPending("g", null, 0) is MapQueryResult.InvalidInput)
    }

    @Test fun latestMaterialAggregatesAndHistoryUseCompleteTuple() {
        insert("a", 10, 1, "a", "applied", action = "confirm")
        insert("b", 10, 1, "a", "applied", action = "confirm")
        insert("c", 10, 1, "b", "applied", action = "confirm")
        insert("still", 11, 0, "a", "applied", action = "add_update", disposition = "still_active")
        insert("changed", 10, 2, "z", "applied", action = "add_update", disposition = "situation_changed")
        insert("status", 12, 0, "a", "pending_target", action = "update_status")
        insert("ack1", 9, 0, "x", "applied", action = "acknowledge")
        insert("ack2", 10, 0, "x", "applied", action = "acknowledge")
        val repo = RoomMapItemActionQueryRepository(db.mapItemActionDao())
        assertEquals("still", success(repo.latestApplied("g", id))!!.order.eventId)
        assertEquals("c", success(repo.latestApplied("g", id, MapItemAction.CONFIRM))!!.order.eventId)
        assertEquals("changed", success(repo.latestMaterialChange("g", id))!!.order.eventId)
        assertEquals(2, success(repo.confirmationActorCount("g", id)))
        assertEquals(1, success(repo.confirmationActorCount("g", id, MapActionOrderKey(10, 1, "a", "b"))))
        assertEquals(0, success(repo.confirmationActorCount("g", id, MapActionOrderKey(10, 1, "b", "c"))))
        assertEquals(1, success(repo.acknowledgementActorCount("g", id)))
        val p1 = success(repo.appliedHistory("g", id, null, 3))
        val p2 = success(repo.appliedHistory("g", id, p1.nextCursor, 20))
        val all = p1.actions + p2.actions
        assertEquals(all.size, all.map { it.order.eventId }.distinct().size)
        assertEquals(listOf("still", "changed", "c", "b", "a", "ack2", "ack1"), all.map { it.order.eventId })
        assertNull(success(repo.latestApplied("g", MapItemIdentity(MapItemType.MARKER, "missing"))))
    }

    @Test fun latestTypedSupportsSituationUpdateAndLifecycleStatus() {
        insert("update-old", 10, 0, "a", "applied", action = "add_update", disposition = "still_active")
        insert("update-new", 10, 0, "b", "applied", action = "add_update", disposition = "situation_changed")
        insert("life-applied", 11, 0, "a", "applied", action = "update_status")
        insert("life-pending", 12, 0, "a", "pending_target", action = "update_status")
        val repo = RoomMapItemActionQueryRepository(db.mapItemActionDao())
        assertEquals("update-new", success(repo.latestApplied("g", id, MapItemAction.ADD_UPDATE))!!.order.eventId)
        assertEquals("life-applied", success(repo.latestApplied("g", id, MapItemAction.UPDATE_STATUS))!!.order.eventId)
    }

    @Test fun criticalPlansUseSchema52IndexesWithoutUnboundedActionScan() {
        val plans = listOf(
            "SELECT * FROM map_item_actions WHERE gid='g' AND item_type='marker' AND item_id='i' AND materialization_state='pending_target' ORDER BY action_t,action_lt,actor_did,event_id",
            "SELECT * FROM map_item_actions WHERE gid='g' AND item_type='marker' AND item_id='i' AND materialization_state='applied' ORDER BY action_t DESC,action_lt DESC,actor_did DESC,event_id DESC LIMIT 1",
            "SELECT * FROM map_item_actions WHERE gid='g' AND item_type='marker' AND item_id='i' AND materialization_state='applied' ORDER BY action_t DESC,action_lt DESC,actor_did DESC,event_id DESC LIMIT 10",
            "SELECT * FROM map_item_actions WHERE materialization_state='pending_target' AND gid='g' ORDER BY action_t,action_lt,actor_did,event_id LIMIT 10",
            "SELECT COUNT(DISTINCT actor_did) FROM map_item_actions WHERE gid='g' AND item_type='marker' AND item_id='i' AND materialization_state='applied' AND action_type='confirm'",
        ).map(::plan)
        plans.forEach { details ->
            assertTrue(details, details.contains("INDEX index_map_item_actions_"))
            assertFalse(details, details.contains("SCAN map_item_actions"))
        }
    }

    @Test fun domainRepositoryKeepsMixedIdentityMissingAndInvalidOutcomesSeparate() {
        val dao = db.mapItemDomainStateDao()
        dao.insertIgnore(domain("marker", "same"))
        dao.insertIgnore(domain("area", "same"))
        dao.insertIgnore(domain("marker", "bad"))
        db.openHelper.writableDatabase.execSQL("UPDATE map_item_domain_state SET temporal_origin='future' WHERE item_id='bad'")
        val repository = RoomMapItemDomainStateRepository(dao, chunkSize = 1)
        val marker = MapItemIdentity(MapItemType.MARKER, "same")
        val area = MapItemIdentity(MapItemType.AREA, "same")
        val bad = MapItemIdentity(MapItemType.MARKER, "bad")
        val missing = MapItemIdentity(MapItemType.AREA, "missing")
        val result: MapItemBatchLookupResult = success(repository.findBatch("g", listOf(marker, marker, area, bad, missing)))
        assertEquals(setOf(marker, area), result.found.keys)
        assertEquals(setOf(bad), result.invalid.keys)
        assertEquals(setOf(missing), result.missing)
    }

    private fun plan(sql: String): String = db.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql")).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }.joinToString(" | ")
    }
    private fun insert(event: String, t: Long, lt: Long, actor: String, state: String, action: String = "confirm", reason: String? = null,
        itemType: String = "marker", disposition: String? = null) {
        val row = MapItemActionEntity("g", event, event, "MAP.ITEM", 1, itemType, "i", action, actor, t, lt,
            null, null, disposition, null, "sha-$event", state, reason)
        assertTrue(db.mapItemActionDao().insertIgnore(row) is PersistenceWriteResult.Success)
    }
    private fun domain(type: String, item: String) = MapItemDomainStateEntity(
        "g", type, item, null, "unresolved", null, null, "legacy_row_backfill", "did:o",
        null, null, null, null, null, null, null, null, "legacy_no_expiry", 0, 0,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
    )
    @Suppress("UNCHECKED_CAST") private fun <T> success(result: MapQueryResult<T>): T = (result as MapQueryResult.Success<T>).value
}
