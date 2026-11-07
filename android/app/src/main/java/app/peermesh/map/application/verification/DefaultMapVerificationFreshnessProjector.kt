package app.peermesh.map.application.verification

import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis

/**
 * Pure M3C2 freshness projector over accepted M3C1 presentation data.
 *
 * This class deliberately owns no clock, timeline, repository or persistence
 * dependency. Canonical ordering comparison remains at the M2 read boundary.
 */
class DefaultMapVerificationFreshnessProjector : ProjectMapVerificationFreshness {
    override fun project(
        verificationResult: MapItemVerificationProjectionResult,
    ): MapVerificationFreshnessProjectionResult = when (verificationResult) {
        is MapItemVerificationProjectionResult.Available ->
            projectAvailable(verificationResult.presentation)
        MapItemVerificationProjectionResult.CoordinationNotApplicable ->
            MapVerificationFreshnessProjectionResult.CoordinationNotApplicable
        MapItemVerificationProjectionResult.UnresolvedLegacyCategory ->
            MapVerificationFreshnessProjectionResult.UnresolvedLegacyCategory
        is MapItemVerificationProjectionResult.Unavailable ->
            MapVerificationFreshnessProjectionResult.Unavailable(verificationResult.reason)
        is MapItemVerificationProjectionResult.InvalidIdentity ->
            MapVerificationFreshnessProjectionResult.InvalidIdentity(verificationResult.reason)
    }

    private fun projectAvailable(
        verification: MapItemVerificationPresentation,
    ): MapVerificationFreshnessProjectionResult {
        val state = when (val evidence = verification.evidence) {
            is MapVerificationEvidence.Present -> currentState(verification, evidence)
            MapVerificationEvidence.Absent -> MapVerificationFreshnessState.Absent(
                reason = conservativeAbsenceReason(verification),
            )
        }
        return MapVerificationFreshnessProjectionResult.Available(
            MapVerificationFreshnessPresentation(verification.identity, state),
        )
    }

    private fun currentState(
        verification: MapItemVerificationPresentation,
        evidence: MapVerificationEvidence.Present,
    ): MapVerificationFreshnessState =
        if (
            verification.lifecycle == MapSituationLifecycleStatus.ACTIVE &&
            verification.freshnessBasis is MapSituationConfirmationFreshnessBasis.Current &&
            evidence.latestCurrentConfirmationAtSeconds ==
                verification.freshnessBasis.latestCurrentConfirmationAtSeconds
        ) {
            MapVerificationFreshnessState.Current(
                actorCount = evidence.actorCount,
                latestCurrentConfirmationAtSeconds = evidence.latestCurrentConfirmationAtSeconds,
            )
        } else {
            MapVerificationFreshnessState.Absent(MapVerificationAbsenceReason.UNKNOWN)
        }

    private fun conservativeAbsenceReason(
        verification: MapItemVerificationPresentation,
    ): MapVerificationAbsenceReason = when (verification.freshnessBasis) {
        MapSituationConfirmationFreshnessBasis.NoAcceptedPriorConfirmationEstablished ->
            if (verification.lifecycle == MapSituationLifecycleStatus.ACTIVE) {
                MapVerificationAbsenceReason.NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED
            } else {
                MapVerificationAbsenceReason.RESOLVED_WITHOUT_PRIOR_CONFIRMATION
            }
        MapSituationConfirmationFreshnessBasis.InvalidatedBySituationChange ->
            if (verification.lifecycle == MapSituationLifecycleStatus.ACTIVE) {
                MapVerificationAbsenceReason.INVALIDATED_BY_SITUATION_CHANGE
            } else {
                MapVerificationAbsenceReason.UNKNOWN
            }
        MapSituationConfirmationFreshnessBasis.InvalidatedByResolution ->
            if (verification.lifecycle == MapSituationLifecycleStatus.NO_LONGER_ACTIVE) {
                MapVerificationAbsenceReason.INVALIDATED_BY_RESOLUTION
            } else {
                MapVerificationAbsenceReason.UNKNOWN
            }
        is MapSituationConfirmationFreshnessBasis.Current,
        MapSituationConfirmationFreshnessBasis.Unknown ->
            MapVerificationAbsenceReason.UNKNOWN
    }
}
