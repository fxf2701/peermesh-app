package app.peermesh.map.application.interaction

import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemDetailLoadResult

fun interface EvaluateMapItemInteractions {
    fun evaluate(detailResult: MapItemDetailLoadResult): MapItemInteractionsAvailability
}

class DefaultEvaluateMapItemInteractions(
    private val participationProvider: LocalMapParticipationProvider,
    private val localSupportProvider: LocalMapInteractionSupportProvider,
    private val peerCapabilityProvider: MapPeerCapabilityProvider,
    private val clock: MapAvailabilityClock,
    private val evaluator: MapInteractionAvailabilityEvaluator,
) : EvaluateMapItemInteractions {
    override fun evaluate(detailResult: MapItemDetailLoadResult): MapItemInteractionsAvailability {
        val target = normalize(detailResult)
        if (target !is MapInteractionTargetState.Available) {
            return evaluator.evaluate(safeInput(target, 0L))
        }
        val identity = target.detail.identity
        val now = try {
            clock.nowSeconds()
        } catch (_: Exception) {
            return evaluator.evaluate(safeInput(MapInteractionTargetState.InternalReadFailure, 0L))
        }

        val participation = try {
            participationProvider.participationFor(identity.groupId)
        } catch (_: Exception) {
            LocalMapParticipation.Unavailable
        }
        val support = try {
            localSupportProvider.supportFor(identity.groupId)
        } catch (_: Exception) {
            LocalMapInteractionSupport(emptySet())
        }
        val capability = try {
            peerCapabilityProvider.capabilityFor(identity.groupId)
        } catch (_: Exception) {
            MapPeerCapabilitySummary(MapPeerCapabilityLevel.UNAVAILABLE)
        }
        return evaluator.evaluate(MapItemInteractionEvaluationInput(target, participation, support, capability, now))
    }

    private fun safeInput(target: MapInteractionTargetState, now: Long) = MapItemInteractionEvaluationInput(
        target = target,
        participation = LocalMapParticipation.Unavailable,
        localSupport = LocalMapInteractionSupport(emptySet()),
        peerCapability = MapPeerCapabilitySummary(MapPeerCapabilityLevel.UNAVAILABLE),
        nowSeconds = now,
    )

    private fun normalize(result: MapItemDetailLoadResult): MapInteractionTargetState = when (result) {
        is MapItemDetailLoadResult.Found -> normalize(result.detail)
        is MapItemDetailLoadResult.UnresolvedLegacy -> normalize(result.detail)
        is MapItemDetailLoadResult.CanonicalOnly -> MapInteractionTargetState.DomainStateMissing
        is MapItemDetailLoadResult.InvalidIdentity -> MapInteractionTargetState.InvalidIdentity
        MapItemDetailLoadResult.CanonicalItemMissing -> MapInteractionTargetState.CanonicalMissing
        MapItemDetailLoadResult.InvalidCanonicalPersistedState -> MapInteractionTargetState.InvalidCanonicalState
        is MapItemDetailLoadResult.InvalidDomainPersistedState -> MapInteractionTargetState.InvalidDomainState
        MapItemDetailLoadResult.InternalReadFailure -> MapInteractionTargetState.InternalReadFailure
    }

    private fun normalize(detail: MapItemDetail): MapInteractionTargetState {
        if (detail.canonical.identity.itemType != detail.identity.itemType) {
            return MapInteractionTargetState.TargetTypeConflict
        }
        if (detail.canonical.identity != detail.identity) {
            return MapInteractionTargetState.InvalidCanonicalState
        }
        val domain = detail.domain ?: return MapInteractionTargetState.DomainStateMissing
        val temporal = if (domain.temporalOrigin == MapItemTemporalOrigin.LEGACY_UNRESOLVED) {
            MapInteractionTemporalState.Unresolved
        } else {
            MapInteractionTemporalState.Resolved(
                domain.windows.expiresAt,
                domain.windows.ownerEditUntil,
                domain.windows.interactionUntil,
            )
        }
        return MapInteractionTargetState.Available(MapItemAvailabilityDetail(
            identity = detail.identity,
            canonicalAvailability = detail.canonical.availability,
            category = domain.category,
            lifecycle = domain.lifecycle,
            temporalState = temporal,
        ))
    }
}
