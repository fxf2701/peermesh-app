package app.peermesh.map.presentation.detail

import app.peermesh.map.application.submission.MapInteractionDeliveryWarning
import app.peermesh.map.application.submission.MapInteractionInputFailure
import app.peermesh.map.application.submission.MapInteractionRejection
import app.peermesh.map.application.submission.MapInteractionSubmissionFailure

/** Stable presentation classifications. Localized strings stay in Android resources/views. */
sealed interface MapItemDetailShellFeedback {
    data class Accepted(
        val deliveryWarning: MapInteractionDeliveryWarning? = null,
    ) : MapItemDetailShellFeedback

    data object AlreadyRecorded : MapItemDetailShellFeedback

    data class Rejected(
        val reason: MapInteractionRejection,
    ) : MapItemDetailShellFeedback

    data class InvalidInput(
        val failure: MapInteractionInputFailure,
    ) : MapItemDetailShellFeedback

    data class Failed(
        val reason: MapInteractionSubmissionFailure,
    ) : MapItemDetailShellFeedback

    /** Lightweight UI guard; semantic validation still belongs to M3A3. */
    data object InteractionUnavailable : MapItemDetailShellFeedback
}
