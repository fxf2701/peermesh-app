package app.peermesh.data.map.convergence

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.*
import app.peermesh.core.model.EventCore
import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.processing.MapEventProcessingResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalRemoteDedicatedActionConvergenceInstrumentationTest {
    @Test fun dedicatedConfirmation_exactAndConflictingDuplicates_areImmutableAcrossBothSourceDirections() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val add = add(MapItemCategory.SITUATION)
            assertTrue(f.processLocallyEquivalent(add) is LocalMapWriteOutcome.AcceptedNew)
            assertRemoteAccepted(f.processRemotelyDetailed(add))
            val original = EventFactory.mapConfirmation(MapItemType.MARKER, MapConfirmation(meta("dedup", "peer", 11, 0), null, "original"))
            val conflicting = original.copy(data = buildJsonObject { (original.data as JsonObject).forEach { (k,v) -> put(k,v) }; put("note", "changed") })
            assertTrue(f.processLocallyEquivalent(original) is LocalMapWriteOutcome.AcceptedNew)
            val localBefore = f.captureLocalTarget(target())
            val originalAction = localBefore.actions.single()
            assertEquals(1, localBefore.actions.size)
            assertEquals("original", originalAction.semanticValues["note"])
            assertEquals("applied", originalAction.materializationState)
            assertNull(originalAction.rejectionReason)
            assertEquals(1, localBefore.domainState!!.confirmationActorCount)
            assertEquals("dedup", localBefore.domainState.latestConfirmation!!.eventId)
            assertEquals("active", localBefore.domainState.lifecycle)
            val localExact = f.processRemotelyOnLocalDbDetailed(original)
            assertFalse(localExact.accepted); assertEquals(1, localExact.invocationCount); assertNotNull(localExact.request); assertNotNull(localExact.result)
            assertTrue(localExact.result is MapEventProcessingResult.ExactDuplicate)
            val localAfterExact = f.captureLocalTarget(target())
            assertEquals(localBefore, localAfterExact)
            assertEquals(originalAction.fingerprint, localAfterExact.actions.single().fingerprint)
            assertEquals(localBefore.domainState.confirmationActorCount, localAfterExact.domainState!!.confirmationActorCount)
            assertEquals(localBefore.domainState.latestConfirmation, localAfterExact.domainState.latestConfirmation)
            assertEquals(localBefore.domainState.lifecycle, localAfterExact.domainState.lifecycle)
            val localConflict = f.processRemotelyOnLocalDbDetailed(conflicting)
            assertFalse(localConflict.accepted); assertEquals(1, localConflict.invocationCount); assertNotNull(localConflict.request); assertNotNull(localConflict.result)
            assertTrue(localConflict.result is MapEventProcessingResult.ConflictingDuplicate)
            val localAfterConflict = f.captureLocalTarget(target())
            assertEquals(localBefore, localAfterConflict)
            assertEquals(originalAction.fingerprint, localAfterConflict.actions.single().fingerprint)
            assertEquals(localBefore.domainState.confirmationActorCount, localAfterConflict.domainState!!.confirmationActorCount)
            assertEquals(localBefore.domainState.latestConfirmation, localAfterConflict.domainState.latestConfirmation)
            assertEquals(localBefore.domainState.lifecycle, localAfterConflict.domainState.lifecycle)
            val remoteOriginal = f.processRemotelyOnRemoteDbDetailed(original)
            assertRemoteAccepted(remoteOriginal)
            val remoteBefore = f.captureRemoteTarget(target())
            assertTrue(f.processLocallyOnRemoteDb(original) is LocalMapWriteOutcome.ExactDuplicate)
            val remoteAfterExact = f.captureRemoteTarget(target())
            assertEquals(remoteBefore, remoteAfterExact)
            assertEquals(remoteBefore.actions.single().fingerprint, remoteAfterExact.actions.single().fingerprint)
            assertTrue(f.processLocallyOnRemoteDb(conflicting) is LocalMapWriteOutcome.ConflictingDuplicate)
            val remoteAfterConflict = f.captureRemoteTarget(target())
            assertEquals(remoteBefore, remoteAfterConflict)
            assertEquals(remoteBefore.actions.single().fingerprint, remoteAfterConflict.actions.single().fingerprint)
            assertEquivalent(localBefore, remoteBefore)
        }
    }
    @Test fun allDedicatedActions_produceEquivalentImmutableHistoryAndProjection() {
        converge(MapItemCategory.SITUATION, listOf(
            confirm("confirm", "peer", 11, 0),
            update("still", "peer", 12, 0, MapSituationUpdateType.STILL_ACTIVE),
            update("changed", "peer", 13, 0, MapSituationUpdateType.SITUATION_CHANGED),
            status("resolved", "owner", 14, 0),
        ))
        converge(MapItemCategory.COORDINATION, listOf(
            EventFactory.mapAcknowledgement(MapItemType.MARKER, MapCoordinationAcknowledgement(meta("ack", "peer", 11, 0))),
            EventFactory.mapCoordinationNote(MapItemType.MARKER, MapCoordinationNote(meta("note", "peer", 12, 0), "on route")),
        ))
    }

    @Test fun wrongCategoryAction_isRejectedEquivalently() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val add = add(MapItemCategory.SITUATION)
            assertTrue(f.processLocallyEquivalent(add) is LocalMapWriteOutcome.AcceptedNew)
            assertRemoteAccepted(f.processRemotelyDetailed(add))
            val wrong = EventFactory.mapAcknowledgement(MapItemType.MARKER, MapCoordinationAcknowledgement(meta("wrong", "peer", 11, 0)))
            val localBefore = f.captureLocalTarget(target())
            val remoteBefore = f.captureRemoteTarget(target())
            assertTrue(f.processLocallyEquivalent(wrong) is LocalMapWriteOutcome.SemanticRejected)
            val remoteWrong = f.processRemotelyDetailed(wrong)
            assertFalse(remoteWrong.accepted); assertEquals(1, remoteWrong.invocationCount)
            assertNotNull(remoteWrong.request); assertNotNull(remoteWrong.result)
            assertTrue(remoteWrong.result is MapEventProcessingResult.Rejected)
            val localAfter = f.captureLocalTarget(target())
            val remoteAfter = f.captureRemoteTarget(target())
            assertEquals(localBefore, localAfter); assertEquals(remoteBefore, remoteAfter)
            assertNull(f.localDb.eventsDao().findById("g", wrong.id)); assertNull(f.remoteDb.eventsDao().findById("g", wrong.id))
            assertTrue(localAfter.actions.none { it.actionId == "wrong" })
            assertEquivalent(localAfter, remoteAfter)
        }
    }

    @Test fun confirmationComparatorRepresentativeCase_convergesAcrossPaths() {
        val actions = listOf(confirm("a", "peer", 11, 0), confirm("b", "owner", 11, 0), confirm("c", "owner", 11, 1))
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            (listOf(add(MapItemCategory.SITUATION)) + actions).forEach {
                assertTrue(f.processLocallyEquivalent(it) is LocalMapWriteOutcome.AcceptedNew)
                assertRemoteAccepted(f.processRemotelyDetailed(it))
            }
            val local = f.captureLocalTarget(target())
            assertEquivalent(local, f.captureRemoteTarget(target()))
            assertEquals(OrderTupleSnapshot(11, 1, "owner", "c"), local.domainState!!.latestConfirmation)
            assertEquals(2, local.domainState.confirmationActorCount)
            assertEquals(3, local.actions.size)
            assertTrue(local.actions.all { it.materializationState == "applied" && it.rejectionReason == null })
        }
    }

    @Test fun postResolutionNormalAction_isRejectedConsistently() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val accepted = listOf(add(MapItemCategory.SITUATION), confirm("before", "peer", 11, 0), status("resolve", "owner", 12, 0))
            accepted.forEach {
                assertTrue(f.processLocallyEquivalent(it) is LocalMapWriteOutcome.AcceptedNew)
                assertRemoteAccepted(f.processRemotelyDetailed(it))
            }
            val after = confirm("after", "peer", 13, 0)
            assertTrue(f.processLocallyEquivalent(after) is LocalMapWriteOutcome.SemanticRejected)
            val remoteAfter = f.processRemotelyDetailed(after)
            assertFalse(remoteAfter.accepted); assertEquals(1, remoteAfter.invocationCount)
            assertNotNull(remoteAfter.request); assertNotNull(remoteAfter.result)
            assertTrue(remoteAfter.result is MapEventProcessingResult.Rejected)
            val local = f.captureLocalTarget(target())
            val remote = f.captureRemoteTarget(target())
            assertNull(f.localDb.eventsDao().findById("g", after.id)); assertNull(f.remoteDb.eventsDao().findById("g", after.id))
            assertTrue(local.actions.none { it.actionId == "after" })
            val beforeAction = local.actions.single { it.actionId == "before" }
            val resolutionAction = local.actions.single { it.actionId == "resolve" }
            assertEquals("applied", beforeAction.materializationState); assertNull(beforeAction.rejectionReason)
            assertEquals("applied", resolutionAction.materializationState); assertNull(resolutionAction.rejectionReason)
            assertEquals("no_longer_active", local.domainState!!.lifecycle)
            assertEquals(OrderTupleSnapshot(12, 0, "owner", "resolve"), local.domainState.latestLifecycle)
            assertEquals(OrderTupleSnapshot(12, 0, "owner", "resolve"), local.domainState.latestMaterialChange)
            assertEquals(0, local.domainState.confirmationActorCount)
            assertEquivalent(local, remote)
        }
    }

    @Test fun repeatedAndDistinctActors_produceEquivalentAggregates() {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val events = listOf(add(MapItemCategory.SITUATION), confirm("p1", "peer", 11, 0), confirm("p2", "peer", 12, 0), confirm("o1", "owner", 13, 0))
            events.forEach {
                assertTrue(f.processLocallyEquivalent(it) is LocalMapWriteOutcome.AcceptedNew)
                assertRemoteAccepted(f.processRemotelyDetailed(it))
            }
            val l = f.captureLocalTarget(target()); assertEquivalent(l, f.captureRemoteTarget(target()))
            assertEquals(3, l.actions.size)
            assertEquals(2, l.domainState!!.confirmationActorCount)
            assertEquals("o1", l.domainState.latestConfirmation!!.eventId)
            assertEquals(0, l.domainState.acknowledgementActorCount)
        }
    }

    @Test fun exactDedicatedDuplicateAfterTargetDeletion_remainsExactAndNonMutating() =
        mutableDuplicateScenario { f ->
            val delete = EventFactory.mapMarkerDelete("delete", "owner", 12, 0, "m")
            assertTrue(f.processLocallyEquivalent(delete) is LocalMapWriteOutcome.AcceptedNew)
            assertRemoteAccepted(f.processRemotelyOnRemoteDbDetailed(delete))
            assertEquals("deleted", f.localDb.mapDao().findMarker("g", "m")!!.status)
            assertEquivalent(f.captureLocalTarget(target()), f.captureRemoteTarget(target()))
        }

    @Test fun exactDedicatedDuplicateAfterProjectionChange_doesNotRefreshOrRevertProjection() =
        mutableDuplicateScenario { f ->
            val later = confirm("later", "owner", 12, 0)
            assertTrue(f.processLocallyEquivalent(later) is LocalMapWriteOutcome.AcceptedNew)
            assertRemoteAccepted(f.processRemotelyOnRemoteDbDetailed(later))
            assertEquals(2, f.localDb.mapItemDomainStateDao().find("g", "marker", "m")!!.confirmation_actor_count)
            assertEquivalent(f.captureLocalTarget(target()), f.captureRemoteTarget(target()))
        }

    @Test fun exactDedicatedDuplicateAfterResolution_remainsExactAndPreservesResolvedProjection() =
        mutableDuplicateScenario { f ->
            val resolution = status("resolution", "owner", 12, 0)
            assertTrue(f.processLocallyEquivalent(resolution) is LocalMapWriteOutcome.AcceptedNew)
            assertRemoteAccepted(f.processRemotelyOnRemoteDbDetailed(resolution))
            assertEquals("no_longer_active", f.localDb.mapItemDomainStateDao().find("g", "marker", "m")!!.situation_lifecycle)
            assertEquivalent(f.captureLocalTarget(target()), f.captureRemoteTarget(target()))
        }

    private fun converge(category: MapItemCategory, actions: List<EventCore>) {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            (listOf(add(category)) + actions).forEach {
                assertTrue(f.processLocallyEquivalent(it) is LocalMapWriteOutcome.AcceptedNew)
                assertRemoteAccepted(f.processRemotelyDetailed(it))
            }
            assertEquivalent(f.captureLocalTarget(target()), f.captureRemoteTarget(target()))
            assertEquals(actionCount(f.localDb), actionCount(f.remoteDb))
        }
    }

    private fun mutableDuplicateScenario(mutate: (MapEventConvergenceFixture) -> Unit) {
        MapEventConvergenceFixture().use { f ->
            f.seedEquivalentMembers("owner", "peer")
            val targetAdd = add(MapItemCategory.SITUATION)
            assertTrue(f.processLocallyEquivalent(targetAdd) is LocalMapWriteOutcome.AcceptedNew)
            assertRemoteAccepted(f.processRemotelyOnRemoteDbDetailed(targetAdd))
            val original = confirm("original", "peer", 11, 0)
            assertTrue(f.processLocallyEquivalent(original) is LocalMapWriteOutcome.AcceptedNew)
            assertRemoteAccepted(f.processRemotelyOnRemoteDbDetailed(original))
            mutate(f)
            val before = f.captureLocalTarget(target())
            val originalBefore = before.actions.single { it.actionId == "original" }
            val duplicate = f.processRemotelyOnLocalDbDetailed(original)
            assertFalse(duplicate.accepted); assertEquals(1, duplicate.invocationCount); assertNotNull(duplicate.request); assertNotNull(duplicate.result)
            assertTrue(duplicate.result is MapEventProcessingResult.ExactDuplicate)
            val after = f.captureLocalTarget(target())
            val originalAfter = after.actions.single { it.actionId == "original" }
            assertEquals(before, after)
            assertEquals(1, after.actions.count { it.actionId == "original" })
            assertEquals(originalBefore.fingerprint, originalAfter.fingerprint)
            assertEquals(originalBefore.semanticValues, originalAfter.semanticValues)
            assertEquals("applied", originalAfter.materializationState)
            assertNull(originalAfter.rejectionReason)
        }
    }

    private fun assertRemoteAccepted(evidence: RemoteProcessingEvidence) {
        assertTrue(evidence.accepted)
        assertEquals(1, evidence.invocationCount)
        assertNotNull(evidence.request)
        assertNotNull(evidence.result)
        assertTrue(evidence.result is MapEventProcessingResult.Accepted)
    }

    companion object {
        internal fun target() = MapTargetIdentity("g", "marker", "m")
        fun meta(id: String, actor: String, t: Long, lt: Long) = MapActionMetadata(id, "m", actor, t, lt)
        fun add(category: MapItemCategory) = EventFactory.mapMarkerAdd("add-${category.name}", "owner", 10, 0, "m", "l", 1.0, 2.0, null, null, null, if (category == MapItemCategory.COORDINATION) "camp" else "risk", domainExtension = MapCanonicalAddExtension(category, if (category == MapItemCategory.COORDINATION) null else MapInformationSource.FIRSTHAND))
        fun confirm(id: String, actor: String, t: Long, lt: Long) = EventFactory.mapConfirmation(MapItemType.MARKER, MapConfirmation(meta(id, actor, t, lt), null, null))
        fun update(id: String, actor: String, t: Long, lt: Long, kind: MapSituationUpdateType) = EventFactory.mapSituationUpdate(MapItemType.MARKER, MapSituationUpdate(meta(id, actor, t, lt), id, null, kind))
        fun status(id: String, actor: String, t: Long, lt: Long) = EventFactory.mapSituationStatus(MapItemType.MARKER, MapSituationStatusUpdate(meta(id, actor, t, lt), MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null))
    }

    private fun actionCount(db: app.peermesh.data.db.AppDatabase): Int = db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM map_item_actions WHERE gid='g' AND item_type='marker' AND item_id='m'")
        .use { it.moveToFirst(); it.getInt(0) }
}
