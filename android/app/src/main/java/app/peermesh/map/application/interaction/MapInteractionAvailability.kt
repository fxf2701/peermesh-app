package app.peermesh.map.application.interaction

import app.peermesh.map.application.detail.MapItemIdentity

enum class MapInteractionAvailabilityState {
    ENABLED,
    DISABLED,
    HIDDEN,
    UNSUPPORTED_LOCALLY,
    COMPATIBILITY_LIMITED,
    UNAVAILABLE_UNRESOLVED,
}

enum class MapInteractionAvailabilityReason {
    INVALID_TARGET_IDENTITY,
    CANONICAL_TARGET_MISSING,
    TARGET_TYPE_CONFLICT,
    INVALID_CANONICAL_STATE,
    DOMAIN_STATE_MISSING,
    INVALID_DOMAIN_STATE,
    INTERNAL_READ_FAILURE,
    CATEGORY_UNRESOLVED,
    ACTION_INCOMPATIBLE_WITH_CATEGORY,
    TARGET_DELETED,
    SITUATION_LIFECYCLE_UNRESOLVED,
    SITUATION_RESOLVED,
    SITUATION_ALREADY_RESOLVED,
    UNEXPECTED_COORDINATION_LIFECYCLE,
    TEMPORAL_STATE_UNRESOLVED,
    TARGET_EXPIRED,
    INTERACTION_WINDOW_CLOSED,
    LOCAL_ACTOR_NOT_MEMBER,
    LOCAL_MEMBERSHIP_UNKNOWN,
    LOCAL_MEMBERSHIP_UNAVAILABLE,
    LOCAL_FEATURE_UNSUPPORTED,
}

enum class MapInteractionCapabilityWarning {
    PEER_SUPPORT_MIXED,
    PEER_SUPPORT_UNKNOWN,
    PEER_CAPABILITY_UNAVAILABLE,
}

data class MapItemInteractionAvailability(
    val interaction: MapInteractionType,
    val state: MapInteractionAvailabilityState,
    val primaryReason: MapInteractionAvailabilityReason? = null,
    val additionalReasons: Set<MapInteractionAvailabilityReason> = emptySet(),
    val capabilityWarning: MapInteractionCapabilityWarning? = null,
) {
    val isVisible: Boolean
        get() = state != MapInteractionAvailabilityState.HIDDEN &&
            state != MapInteractionAvailabilityState.UNSUPPORTED_LOCALLY

    val isEnabled: Boolean
        get() = state == MapInteractionAvailabilityState.ENABLED ||
            state == MapInteractionAvailabilityState.COMPATIBILITY_LIMITED
}

data class MapItemInteractionsAvailability(
    val identity: MapItemIdentity?,
    val interactions: List<MapItemInteractionAvailability>,
)
