package app.peermesh.map.presentation.creation

/**
 * Matches delayed publication to the dialog that registered the exact submission attempt.
 * It deliberately owns no Android UI objects.
 */
internal class MapCreationPendingFeedbackCoordinator {
    private var pendingSubmissionId: MapCreationSubmissionId? = null

    fun register(submissionId: MapCreationSubmissionId) {
        pendingSubmissionId = submissionId
    }

    fun resolve(submissionId: MapCreationSubmissionId): MapCreationPendingFeedbackDecision = when {
        pendingSubmissionId == null -> MapCreationPendingFeedbackDecision.NoPending
        pendingSubmissionId != submissionId -> MapCreationPendingFeedbackDecision.IgnoreStale
        else -> {
            pendingSubmissionId = null
            MapCreationPendingFeedbackDecision.Apply
        }
    }

    fun clearIfMatches(submissionId: MapCreationSubmissionId) {
        if (pendingSubmissionId == submissionId) pendingSubmissionId = null
    }

    fun clear() {
        pendingSubmissionId = null
    }
}

internal sealed interface MapCreationPendingFeedbackDecision {
    data object Apply : MapCreationPendingFeedbackDecision
    data object IgnoreStale : MapCreationPendingFeedbackDecision
    data object NoPending : MapCreationPendingFeedbackDecision
}
