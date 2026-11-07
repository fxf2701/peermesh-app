package app.peermesh

import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.types.EventTypes
import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.processing.MapEventRejectionReason
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMapPostCommitTransportTest {
    @Test fun acceptedNew_ordersCallbacks_andPassesExactCommittedCore() {
        val core = core()
        val calls = mutableListOf<String>()
        var captured: EventCore? = null
        val result = dispatch(core, { calls += "key"; true }, { calls += "permission"; true }, { calls += "ready"; true }) {
            calls += "enqueue"; captured = it; true
        }
        assertEquals(listOf("key", "permission", "ready", "enqueue"), calls)
        assertSame(core, captured)
        assertEquals(LocalMapPostCommitTransport.Result.Enqueued(true, true, true), result)
    }

    @Test fun missingKey_skipsBleAndEnqueue() {
        val calls = mutableListOf<String>()
        val result = dispatch(core(), { calls += "key"; false }, { calls += "permission"; true }, { calls += "ready"; true }) { calls += "enqueue"; true }
        assertEquals(LocalMapPostCommitTransport.Result.MissingGroupKey, result)
        assertEquals(listOf("key"), calls)
    }

    @Test fun bleNotReadyWithPermission_stillEnqueuesLikeBaseline() {
        var enqueueCount = 0
        val result = dispatch(core(), { true }, { true }, { false }) { enqueueCount++; true }
        assertEquals(1, enqueueCount)
        assertEquals(LocalMapPostCommitTransport.Result.Enqueued(true, false, true), result)
    }

    @Test fun missingBlePermission_preservesBaselineEnqueueBehavior() {
        val calls = mutableListOf<String>()
        val result = dispatch(core(), { calls += "key"; true }, { calls += "permission"; false }, { calls += "ready"; true }) { calls += "enqueue"; true }
        assertEquals(listOf("key", "permission", "ready", "enqueue"), calls)
        assertEquals(LocalMapPostCommitTransport.Result.Enqueued(false, false, true), result)
    }

    @Test fun enqueueFalse_isReportedWithoutChangingCommittedOutcome() {
        var count = 0
        val result = dispatch(core(), { true }, { true }, { true }) { count++; false }
        assertEquals(1, count)
        assertEquals(LocalMapPostCommitTransport.Result.Enqueued(true, true, false), result)
    }

    @Test fun enqueueException_isCapturedWithExactCauseAndBleState() {
        val cause = IllegalStateException("boom")
        var count = 0
        val result = dispatch(core(), { true }, { true }, { true }) { count++; throw cause }
        assertEquals(1, count)
        assertTrue(result is LocalMapPostCommitTransport.Result.EnqueueFailed)
        result as LocalMapPostCommitTransport.Result.EnqueueFailed
        assertSame(cause, result.cause)
        assertTrue(result.hasBlePermission)
        assertTrue(result.bleReady)
    }

    @Test fun nonTransportOutcomes_skipEveryPostCommitCallback() {
        val outcomes = listOf<LocalMapWriteOutcome>(
            LocalMapWriteOutcome.AcceptedNoChange("e"), LocalMapWriteOutcome.ExactDuplicate("e"),
            LocalMapWriteOutcome.ConflictingDuplicate("e"), LocalMapWriteOutcome.SemanticRejected("e", MapEventRejectionReason.OWNER_REQUIRED),
            LocalMapWriteOutcome.InvalidPersistedState("e", "bad"), LocalMapWriteOutcome.PersistenceFailure("e", IllegalStateException()),
        )
        outcomes.forEach { outcome ->
            val result = LocalMapPostCommitTransport.dispatch(outcome, core(), { error("key") }, { error("permission") }, { error("ready") }) { error("enqueue") }
            assertEquals(LocalMapPostCommitTransport.Result.NotEligible, result)
        }
    }

    private fun dispatch(core: EventCore, key: () -> Boolean, permission: () -> Boolean, ready: () -> Boolean, enqueue: (EventCore) -> Boolean) =
        LocalMapPostCommitTransport.dispatch(LocalMapWriteOutcome.AcceptedNew(core.id), core, key, permission, ready, enqueue)

    private fun core() = EventCore(1, "e", EventTypes.MAP_MARKER_ADD, Actor("owner"), EventTime(1), EventRef(target = "m"), data = JsonNull)
}
