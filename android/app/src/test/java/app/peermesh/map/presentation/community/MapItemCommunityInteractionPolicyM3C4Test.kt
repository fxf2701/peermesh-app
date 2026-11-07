package app.peermesh.map.presentation.community

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.interaction.MapInteractionAvailabilityState
import app.peermesh.map.application.interaction.MapItemInteractionAvailability
import app.peermesh.map.application.interaction.MapItemInteractionsAvailability
import app.peermesh.map.application.interaction.MapInteractionType
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult
import app.peermesh.map.application.verification.MapVerificationAbsenceReason
import app.peermesh.map.application.verification.MapVerificationFreshnessPresentation
import app.peermesh.map.application.verification.MapVerificationFreshnessProjectionResult
import app.peermesh.map.application.verification.MapVerificationFreshnessState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemCommunityInteractionPolicyM3C4Test {
    @Test fun freshnessRequestAndM3AAvailabilityMustBothAllowConfirmation() {
        assertTrue(policy(MapVerificationAbsenceReason.NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED, true))
        assertTrue(policy(MapVerificationAbsenceReason.INVALIDATED_BY_SITUATION_CHANGE, true))
        assertFalse(policy(MapVerificationAbsenceReason.NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED, false))
        assertFalse(policy(MapVerificationAbsenceReason.UNKNOWN, true))
        assertFalse(policy(MapVerificationAbsenceReason.INVALIDATED_BY_RESOLUTION, true))
        assertFalse(policy(MapVerificationAbsenceReason.RESOLVED_WITHOUT_PRIOR_CONFIRMATION, true))
    }

    @Test fun currentUnavailableAndPendingProjectionNeverOfferTheCta() {
        assertFalse(MapItemCommunityInteractionPolicy.canRequestFreshConfirmation(
            MapItemCommunityProjectionSnapshot.PendingCurrentDetail,
            availability(true),
        ))
        assertFalse(MapItemCommunityInteractionPolicy.canRequestFreshConfirmation(
            projected(MapVerificationFreshnessState.Current(1, null)),
            availability(true),
        ))
    }

    @Test fun hiddenMissingOrDuplicateConfirmNeverPassesThePolicy() {
        val fresh = projected(MapVerificationFreshnessState.Absent(
            MapVerificationAbsenceReason.NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED,
        ))
        assertFalse(MapItemCommunityInteractionPolicy.canRequestFreshConfirmation(fresh, null))
        assertFalse(MapItemCommunityInteractionPolicy.canRequestFreshConfirmation(
            fresh,
            MapItemInteractionsAvailability(IDENTITY, emptyList()),
        ))
        assertFalse(MapItemCommunityInteractionPolicy.canRequestFreshConfirmation(
            fresh,
            availability(MapInteractionAvailabilityState.HIDDEN),
        ))
        assertFalse(MapItemCommunityInteractionPolicy.canRequestFreshConfirmation(
            fresh,
            MapItemInteractionsAvailability(
                IDENTITY,
                listOf(
                    confirm(MapInteractionAvailabilityState.ENABLED),
                    confirm(MapInteractionAvailabilityState.ENABLED),
                ),
            ),
        ))
    }

    @Test fun ctaActionDelegatesOnlyTheExistingConfirmInteraction() {
        var interaction: MapInteractionType? = null
        MapItemCommunityConfirmCtaAction.open { interaction = it }
        assertTrue(interaction === MapInteractionType.Situation.Confirm)
    }

    private fun policy(reason: MapVerificationAbsenceReason, enabled: Boolean) =
        MapItemCommunityInteractionPolicy.canRequestFreshConfirmation(
            projected(MapVerificationFreshnessState.Absent(reason)),
            availability(enabled),
        )

    private fun projected(state: MapVerificationFreshnessState) =
        MapItemCommunityProjectionSnapshot.Projected(
            MapItemVerificationProjectionResult.CoordinationNotApplicable,
            MapVerificationFreshnessProjectionResult.Available(
                MapVerificationFreshnessPresentation(IDENTITY, state),
            ),
            MapCoordinationParticipationProjectionResult.NotApplicableToSituation,
            MapItemCommunityTimelineProjection.NotReady,
        )

    private fun availability(enabled: Boolean) = availability(
        if (enabled) MapInteractionAvailabilityState.ENABLED else MapInteractionAvailabilityState.DISABLED,
    )

    private fun availability(state: MapInteractionAvailabilityState) = MapItemInteractionsAvailability(
        IDENTITY,
        listOf(confirm(state)),
    )

    private fun confirm(state: MapInteractionAvailabilityState) = MapItemInteractionAvailability(
        MapInteractionType.Situation.Confirm,
        state,
    )

    companion object {
        private val IDENTITY = MapItemIdentity("group", MapItemType.MARKER, "item")
    }
}
