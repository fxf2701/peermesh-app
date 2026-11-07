package app.peermesh.data.map.convergence

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.*
import app.peermesh.data.map.local.LocalMapWriteOutcome
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalRemotePendingConvergenceInstrumentationTest {
    @Test fun remotePendingThenLocalAdd_andLocalPendingThenRemoteAdd_produceEquivalentState() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val action = LocalRemoteDedicatedActionConvergenceInstrumentationTest.confirm("pending", "peer", 11, 0)
            val add = LocalRemoteCanonicalConvergenceInstrumentationTest.markerAdd("add", "owner", 10, "m")
            val pending = f.processRemotelyOnLocalDbDetailed(action)
            assertTrue(pending.accepted); assertEquals(1, pending.invocationCount); assertTrue(pending.result is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            assertTrue(f.processLocallyOnRemoteDb(action) is LocalMapWriteOutcome.AcceptedNew)
            assertEquals("pending_target", f.localDb.mapItemActionDao().findByActionId("g", "pending")!!.materialization_state)
            assertEquals("pending_target", f.remoteDb.mapItemActionDao().findByActionId("g", "pending")!!.materialization_state)
            assertNull(f.localDb.mapItemDomainStateDao().find("g", "marker", "m"))
            assertNull(f.remoteDb.mapItemDomainStateDao().find("g", "marker", "m"))
            assertTrue(f.processLocallyEquivalent(add) is LocalMapWriteOutcome.AcceptedNew)
            val result = f.processRemotelyOnRemoteDbDetailed(add)
            assertTrue(result.accepted); assertEquals(1, result.invocationCount); assertTrue(result.result is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            val local = f.captureLocalTarget(MapTargetIdentity("g", "marker", "m")); val remote = f.captureRemoteTarget(MapTargetIdentity("g", "marker", "m"))
            assertEquivalent(local, remote); assertEquals(1, local.actions.size); assertEquals("applied", local.actions.single().materializationState)
            assertEquals(1, local.domainState!!.confirmationActorCount); assertEquals(0, local.domainState.acknowledgementActorCount)
            assertEquals("pending", local.domainState.latestConfirmation!!.eventId); assertEquals("active", local.domainState.lifecycle)
        }
    }

    @Test fun markerAndAreaPendingSequences_areSourceIndependent() {
        listOf(MapItemType.MARKER, MapItemType.AREA).forEach { type ->
            MapEventConvergenceFixture().use { f ->
                f.seedEquivalentMembers("owner", "peer")
                val id = type.name.lowercase()
                val action = EventFactory.mapConfirmation(type, MapConfirmation(MapActionMetadata("pending-$id", id, "peer", 11, 0), null, null))
                f.processLocallyEquivalent(action); f.processRemotely(action)
                assertEquals("pending_target", f.localDb.mapItemActionDao().findByActionId("g", "pending-$id")!!.materialization_state)
                val add = if (type == MapItemType.MARKER) LocalRemoteCanonicalConvergenceInstrumentationTest.markerAdd("add", "owner", 10, id)
                    else LocalRemoteCanonicalConvergenceInstrumentationTest.areaAdd("add", "owner", 10, id)
                f.processLocallyEquivalent(add); f.processRemotely(add)
                assertEquivalent(f.captureLocalTarget(MapTargetIdentity("g", id, id)), f.captureRemoteTarget(MapTargetIdentity("g", id, id)))
            }
        }
    }

    @Test fun oppositePendingArrivalOrders_produceIdenticalTerminalHistoryAndProjection() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val a = LocalRemoteDedicatedActionConvergenceInstrumentationTest.confirm("a", "peer", 11, 0)
            val b = LocalRemoteDedicatedActionConvergenceInstrumentationTest.confirm("b", "owner", 12, 0)
            f.processRemotely(a); f.processRemotely(b); f.processLocallyEquivalent(b); f.processLocallyEquivalent(a)
            val add = LocalRemoteCanonicalConvergenceInstrumentationTest.markerAdd("add-order", "owner", 10, "m")
            f.processLocallyEquivalent(add); f.processRemotely(add)
            val local = f.captureLocalTarget(MapTargetIdentity("g", "marker", "m")); val remote = f.captureRemoteTarget(MapTargetIdentity("g", "marker", "m"))
            assertEquivalent(local, remote); assertEquals(2, local.actions.size); assertTrue(local.actions.all { it.materializationState == "applied" && it.rejectionReason == null })
            assertEquals(2, local.domainState!!.confirmationActorCount); assertEquals("b", local.domainState.latestConfirmation!!.eventId); assertEquals("active", local.domainState.lifecycle)
        }
    }

    @Test fun targetTriggeredReplay_materializesPendingActionBeforeProjectionRead() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val action = LocalRemoteDedicatedActionConvergenceInstrumentationTest.confirm("pending-live", "peer", 11, 0)
            f.processRemotely(action); f.processLocallyEquivalent(action)
            assertEquals("pending_target", f.localDb.mapItemActionDao().findByActionId("g", "pending-live")!!.materialization_state)
            val add = LocalRemoteCanonicalConvergenceInstrumentationTest.markerAdd("add-live", "owner", 10, "m")
            f.processLocallyEquivalent(add); f.processRemotely(add)
            assertEquals("applied", f.localDb.mapItemActionDao().findByActionId("g", "pending-live")!!.materialization_state)
            assertEquals(1, f.localDb.mapItemDomainStateDao().find("g", "marker", "m")!!.confirmation_actor_count)
        }
    }

    @Test fun stillMissingTargets_remainPendingAcrossLocalAndRemotePaths() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val missing = LocalRemoteDedicatedActionConvergenceInstrumentationTest.confirm("missing", "peer", 11, 0)
            f.processLocallyEquivalent(missing); f.processRemotely(missing)
            assertEquals("pending_target", f.localDb.mapItemActionDao().findByActionId("g", "missing")!!.materialization_state)
            assertEquals("pending_target", f.remoteDb.mapItemActionDao().findByActionId("g", "missing")!!.materialization_state)
        }
    }

}
