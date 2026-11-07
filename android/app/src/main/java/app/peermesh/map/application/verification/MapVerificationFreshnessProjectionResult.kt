package app.peermesh.map.application.verification

import app.peermesh.map.application.detail.InvalidIdentityReason

sealed interface MapVerificationFreshnessProjectionResult {
    data class Available(
        val presentation: MapVerificationFreshnessPresentation,
    ) : MapVerificationFreshnessProjectionResult

    data object CoordinationNotApplicable : MapVerificationFreshnessProjectionResult
    data object UnresolvedLegacyCategory : MapVerificationFreshnessProjectionResult

    data class Unavailable(
        val reason: MapVerificationUnavailableReason,
    ) : MapVerificationFreshnessProjectionResult

    data class InvalidIdentity(
        val reason: InvalidIdentityReason,
    ) : MapVerificationFreshnessProjectionResult
}
