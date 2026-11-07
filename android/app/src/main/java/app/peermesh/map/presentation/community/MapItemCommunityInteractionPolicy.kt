package app.peermesh.map.presentation.community

import app.peermesh.map.application.interaction.MapItemInteractionsAvailability
import app.peermesh.map.application.interaction.MapInteractionType
import app.peermesh.map.application.verification.MapVerificationAbsenceReason
import app.peermesh.map.application.verification.MapVerificationFreshnessProjectionResult
import app.peermesh.map.application.verification.MapVerificationFreshnessState

/** Pure Android-presentation policy: advisory freshness can only narrow M3A availability. */
internal object MapItemCommunityInteractionPolicy {
    fun canRequestFreshConfirmation(
        projection: MapItemCommunityProjectionSnapshot?,
        availability: MapItemInteractionsAvailability?,
    ): Boolean {
        val projected = projection as? MapItemCommunityProjectionSnapshot.Projected ?: return false
        val freshness = projected.freshness as? MapVerificationFreshnessProjectionResult.Available
            ?: return false
        val absence = freshness.presentation.state as? MapVerificationFreshnessState.Absent ?: return false
        val freshnessRequestsConfirmation = when (absence.reason) {
            MapVerificationAbsenceReason.NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED,
            MapVerificationAbsenceReason.INVALIDATED_BY_SITUATION_CHANGE,
            -> true
            MapVerificationAbsenceReason.INVALIDATED_BY_RESOLUTION,
            MapVerificationAbsenceReason.RESOLVED_WITHOUT_PRIOR_CONFIRMATION,
            MapVerificationAbsenceReason.UNKNOWN,
            -> false
        }
        if (!freshnessRequestsConfirmation) return false
        val confirm = availability?.interactions?.singleOrNull {
            it.interaction == MapInteractionType.Situation.Confirm
        } ?: return false
        return confirm.isVisible && confirm.isEnabled
    }
}

/** The CTA may only enter the existing M3A interaction path. */
internal object MapItemCommunityConfirmCtaAction {
    fun open(openInteraction: (MapInteractionType) -> Unit) {
        openInteraction(MapInteractionType.Situation.Confirm)
    }
}
