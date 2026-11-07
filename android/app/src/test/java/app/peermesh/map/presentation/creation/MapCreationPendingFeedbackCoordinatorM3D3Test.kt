package app.peermesh.map.presentation.creation

import org.junit.Assert.assertEquals
import org.junit.Test

class MapCreationPendingFeedbackCoordinatorM3D3Test {
    @Test fun `same group stale feedback cannot affect newer pending submission`() {
        val coordinator = MapCreationPendingFeedbackCoordinator()
        val submissionA = MapCreationSubmissionId(draftId = 10L, requestId = 1L)
        val submissionB = MapCreationSubmissionId(draftId = 11L, requestId = 2L)

        coordinator.register(submissionA)
        coordinator.clearIfMatches(submissionA) // Dialog A was dismissed in the same group and scope.
        coordinator.register(submissionB)

        assertEquals(MapCreationPendingFeedbackDecision.IgnoreStale, coordinator.resolve(submissionA))
        assertEquals(MapCreationPendingFeedbackDecision.Apply, coordinator.resolve(submissionB))
    }

    @Test fun `matching feedback applies once`() {
        val coordinator = MapCreationPendingFeedbackCoordinator()
        val submission = MapCreationSubmissionId(draftId = 10L, requestId = 1L)
        coordinator.register(submission)

        assertEquals(MapCreationPendingFeedbackDecision.Apply, coordinator.resolve(submission))
        assertEquals(MapCreationPendingFeedbackDecision.NoPending, coordinator.resolve(submission))
    }
}
