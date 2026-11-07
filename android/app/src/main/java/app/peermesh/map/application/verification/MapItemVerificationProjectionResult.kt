package app.peermesh.map.application.verification

import app.peermesh.map.application.detail.InvalidIdentityReason

sealed interface MapItemVerificationProjectionResult {
    data class Available(
        val presentation: MapItemVerificationPresentation,
    ) : MapItemVerificationProjectionResult

    data object CoordinationNotApplicable : MapItemVerificationProjectionResult

    data object UnresolvedLegacyCategory : MapItemVerificationProjectionResult

    data class Unavailable(
        val reason: MapVerificationUnavailableReason,
    ) : MapItemVerificationProjectionResult

    data class InvalidIdentity(
        val reason: InvalidIdentityReason,
    ) : MapItemVerificationProjectionResult
}

enum class MapVerificationUnavailableReason {
    DOMAIN_STATE_MISSING,
    CANONICAL_ITEM_MISSING,
    INVALID_CANONICAL_PERSISTED_STATE,
    INVALID_DOMAIN_PERSISTED_STATE,
    INTERNAL_READ_FAILURE,
}
