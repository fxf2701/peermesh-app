package app.peermesh.data.map.convergence

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.model.EventCore
import app.peermesh.data.map.local.LocalMapWriteOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapCrossSourceDuplicateAndFailureRegressionInstrumentationTest {
    @Test fun localFirstThenRemoteExactDuplicate_isIdempotent() = duplicate(localFirst = true)
    @Test fun remoteFirstThenLocalExactDuplicate_isIdempotent() = duplicate(localFirst = false)

    @Test fun multipleRemoteSources_useTheSameDurableDuplicateClassifier() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner")
            val event = LocalRemoteCanonicalConvergenceInstrumentationTest.markerAdd("same", "owner", 10, "m")
            val mesh = f.processRemotelyOnRemoteDbDetailed(event, "mesh")
            assertTrue(mesh.accepted); assertEquals(1, mesh.invocationCount); assertTrue(mesh.result is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            val before = f.captureRemoteTarget(target())
            val relay = f.processRemotelyOnRemoteDbDetailed(event, "relay")
            assertFalse(relay.accepted); assertEquals(1, relay.invocationCount); assertTrue(relay.result is app.peermesh.data.map.processing.MapEventProcessingResult.ExactDuplicate)
            assertEquals(before, f.captureRemoteTarget(target()))
        }
    }

    @Test fun conflictingDuplicate_isNonDestructiveInBothSourceOrders() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner")
            val event = LocalRemoteCanonicalConvergenceInstrumentationTest.markerAdd("same", "owner", 10, "m")
            assertTrue(f.processLocallyEquivalent(event) is LocalMapWriteOutcome.AcceptedNew); val before = f.captureLocalTarget(target())
            val changed = changed(event)
            val remoteConflict = f.processRemotelyOnLocalDbDetailed(changed)
            assertFalse(remoteConflict.accepted); assertTrue(remoteConflict.result is app.peermesh.data.map.processing.MapEventProcessingResult.ConflictingDuplicate); assertEquals(before, f.captureLocalTarget(target()))
            val remoteBefore = f.captureLocalTarget(target())
            val remote = f.processRemotelyOnLocalDbDetailed(event)
            assertFalse(remote.accepted); assertEquals(1, remote.invocationCount); assertTrue(remote.result is app.peermesh.data.map.processing.MapEventProcessingResult.ExactDuplicate)
            val conflict = f.processRemotelyOnLocalDbDetailed(changed)
            assertFalse(conflict.accepted); assertEquals(1, conflict.invocationCount); assertTrue(conflict.result is app.peermesh.data.map.processing.MapEventProcessingResult.ConflictingDuplicate)
            assertEquals(remoteBefore, f.captureLocalTarget(target()))
            val remoteOriginal = f.processRemotelyOnRemoteDbDetailed(event)
            assertTrue(remoteOriginal.result is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            val beforeReverse = f.captureRemoteTarget(target())
            assertTrue(f.processLocallyOnRemoteDb(changed) is LocalMapWriteOutcome.ConflictingDuplicate)
            assertEquals(beforeReverse, f.captureRemoteTarget(target()))
        }
    }

    @Test fun exactDuplicateAfterMembershipRemoval_remainsNonMutating() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner")
            val event = LocalRemoteCanonicalConvergenceInstrumentationTest.markerAdd("same-removed", "owner", 10, "m")
            assertTrue(f.processLocallyEquivalent(event) is LocalMapWriteOutcome.AcceptedNew)
            f.localDb.removedMemberDao().upsert(app.peermesh.data.db.entity.RemovedMemberEntity("g", "owner", "admin", "removed", 11, 11, 0, "admin"))
            val before = f.captureLocalTarget(target())
            val duplicate = f.processRemotelyOnLocalDbDetailed(event)
            assertFalse(duplicate.accepted); assertEquals(1, duplicate.invocationCount); assertTrue(duplicate.result is app.peermesh.data.map.processing.MapEventProcessingResult.ExactDuplicate)
            assertEquals(before, f.captureLocalTarget(target()))
            assertEquals(1, f.localDb.eventsDao().countByType("g", event.type))
        }
    }

    private fun duplicate(localFirst: Boolean) {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner")
            val event = LocalRemoteCanonicalConvergenceInstrumentationTest.markerAdd("same", "owner", 10, "m")
            if (localFirst) {
            assertTrue(f.processLocallyEquivalent(event) is LocalMapWriteOutcome.AcceptedNew); val before = f.captureLocalTarget(target())
            val remote = f.processRemotelyOnLocalDbDetailed(event)
            assertFalse(remote.accepted); assertEquals(1, remote.invocationCount); assertTrue(remote.result is app.peermesh.data.map.processing.MapEventProcessingResult.ExactDuplicate)
            assertEquals(before, f.captureLocalTarget(target()))
            } else {
                val original = f.processRemotelyOnRemoteDbDetailed(event)
                assertTrue(original.accepted); assertEquals(1, original.invocationCount); assertTrue(original.result is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
                val before = f.captureRemoteTarget(target())
                assertTrue(f.processLocallyOnRemoteDb(event) is LocalMapWriteOutcome.ExactDuplicate)
                assertEquals(before, f.captureRemoteTarget(target()))
            }
        }
    }

    private fun changed(core: EventCore) = core.copy(data = buildJsonObject { (core.data as JsonObject).forEach { (k,v) -> put(k,v) }; put("label", "changed") })
    private fun target() = MapTargetIdentity("g", "marker", "m")
}
