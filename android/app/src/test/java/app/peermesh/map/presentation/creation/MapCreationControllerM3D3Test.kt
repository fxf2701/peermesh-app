package app.peermesh.map.presentation.creation

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.map.application.creation.MapCreationDeliveryState
import app.peermesh.map.application.creation.MapCreationShape
import app.peermesh.map.application.creation.MapCreationSubmissionFailure
import app.peermesh.map.application.creation.MapCreationSubmissionResult
import app.peermesh.map.application.creation.SubmitMapCreation
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCreationControllerM3D3Test {
    @Test fun `marker draft fields and geometry have one immutable state owner`() {
        val fixture = Fixture()
        fixture.beginMarker("group-a")
        fixture.controller.updateKind("risk")
        fixture.controller.updateLabel("R")
        fixture.controller.updateNote("note")
        fixture.controller.updateTtlSeconds(60L)
        fixture.controller.updateCreatorInformationSource(MapInformationSource.FIRSTHAND)
        fixture.controller.updateMarker(10.0, 20.0, 30.0)

        val draft = fixture.controller.currentState().draft!!
        assertEquals("group-a", draft.groupId)
        assertEquals("risk", draft.kind)
        assertEquals("R", draft.label)
        assertEquals(30.0, (draft.geometry as MapCreationDraftGeometry.Marker).radiusMeters)
    }

    @Test fun `area input and published snapshots defensively copy mutable vertices`() {
        val fixture = Fixture()
        fixture.controller.begin("group", MapItemCategory.SITUATION, MapCreationShape.AREA)
        val points = mutableListOf(c(0.0, 0.0), c(0.0, 1.0), c(1.0, 1.0))
        fixture.controller.updateArea(MapSceneAreaGeometry.Polygon(listOf(points)))
        val snapshot = fixture.controller.currentState()
        points.clear()
        val ring = ((snapshot.draft!!.geometry as MapCreationDraftGeometry.Area).geometry
            as MapSceneAreaGeometry.Polygon).rings.single()
        assertEquals(3, ring.size)
    }

    @Test fun `one in flight submission ignores duplicate tap and accepted clears matching draft`() {
        val fixture = Fixture()
        fixture.readyMarker("group")
        fixture.controller.submit()
        fixture.controller.submit()
        assertTrue(fixture.controller.currentState().operation is MapCreationOperationState.Submitting)
        assertEquals(1, fixture.background.size)
        fixture.background.runNext()
        assertEquals(1, fixture.submissions)
        assertNull(fixture.controller.currentState().draft)
        assertTrue(fixture.controller.currentState().feedback is MapCreationFeedback.Submission)
    }

    @Test fun `failure preserves draft for correction and retry`() {
        val fixture = Fixture(result = MapCreationSubmissionResult.Failed(
            null,
            MapCreationSubmissionFailure.PERSISTENCE_FAILURE,
        ))
        fixture.readyMarker("group")
        fixture.controller.submit()
        fixture.background.runNext()
        assertNotNull(fixture.controller.currentState().draft)
        assertEquals(MapCreationOperationState.Idle, fixture.controller.currentState().operation)
    }

    @Test fun `retry keeps draft identity but receives a new request identity`() {
        val fixture = Fixture(result = MapCreationSubmissionResult.Failed(
            null,
            MapCreationSubmissionFailure.PERSISTENCE_FAILURE,
        ))
        fixture.readyMarker("group")

        val first = fixture.controller.submit()!!
        fixture.background.runNext()
        val second = fixture.controller.submit()!!

        assertEquals(first.draftId, second.draftId)
        assertTrue(first.requestId != second.requestId)
    }

    @Test fun `new draft receives a new draft identity`() {
        val fixture = Fixture()
        fixture.readyMarker("group")
        val first = fixture.controller.submit()!!
        fixture.background.runNext()
        fixture.readyMarker("group")
        val second = fixture.controller.submit()!!

        assertTrue(first.draftId != second.draftId)
    }

    @Test fun `feedback carries started identity and only matching consumption clears it`() {
        val fixture = Fixture()
        fixture.readyMarker("group")
        val started = fixture.controller.submit()!!
        fixture.background.runNext()

        val feedback = fixture.controller.currentState().feedback as MapCreationFeedback.Submission
        assertEquals(started, feedback.submissionId)
        fixture.controller.consumeSubmissionFeedback(
            MapCreationSubmissionId(started.draftId, started.requestId + 1),
        )
        assertNotNull(fixture.controller.currentState().feedback)
        fixture.controller.consumeSubmissionFeedback(started)
        assertNull(fixture.controller.currentState().feedback)
    }

    @Test fun `queued stale publication cannot apply to newer submission owner`() {
        val background = QueueExecutor()
        val publication = QueueExecutor()
        val controller = DefaultMapCreationController(
            SubmitMapCreation { MapCreationSubmissionResult.Accepted("item", MapCreationDeliveryState.ENQUEUED) },
            background,
            publication,
        )
        val coordinator = MapCreationPendingFeedbackCoordinator()
        val applied = mutableListOf<MapCreationSubmissionId>()
        controller.addListener(MapCreationStateListener { state ->
            val feedback = state.feedback as? MapCreationFeedback.Submission
                ?: return@MapCreationStateListener
            if (coordinator.resolve(feedback.submissionId) == MapCreationPendingFeedbackDecision.Apply) {
                applied += feedback.submissionId
            }
            controller.consumeSubmissionFeedback(feedback.submissionId)
        })

        readyMarker(controller, "group")
        val submissionA = controller.submit { coordinator.register(it) }!!
        background.runNext() // Completion A is published but not delivered to the listener yet.
        coordinator.clearIfMatches(submissionA) // Dialog A is dismissed.
        controller.cancel()
        readyMarker(controller, "group")
        val submissionB = controller.submit { coordinator.register(it) }!!

        publication.runAll() // Delivers stale A publication while B owns the pending UI.
        assertTrue(applied.isEmpty())
        background.runNext()
        publication.runAll()

        assertEquals(listOf(submissionB), applied)
    }

    @Test fun `executor rejection completes with internal failure and retains retryable draft`() {
        var submitted = false
        val controller = DefaultMapCreationController(
            SubmitMapCreation { submitted = true; error("must not submit") },
            Executor { throw RejectedExecutionException("rejected") },
        )
        readyMarker(controller, "group")

        controller.submit()

        val state = controller.currentState()
        assertEquals(MapCreationOperationState.Idle, state.operation)
        assertNotNull(state.draft)
        assertEquals(
            MapCreationSubmissionFailure.INTERNAL_FAILURE,
            ((state.feedback as MapCreationFeedback.Submission).result as
                MapCreationSubmissionResult.Failed).reason,
        )
        assertEquals(
            MapCreationSubmissionId(state.draft!!.draftId, 1L),
            (state.feedback as MapCreationFeedback.Submission).submissionId,
        )
        assertFalse(submitted)
    }

    @Test fun `retry after executor rejection is accepted normally`() {
        val executor = RejectThenQueueExecutor()
        var submissions = 0
        val controller = DefaultMapCreationController(
            SubmitMapCreation {
                submissions++
                MapCreationSubmissionResult.Accepted("item", MapCreationDeliveryState.ENQUEUED)
            },
            executor,
        )
        readyMarker(controller, "group")

        controller.submit()
        assertEquals(MapCreationOperationState.Idle, controller.currentState().operation)
        executor.reject = false
        controller.submit()
        assertTrue(controller.currentState().operation is MapCreationOperationState.Submitting)
        executor.runNext()

        assertEquals(1, submissions)
        assertNull(controller.currentState().draft)
        assertEquals(MapCreationOperationState.Idle, controller.currentState().operation)
    }

    @Test fun `ordinary async submit exception is retryable internal failure`() {
        val background = QueueExecutor()
        val controller = DefaultMapCreationController(
            SubmitMapCreation { throw IllegalStateException("submit") },
            background,
        )
        readyMarker(controller, "group")
        controller.submit()
        background.runNext()

        val state = controller.currentState()
        assertNotNull(state.draft)
        assertEquals(MapCreationOperationState.Idle, state.operation)
        assertEquals(
            MapCreationSubmissionFailure.INTERNAL_FAILURE,
            ((state.feedback as MapCreationFeedback.Submission).result as
                MapCreationSubmissionResult.Failed).reason,
        )
    }

    @Test(expected = FatalTestError::class)
    fun `fatal executor rejection error is not swallowed`() {
        val controller = DefaultMapCreationController(
            SubmitMapCreation { error("must not submit") },
            Executor { throw FatalTestError() },
        )
        readyMarker(controller, "group")
        controller.submit()
    }

    @Test fun `cancel and newer group draft reject stale completion`() {
        val fixture = Fixture()
        fixture.readyMarker("group-a")
        fixture.controller.submit()
        fixture.controller.cancel()
        fixture.readyMarker("group-b")
        val newer = fixture.controller.currentState().draft
        fixture.background.runNext()
        assertEquals(newer, fixture.controller.currentState().draft)
        assertEquals("group-b", fixture.controller.currentState().draft!!.groupId)
    }

    @Test fun `listeners detach and close prevent future publication`() {
        val fixture = Fixture()
        var calls = 0
        val listener = MapCreationStateListener { calls++ }
        fixture.controller.addListener(listener)
        fixture.controller.removeListener(listener)
        fixture.beginMarker("group")
        assertEquals(1, calls)
        fixture.controller.close()
        fixture.controller.updateKind("ignored")
        assertFalse(fixture.controller.currentState().isDrafting)
    }

    @Test(expected = FatalTestError::class)
    fun `fatal async error is not swallowed by controller`() {
        val background = QueueExecutor()
        val controller = DefaultMapCreationController(
            SubmitMapCreation { throw FatalTestError() },
            background,
        )
        readyMarker(controller, "group")
        controller.submit()
        background.runNext()
    }

    private class Fixture(
        private val result: MapCreationSubmissionResult =
            MapCreationSubmissionResult.Accepted("item", MapCreationDeliveryState.ENQUEUED),
    ) {
        val background = QueueExecutor()
        var submissions = 0
        val controller = DefaultMapCreationController(
            SubmitMapCreation { submissions++; result },
            background,
        )

        fun beginMarker(groupId: String) =
            controller.begin(groupId, MapItemCategory.SITUATION, MapCreationShape.MARKER)

        fun readyMarker(groupId: String) = readyMarker(controller, groupId)
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size
        override fun execute(command: Runnable) {
            tasks += command
        }
        fun runNext() = tasks.removeFirst().run()
        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    private class RejectThenQueueExecutor : Executor {
        var reject = true
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException("rejected")
            tasks += command
        }

        fun runNext() = tasks.removeFirst().run()
    }

    private companion object {
        fun readyMarker(controller: MapCreationController, groupId: String) {
            controller.begin(groupId, MapItemCategory.SITUATION, MapCreationShape.MARKER)
            controller.updateKind("risk")
            controller.updateCreatorInformationSource(MapInformationSource.FIRSTHAND)
            controller.updateMarker(10.0, 20.0, null)
        }

        fun c(lat: Double, lon: Double) = MapSceneCoordinate(lat, lon)
    }

    private class FatalTestError : Error()
}
