package app.peermesh.map.application.submission

sealed interface MapInteractionSubmissionResult {
    val refreshRecommended: Boolean

    data class Accepted(
        val eventId: String,
        val deliveryWarning: MapInteractionDeliveryWarning? = null,
        override val refreshRecommended: Boolean = true,
    ) : MapInteractionSubmissionResult

    data class AlreadyRecorded(
        val eventId: String,
        override val refreshRecommended: Boolean = true,
    ) : MapInteractionSubmissionResult

    data class Rejected(
        val eventId: String?,
        val reason: MapInteractionRejection,
        override val refreshRecommended: Boolean,
    ) : MapInteractionSubmissionResult

    data class InvalidInput(
        val failure: MapInteractionInputFailure,
        override val refreshRecommended: Boolean = false,
    ) : MapInteractionSubmissionResult

    data class Failed(
        val eventId: String?,
        val reason: MapInteractionSubmissionFailure,
        override val refreshRecommended: Boolean = false,
    ) : MapInteractionSubmissionResult
}

enum class MapInteractionDeliveryWarning {
    MISSING_GROUP_KEY,
    ENQUEUE_UNAVAILABLE,
    ENQUEUE_FAILED,
}

enum class MapInteractionSubmissionFailure {
    INVALID_LOCAL_STATE,
    INVALID_CONSTRUCTED_EVENT,
    CONFLICTING_DUPLICATE,
    INVALID_PERSISTED_STATE,
    PERSISTENCE_FAILURE,
    INTERNAL_FAILURE,
}

enum class MapInteractionRejection {
    NOT_CURRENT_MEMBER,
    TARGET_MISSING,
    TARGET_TYPE_CONFLICT,
    TARGET_DELETED,
    CATEGORY_UNRESOLVED,
    ACTION_INCOMPATIBLE,
    TARGET_EXPIRED,
    INTERACTION_WINDOW_CLOSED,
    TARGET_RESOLVED,
    LIFECYCLE_REACTIVATION_FORBIDDEN,
    INVALID_ACTION,
    OTHER,
}
