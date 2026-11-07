package app.peermesh.map.application.verification

import app.peermesh.map.application.detail.MapItemIdentity

/** M3C2 read-only semantic freshness presentation. */
data class MapVerificationFreshnessPresentation(
    val identity: MapItemIdentity,
    val state: MapVerificationFreshnessState,
)

sealed interface MapVerificationFreshnessState {
    data class Current(
        val actorCount: Int,
        val latestCurrentConfirmationAtSeconds: Long?,
    ) : MapVerificationFreshnessState {
        init {
            require(actorCount > 0) { "current confirmation actor count must be positive" }
            require(latestCurrentConfirmationAtSeconds == null || latestCurrentConfirmationAtSeconds >= 0L)
        }
    }

    data class Absent(
        val reason: MapVerificationAbsenceReason,
    ) : MapVerificationFreshnessState
}

enum class MapVerificationAbsenceReason {
    NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED,
    INVALIDATED_BY_SITUATION_CHANGE,
    INVALIDATED_BY_RESOLUTION,
    RESOLVED_WITHOUT_PRIOR_CONFIRMATION,
    UNKNOWN,
}
