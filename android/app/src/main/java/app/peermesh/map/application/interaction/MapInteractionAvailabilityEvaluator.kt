package app.peermesh.map.application.interaction

import app.peermesh.core.map.MapItemActionPolicy
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.map.application.detail.MapItemCanonicalAvailability

interface MapInteractionAvailabilityEvaluator {
    fun evaluate(input: MapItemInteractionEvaluationInput): MapItemInteractionsAvailability
}

/** Pure M3A2 advisory production evaluator; it has no Room, transport, event or submission access. */
class DefaultMapInteractionAvailabilityEvaluator : MapInteractionAvailabilityEvaluator {
    override fun evaluate(input: MapItemInteractionEvaluationInput): MapItemInteractionsAvailability {
        val identity = (input.target as? MapInteractionTargetState.Available)?.detail?.identity
        return MapItemInteractionsAvailability(
            identity = identity,
            interactions = MapInteractionType.all.map { evaluate(it, input) },
        )
    }

    /**
     * First-match priority: identity, canonical read, domain/category, compatibility, deletion,
     * lifecycle, temporal (unresolved/expiry/cutoff), participation, local support, capability.
     */
    private fun evaluate(
        interaction: MapInteractionType,
        input: MapItemInteractionEvaluationInput,
    ): MapItemInteractionAvailability {
        when (input.target) {
            MapInteractionTargetState.InvalidIdentity -> return blocked(interaction, MapInteractionAvailabilityReason.INVALID_TARGET_IDENTITY)
            MapInteractionTargetState.CanonicalMissing -> return blocked(interaction, MapInteractionAvailabilityReason.CANONICAL_TARGET_MISSING)
            MapInteractionTargetState.TargetTypeConflict -> return blocked(interaction, MapInteractionAvailabilityReason.TARGET_TYPE_CONFLICT)
            MapInteractionTargetState.InvalidCanonicalState -> return unresolved(interaction, MapInteractionAvailabilityReason.INVALID_CANONICAL_STATE)
            MapInteractionTargetState.DomainStateMissing -> return unresolved(interaction, MapInteractionAvailabilityReason.DOMAIN_STATE_MISSING)
            MapInteractionTargetState.InvalidDomainState -> return unresolved(interaction, MapInteractionAvailabilityReason.INVALID_DOMAIN_STATE)
            MapInteractionTargetState.InternalReadFailure -> return unresolved(interaction, MapInteractionAvailabilityReason.INTERNAL_READ_FAILURE)
            is MapInteractionTargetState.Available -> Unit
        }
        val detail = input.target.detail
        val category = detail.category
            ?: return unresolved(interaction, MapInteractionAvailabilityReason.CATEGORY_UNRESOLVED)

        if (!MapItemActionPolicy.allows(category, interaction.m2Action)) {
            return hidden(interaction, MapInteractionAvailabilityReason.ACTION_INCOMPATIBLE_WITH_CATEGORY)
        }
        if (detail.canonicalAvailability == MapItemCanonicalAvailability.DELETED) {
            return blocked(interaction, MapInteractionAvailabilityReason.TARGET_DELETED)
        }

        when (category) {
            MapItemCategory.SITUATION -> when (detail.lifecycle) {
                null -> return unresolved(interaction, MapInteractionAvailabilityReason.SITUATION_LIFECYCLE_UNRESOLVED)
                MapSituationLifecycleStatus.ACTIVE -> Unit
                MapSituationLifecycleStatus.NO_LONGER_ACTIVE -> return if (
                    interaction == MapInteractionType.Situation.MarkNoLongerActive
                ) hidden(interaction, MapInteractionAvailabilityReason.SITUATION_ALREADY_RESOLVED)
                else blocked(interaction, MapInteractionAvailabilityReason.SITUATION_RESOLVED)
            }
            MapItemCategory.COORDINATION -> if (detail.lifecycle != null) {
                return unresolved(interaction, MapInteractionAvailabilityReason.UNEXPECTED_COORDINATION_LIFECYCLE)
            }
        }

        val temporal = detail.temporalState
        if (temporal is MapInteractionTemporalState.Unresolved) {
            return unresolved(interaction, MapInteractionAvailabilityReason.TEMPORAL_STATE_UNRESOLVED)
        }
        temporal as MapInteractionTemporalState.Resolved
        if (temporal.expiresAtSeconds != null && input.nowSeconds >= temporal.expiresAtSeconds) {
            return blocked(interaction, MapInteractionAvailabilityReason.TARGET_EXPIRED)
        }
        if (temporal.interactionUntilSeconds != null && input.nowSeconds >= temporal.interactionUntilSeconds) {
            return blocked(interaction, MapInteractionAvailabilityReason.INTERACTION_WINDOW_CLOSED)
        }

        when (input.participation) {
            LocalMapParticipation.CurrentMember -> Unit
            LocalMapParticipation.NotMember -> return blocked(interaction, MapInteractionAvailabilityReason.LOCAL_ACTOR_NOT_MEMBER)
            LocalMapParticipation.Unknown -> return blocked(interaction, MapInteractionAvailabilityReason.LOCAL_MEMBERSHIP_UNKNOWN)
            LocalMapParticipation.Unavailable -> return blocked(interaction, MapInteractionAvailabilityReason.LOCAL_MEMBERSHIP_UNAVAILABLE)
        }
        if (!input.localSupport.supports(interaction)) {
            return result(interaction, MapInteractionAvailabilityState.UNSUPPORTED_LOCALLY,
                MapInteractionAvailabilityReason.LOCAL_FEATURE_UNSUPPORTED)
        }
        return when (input.peerCapability.level) {
            MapPeerCapabilityLevel.SUPPORTED -> result(interaction, MapInteractionAvailabilityState.ENABLED)
            MapPeerCapabilityLevel.MIXED -> compatible(interaction, MapInteractionCapabilityWarning.PEER_SUPPORT_MIXED)
            MapPeerCapabilityLevel.UNKNOWN -> compatible(interaction, MapInteractionCapabilityWarning.PEER_SUPPORT_UNKNOWN)
            MapPeerCapabilityLevel.UNAVAILABLE -> compatible(interaction, MapInteractionCapabilityWarning.PEER_CAPABILITY_UNAVAILABLE)
        }
    }

    private fun compatible(interaction: MapInteractionType, warning: MapInteractionCapabilityWarning) =
        result(interaction, MapInteractionAvailabilityState.COMPATIBILITY_LIMITED, warning = warning)

    private fun blocked(interaction: MapInteractionType, reason: MapInteractionAvailabilityReason) =
        result(interaction, MapInteractionAvailabilityState.DISABLED, reason)

    private fun hidden(interaction: MapInteractionType, reason: MapInteractionAvailabilityReason) =
        result(interaction, MapInteractionAvailabilityState.HIDDEN, reason)

    private fun unresolved(interaction: MapInteractionType, reason: MapInteractionAvailabilityReason) =
        result(interaction, MapInteractionAvailabilityState.UNAVAILABLE_UNRESOLVED, reason)

    private fun result(
        interaction: MapInteractionType,
        state: MapInteractionAvailabilityState,
        reason: MapInteractionAvailabilityReason? = null,
        warning: MapInteractionCapabilityWarning? = null,
    ) = MapItemInteractionAvailability(interaction, state, reason, capabilityWarning = warning)
}
