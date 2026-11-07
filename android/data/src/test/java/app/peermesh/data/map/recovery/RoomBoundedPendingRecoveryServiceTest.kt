package app.peermesh.data.map.recovery

import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.dao.MapDao
import app.peermesh.data.db.dao.MapItemActionDao
import app.peermesh.data.db.dao.MapItemDomainStateDao
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.data.db.entity.MapItemDomainStateEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import app.peermesh.data.map.replay.TargetPendingReplayCoordinator
import app.peermesh.data.map.replay.TargetPendingReplayResult
import app.peermesh.data.map.replay.ProjectionRefreshOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class RoomBoundedPendingRecoveryServiceTest {
    private lateinit var db: AppDatabase
    private lateinit var actions: MapItemActionDao
    private lateinit var maps: MapDao
    private lateinit var states: MapItemDomainStateDao
    private val replayed = mutableListOf<String>()
    private var transactionCount = 0
    private lateinit var service: RoomBoundedPendingRecoveryService

    @Before fun setUp() {
        db = Mockito.mock(AppDatabase::class.java)
        actions = Mockito.mock(MapItemActionDao::class.java)
        maps = Mockito.mock(MapDao::class.java)
        states = Mockito.mock(MapItemDomainStateDao::class.java)
        Mockito.`when`(db.mapItemActionDao()).thenReturn(actions)
        Mockito.`when`(db.mapDao()).thenReturn(maps)
        Mockito.`when`(db.mapItemDomainStateDao()).thenReturn(states)
        val coordinator = TargetPendingReplayCoordinator { target ->
            replayed += "${target.itemType}:${target.itemId}"
            TargetPendingReplayResult(0, 0, 0, 0, ProjectionRefreshOutcome.NotRequired)
        }
        service = RoomBoundedPendingRecoveryService(db, coordinator) { block ->
            transactionCount++
            block()
        }
    }

    @Test fun `rejects blank group nonpositive limit and invalid complete cursor`() {
        assertTrue(service.recover(BoundedPendingRecoveryRequest(" ", 1)) is BoundedPendingRecoveryResult.InvalidRequest)
        assertTrue(service.recover(BoundedPendingRecoveryRequest("g", 0)) is BoundedPendingRecoveryResult.InvalidRequest)
        assertTrue(service.recover(BoundedPendingRecoveryRequest(
            "g", 1, PendingRecoveryCursor(MapActionOrderKey(1, 0, "", "e")),
        )) is BoundedPendingRecoveryResult.InvalidRequest)
        Mockito.verifyNoInteractions(actions)
    }

    @Test fun `uses final scanned row as complete continuation cursor`() {
        Mockito.`when`(actions.scanPending("g", null, null, null, null, 2)).thenReturn(listOf(
            row("marker", "missing", 1, "a"), row("marker", "missing", 2, "b"),
        ))
        val result = completed(service.recover(BoundedPendingRecoveryRequest("g", 2)))
        assertEquals(MapActionOrderKey(2, 0, "actor", "b"), result.nextCursor?.order)
        assertEquals(2, result.scannedRowCount)
        assertEquals(1, result.discoveredTargetCount)
    }

    @Test fun `deduplicates exact targets preserving first appearance and item type`() {
        Mockito.`when`(actions.scanPending("g", null, null, null, null, 4)).thenReturn(listOf(
            row("marker", "same", 1, "a"), row("marker", "same", 2, "b"),
            row("area", "same", 3, "c"), row("marker", "later", 4, "d"),
        ))
        presentMarker("same")
        presentMarker("later")
        Mockito.`when`(maps.findArea("g", "same")).thenReturn(Mockito.mock(app.peermesh.data.db.entity.MapAreaEntity::class.java))
        Mockito.`when`(states.find("g", "area", "same")).thenReturn(Mockito.mock(MapItemDomainStateEntity::class.java))

        val result = completed(service.recover(BoundedPendingRecoveryRequest("g", 4)))
        assertEquals(4, result.scannedRowCount)
        assertEquals(3, result.discoveredTargetCount)
        assertEquals(listOf("MARKER:later"), replayed)
        assertEquals(1, result.completedTargetCount)
        assertEquals(2, result.failedTargetCount)
    }

    @Test fun `still missing target is skipped and does not block present sibling`() {
        Mockito.`when`(actions.scanPending("g", null, null, null, null, 2)).thenReturn(listOf(
            row("marker", "missing", 1, "a"), row("marker", "present", 2, "b"),
        ))
        presentMarker("present")
        val result = completed(service.recover(BoundedPendingRecoveryRequest("g", 2)))
        assertEquals(1, result.skippedMissingTargetCount)
        assertEquals(1, result.completedTargetCount)
        assertEquals(listOf("MARKER:present"), replayed)
    }

    @Test fun `one replay failure rolls back only its target and later sibling continues`() {
        Mockito.`when`(actions.scanPending("g", null, null, null, null, 3)).thenReturn(listOf(
            row("marker", "first", 1, "a"), row("marker", "bad", 2, "b"), row("marker", "last", 3, "c"),
        ))
        listOf("first", "bad", "last").forEach(::presentMarker)
        service = RoomBoundedPendingRecoveryService(db, TargetPendingReplayCoordinator { target ->
            replayed += target.itemId
            if (target.itemId == "bad") error("invalid pending history")
            TargetPendingReplayResult(0, 0, 0, 0, ProjectionRefreshOutcome.NotRequired)
        }) { block -> transactionCount++; block() }
        val result = completed(service.recover(BoundedPendingRecoveryRequest("g", 3)))
        assertEquals(listOf("first", "bad", "last"), replayed)
        assertEquals(2, result.completedTargetCount)
        assertEquals(1, result.failedTargetCount)
        assertEquals(PendingRecoveryFailureReason.INVALID_PERSISTED_TARGET_HISTORY, result.failures.single().reason)
        assertEquals(3, transactionCount)
    }

    private fun presentMarker(id: String) {
        Mockito.`when`(maps.findMarker("g", id)).thenReturn(marker(id))
        Mockito.`when`(states.find("g", "marker", id)).thenReturn(Mockito.mock(MapItemDomainStateEntity::class.java))
    }

    private fun completed(result: BoundedPendingRecoveryResult) =
        result as BoundedPendingRecoveryResult.Completed

    private fun row(type: String, id: String, t: Long, event: String) = MapItemActionEntity(
        "g", event, event, "event", 1, type, id, "confirm", "actor", t, 0,
        null, null, null, null, "fingerprint", "pending_target", null,
    )

    private fun marker(id: String) = MapMarkerEntity(
        "g", id, "layer", 0.0, 0.0, null, null, null, "situation", 1, "active",
        1, 1, "owner", expires_at = null, last_event_id = "add", last_event_t = 1,
        last_event_lt = 0, last_event_actor = "owner",
    )
}
