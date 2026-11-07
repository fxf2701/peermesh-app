package app.peermesh.map.presentation.creation

import app.peermesh.map.application.creation.MapCreationSubmissionResult

/** Draft state is intentionally independent from persisted map selection and scene state. */
data class MapCreationState(
    val draft: MapCreationDraft? = null,
    val operation: MapCreationOperationState = MapCreationOperationState.Idle,
    val feedback: MapCreationFeedback? = null,
) {
    val isDrafting: Boolean get() = draft != null
}

/** Presentation-safe identity for one submission attempt. */
data class MapCreationSubmissionId(
    val draftId: Long,
    val requestId: Long,
)

sealed interface MapCreationOperationState {
    data object Idle : MapCreationOperationState
    data class Submitting(val submissionId: MapCreationSubmissionId) : MapCreationOperationState
}

sealed interface MapCreationFeedback {
    data class Submission(
        val submissionId: MapCreationSubmissionId,
        val result: MapCreationSubmissionResult,
    ) : MapCreationFeedback
    data class DraftRejected(val failure: MapCreationDraftFailure) : MapCreationFeedback
}
