package app.peermesh.map.application.scene

import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemDomainPresentation
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult

data class MapSceneItem(
    val identity: MapItemIdentity,
    val normalizedKind: String?,
    val label: String?,
    val note: String?,
    val availability: MapItemCanonicalAvailability,
    val expiresAt: Long?,
    val geometry: MapSceneGeometry,
    val domain: MapItemDomainPresentation?,
    val temporal: MapSceneTemporalPresentation,
    val verification: MapItemVerificationProjectionResult,
    val participation: MapCoordinationParticipationProjectionResult,
)

sealed interface MapSceneTemporalPresentation {
    data object Current : MapSceneTemporalPresentation
    data object Expired : MapSceneTemporalPresentation
    data object InteractionClosed : MapSceneTemporalPresentation
    data object Resolved : MapSceneTemporalPresentation
    data object Deleted : MapSceneTemporalPresentation
    data object Unresolved : MapSceneTemporalPresentation
}
