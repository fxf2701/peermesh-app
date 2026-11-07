package app.peermesh.map.application.interaction

import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemIdentity

sealed interface MapInteractionTargetState {
    data class Available(val detail: MapItemAvailabilityDetail) : MapInteractionTargetState
    data object InvalidIdentity : MapInteractionTargetState
    data object CanonicalMissing : MapInteractionTargetState
    data object TargetTypeConflict : MapInteractionTargetState
    data object InvalidCanonicalState : MapInteractionTargetState
    data object DomainStateMissing : MapInteractionTargetState
    data object InvalidDomainState : MapInteractionTargetState
    data object InternalReadFailure : MapInteractionTargetState
}

sealed interface MapInteractionTemporalState {
    data class Resolved(
        val expiresAtSeconds: Long?,
        val ownerEditUntilSeconds: Long?,
        val interactionUntilSeconds: Long?,
    ) : MapInteractionTemporalState

    data object Unresolved : MapInteractionTemporalState
}

data class MapItemAvailabilityDetail(
    val identity: MapItemIdentity,
    val canonicalAvailability: MapItemCanonicalAvailability,
    val category: MapItemCategory?,
    val lifecycle: MapSituationLifecycleStatus?,
    val temporalState: MapInteractionTemporalState,
)

data class MapItemInteractionEvaluationInput(
    val target: MapInteractionTargetState,
    val participation: LocalMapParticipation,
    val localSupport: LocalMapInteractionSupport,
    val peerCapability: MapPeerCapabilitySummary,
    val nowSeconds: Long,
)
