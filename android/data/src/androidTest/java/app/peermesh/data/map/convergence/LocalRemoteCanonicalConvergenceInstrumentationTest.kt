package app.peermesh.data.map.convergence

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.model.EventCore
import app.peermesh.data.db.entity.RemovedMemberEntity
import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.processing.MapEventProcessingResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalRemoteCanonicalConvergenceInstrumentationTest {
    @Test fun markerAndAreaAddUpdateDelete_produceEquivalentPersistedState() {
        listOf("marker", "area").forEach { type ->
            MapEventConvergenceFixture().use { f ->
                f.seedEquivalentMembers("owner")
                val events = if (type == "marker") listOf(
                    markerAdd("add", "owner", 10, "item"),
                    EventFactory.mapMarkerUpdate("upd", "owner", 11, 1, "item", "layer", 3.0, 4.0, null, "changed", "note", "risk"),
                    EventFactory.mapMarkerDelete("del", "owner", 12, 2, "item"),
                ) else listOf(
                    areaAdd("add", "owner", 10, "item"),
                    EventFactory.mapAreaUpdate("upd", "owner", 11, 1, "item", "layer", geometry(2), "changed", "note", "risk"),
                    EventFactory.mapAreaDelete("del", "owner", 12, 2, "item"),
                )
                events.forEach { assertTrue(f.processLocallyEquivalent(it) is LocalMapWriteOutcome.AcceptedNew); assertTrue(f.processRemotely(it)) }
                val local = f.captureLocalTarget(MapTargetIdentity("g", type, "item")); val remote = f.captureRemoteTarget(MapTargetIdentity("g", type, "item"))
                assertEquals(setOf("add", "upd", "del"), local.durableEvents.map { it.eventId }.toSet())
                assertEquals(setOf("owner"), local.durableEvents.map { it.actorDid }.toSet())
                assertEquals(listOf(10L, 11L, 12L), local.durableEvents.map { it.timestamp })
                assertEquals(listOf(0L, 1L, 2L), local.durableEvents.map { it.lamport })
                assertEquals(setOf("item"), local.durableEvents.mapNotNull { it.targetId }.toSet())
                assertEquivalent(local, remote)
            }
        }
    }

    @Test fun ownershipMembershipAndTemporalPolicies_areSourceIndependent() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer", "removed")
            val add = markerAdd("add", "owner", 10, "m", ttl = 20)
            assertTrue(f.processLocallyEquivalent(add) is LocalMapWriteOutcome.AcceptedNew); assertTrue(f.processRemotely(add))
            val nonOwner = withoutLegacy(EventFactory.mapMarkerUpdate("bad", "peer", 11, 0, "m", "l", 9.0, 9.0, null, null, null, "risk"))
            assertTrue(f.processLocallyEquivalent(nonOwner) is LocalMapWriteOutcome.SemanticRejected); assertFalse(f.processRemotely(nonOwner))
            listOf(f.localDb, f.remoteDb).forEach { it.removedMemberDao().upsert(RemovedMemberEntity("g", "removed", "owner", "r", 12, 12, 0, "owner")) }
            val removed = markerAdd("removed-add", "removed", 13, "x")
            assertTrue(f.processLocallyEquivalent(removed) is LocalMapWriteOutcome.SemanticRejected); assertFalse(f.processRemotely(removed))
            val expired = EventFactory.mapMarkerDelete("expired", "owner", 30, 0, "m")
            assertTrue(f.processLocallyEquivalent(expired) is LocalMapWriteOutcome.SemanticRejected); assertFalse(f.processRemotely(expired))
            assertEquivalent(f.captureLocalTarget(MapTargetIdentity("g", "marker", "m")), f.captureRemoteTarget(MapTargetIdentity("g", "marker", "m")))
        }
    }

    @Test fun deletedTarget_cannotBeRevivedThroughEitherPath() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner")
            listOf(markerAdd("add", "owner", 10, "m"), EventFactory.mapMarkerDelete("del", "owner", 11, 0, "m")).forEach {
                f.processLocallyEquivalent(it); f.processRemotely(it)
            }
            val revive = EventFactory.mapMarkerUpdate("revive", "owner", 12, 0, "m", "l", 8.0, 9.0, null, null, null, "risk")
            assertTrue(f.processLocallyEquivalent(revive) is LocalMapWriteOutcome.SemanticRejected)
            val remote = f.processRemotelyDetailed(revive); assertFalse(remote.accepted); assertTrue(remote.result is app.peermesh.data.map.processing.MapEventProcessingResult.Rejected)
            assertEquivalent(f.captureLocalTarget(MapTargetIdentity("g", "marker", "m")), f.captureRemoteTarget(MapTargetIdentity("g", "marker", "m")))
        }
    }

    @Test fun resolvedSituation_cannotBeReactivatedByCanonicalUpdateThroughEitherPath() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner")
            val add = markerAdd("resolved-add", "owner", 10, "m")
            f.processLocallyEquivalent(add); f.processRemotely(add)
            val resolution = LocalRemoteDedicatedActionConvergenceInstrumentationTest.status("resolve", "owner", 11, 0)
            assertTrue(f.processLocallyEquivalent(resolution) is LocalMapWriteOutcome.AcceptedNew)
            val remoteResolution = f.processRemotelyDetailed(resolution); assertTrue(remoteResolution.accepted); assertTrue(remoteResolution.result is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            val beforeLocal = f.captureLocalTarget(MapTargetIdentity("g", "marker", "m")); val beforeRemote = f.captureRemoteTarget(MapTargetIdentity("g", "marker", "m"))
            assertEquals("no_longer_active", beforeLocal.domainState!!.lifecycle); assertEquals("resolve", beforeLocal.domainState.latestLifecycle!!.eventId)
            val update = EventFactory.mapMarkerUpdate("reactivate", "owner", 12, 0, "m", "l", 5.0, 6.0, null, null, null, "risk")
            assertTrue(f.processLocallyEquivalent(update) is LocalMapWriteOutcome.SemanticRejected)
            val remote = f.processRemotelyDetailed(update); assertFalse(remote.accepted); assertEquals(1, remote.invocationCount); assertTrue(remote.result is app.peermesh.data.map.processing.MapEventProcessingResult.Rejected)
            assertEquals(beforeLocal, f.captureLocalTarget(MapTargetIdentity("g", "marker", "m"))); assertEquals(beforeRemote, f.captureRemoteTarget(MapTargetIdentity("g", "marker", "m")))
        }
    }

    @Test fun legacyContributionCompatibility_remainsEquivalentAndSeparate() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val add = markerAdd("add", "owner", 10, "m")
            val vote = EventFactory.mapMarkerUpdate("vote", "peer", 11, 0, "m", "l", 9.0, 9.0, null, null, "observed", "risk", confidence = 2)
            listOf(add, vote).forEach { f.processLocallyEquivalent(it); f.processRemotely(it) }
            val l = f.captureLocalTarget(MapTargetIdentity("g", "marker", "m")); val r = f.captureRemoteTarget(MapTargetIdentity("g", "marker", "m"))
            assertEquivalent(l, r); assertEquals(1, l.legacy!!.contributionCount); assertTrue(l.actions.isEmpty())
        }
    }

    @Test fun semanticRejection_leavesBothDatabasesWithoutRejectedDurableEvent() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val add = markerAdd("add-reject", "owner", 10, "m")
            f.processLocallyEquivalent(add); f.processRemotely(add)
            val beforeLocal = f.captureLocalTarget(MapTargetIdentity("g", "marker", "m"))
            val beforeRemote = f.captureRemoteTarget(MapTargetIdentity("g", "marker", "m"))
            val bad = withoutLegacy(EventFactory.mapMarkerUpdate("bad-reject", "peer", 11, 0, "m", "l", 9.0, 9.0, null, null, null, "risk"))
            assertTrue(f.processLocallyEquivalent(bad) is LocalMapWriteOutcome.SemanticRejected)
            val remote = f.processRemotelyDetailed(bad)
            assertFalse(remote.accepted); assertEquals(1, remote.invocationCount)
            assertTrue(remote.result is app.peermesh.data.map.processing.MapEventProcessingResult.Rejected)
            assertEquals(beforeLocal, f.captureLocalTarget(MapTargetIdentity("g", "marker", "m")))
            assertEquals(beforeRemote, f.captureRemoteTarget(MapTargetIdentity("g", "marker", "m")))
            assertNull(f.localDb.eventsDao().findById("g", bad.id)); assertNull(f.remoteDb.eventsDao().findById("g", bad.id))
        }
    }

    @Test fun nonOwnerAreaUpdate_isRejectedWithoutPartialWritesAcrossBothPaths() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val add = areaAdd("area-add", "owner", 10, "a")
            assertTrue(f.processLocallyEquivalent(add) is LocalMapWriteOutcome.AcceptedNew)
            assertRemoteAccepted(f.processRemotelyDetailed(add))
            val update = withoutLegacy(EventFactory.mapAreaUpdate("area-peer-update", "peer", 11, 0, "a", "changed-layer", geometry(2), "changed-label", "changed-note", "risk"))
            val localBefore = f.captureLocalTarget(MapTargetIdentity("g", "area", "a"))
            val remoteBefore = f.captureRemoteTarget(MapTargetIdentity("g", "area", "a"))
            assertTrue(f.processLocallyEquivalent(update) is LocalMapWriteOutcome.SemanticRejected)
            assertRemoteRejected(f.processRemotelyDetailed(update))
            val localAfter = f.captureLocalTarget(MapTargetIdentity("g", "area", "a"))
            val remoteAfter = f.captureRemoteTarget(MapTargetIdentity("g", "area", "a"))
            assertEquals(localBefore, localAfter); assertEquals(remoteBefore, remoteAfter)
            assertNull(f.localDb.eventsDao().findById("g", update.id)); assertNull(f.remoteDb.eventsDao().findById("g", update.id))
            assertEquals(localBefore.canonicalItem, localAfter.canonicalItem)
            listOf("geometry", "label", "note", "layer").forEach { key ->
                assertEquals(localBefore.canonicalItem!!.canonicalValues[key], localAfter.canonicalItem!!.canonicalValues[key])
            }
            assertEquals(localBefore.domainState, localAfter.domainState)
            assertEquivalent(localAfter, remoteAfter)
        }
    }

    @Test fun nonOwnerDelete_isRejectedWithoutPartialWritesAcrossBothPaths() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val add = markerAdd("marker-add", "owner", 10, "m")
            assertTrue(f.processLocallyEquivalent(add) is LocalMapWriteOutcome.AcceptedNew)
            assertRemoteAccepted(f.processRemotelyDetailed(add))
            val delete = EventFactory.mapMarkerDelete("peer-delete", "peer", 11, 0, "m")
            assertCanonicalRejectionWithoutPartialWrites(f, MapTargetIdentity("g", "marker", "m"), delete)
        }
    }

    @Test fun ownerCanonicalTemporalBoundaries_areEquivalentAcrossBothPaths() {
        assertOwnerUpdateParity("inside-owner-window", 1009, accepted = true)
        assertOwnerUpdateParity("at-owner-cutoff", 1010, accepted = false)
        assertOwnerUpdateParity("at-expiry", 2010, accepted = false)
        assertOwnerUpdateParity("after-expiry", 2011, accepted = false)
    }

    @Test fun runtimeRoomSchema_remainsVersion52() {
        MapEventConvergenceFixture().use { f -> assertEquals(54, f.localDb.openHelper.readableDatabase.version); assertEquals(54, f.remoteDb.openHelper.readableDatabase.version) }
    }

    private fun assertOwnerUpdateParity(id: String, time: Long, accepted: Boolean) {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner")
            val add = EventFactory.mapAreaAdd("$id-add", "owner", 10, 0, "a", "layer", geometry(1), null, null, "risk", ttlSeconds = 2000)
            assertTrue(f.processLocallyEquivalent(add) is LocalMapWriteOutcome.AcceptedNew)
            assertRemoteAccepted(f.processRemotelyDetailed(add))
            val update = EventFactory.mapAreaUpdate(id, "owner", time, 0, "a", "updated-layer", geometry(2), "updated-label", "updated-note", "risk")
            val target = MapTargetIdentity("g", "area", "a")
            val localBefore = f.captureLocalTarget(target)
            val remoteBefore = f.captureRemoteTarget(target)
            val local = f.processLocallyEquivalent(update)
            val remote = f.processRemotelyDetailed(update)
            if (accepted) {
                assertTrue("$id local=$local", local is LocalMapWriteOutcome.AcceptedNew)
                assertRemoteAccepted(remote)
            } else {
                assertTrue("$id local=$local", local is LocalMapWriteOutcome.SemanticRejected)
                assertRemoteRejected(remote)
                assertEquals(localBefore, f.captureLocalTarget(target))
                assertEquals(remoteBefore, f.captureRemoteTarget(target))
                assertNull(f.localDb.eventsDao().findById("g", update.id)); assertNull(f.remoteDb.eventsDao().findById("g", update.id))
            }
            assertEquivalent(f.captureLocalTarget(target), f.captureRemoteTarget(target))
        }
    }

    private fun assertCanonicalRejectionWithoutPartialWrites(f: MapEventConvergenceFixture, target: MapTargetIdentity, event: EventCore) {
        val localBefore = f.captureLocalTarget(target)
        val remoteBefore = f.captureRemoteTarget(target)
        assertTrue(f.processLocallyEquivalent(event) is LocalMapWriteOutcome.SemanticRejected)
        assertRemoteRejected(f.processRemotelyDetailed(event))
        val localAfter = f.captureLocalTarget(target)
        val remoteAfter = f.captureRemoteTarget(target)
        assertEquals(localBefore, localAfter); assertEquals(remoteBefore, remoteAfter)
        assertNull(f.localDb.eventsDao().findById("g", event.id)); assertNull(f.remoteDb.eventsDao().findById("g", event.id))
        assertEquals(localBefore.canonicalItem, localAfter.canonicalItem)
        assertEquals(localBefore.domainState, localAfter.domainState)
        assertEquivalent(localAfter, remoteAfter)
    }

    private fun assertRemoteAccepted(evidence: RemoteProcessingEvidence) {
        assertTrue(evidence.accepted); assertEquals(1, evidence.invocationCount)
        assertNotNull(evidence.request); assertNotNull(evidence.result)
        assertTrue(evidence.result is MapEventProcessingResult.Accepted)
    }

    private fun assertRemoteRejected(evidence: RemoteProcessingEvidence) {
        assertFalse(evidence.accepted); assertEquals(1, evidence.invocationCount)
        assertNotNull(evidence.request); assertNotNull(evidence.result)
        assertTrue(evidence.result is MapEventProcessingResult.Rejected)
    }

    companion object {
        fun markerAdd(id: String, actor: String, t: Long, item: String, ttl: Long? = null) = EventFactory.mapMarkerAdd(id, actor, t, 0, item, "layer", 1.0, 2.0, null, null, null, "risk", ttlSeconds = ttl, domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
        fun areaAdd(id: String, actor: String, t: Long, item: String, ttl: Long? = null) = EventFactory.mapAreaAdd(id, actor, t, 0, item, "layer", geometry(1), null, null, "risk", ttlSeconds = ttl, domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
        fun geometry(seed: Int) = buildJsonArray { add(JsonPrimitive(seed)) }
        fun withoutLegacy(core: EventCore) = core.copy(data = buildJsonObject { (core.data as JsonObject).forEach { (k, v) -> if (k != "confidence" && k != "note") put(k, v) } })
    }
}
