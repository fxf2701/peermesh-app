package app.peermesh.map.application.creation

sealed interface MapCreationSubmissionResult {
    val itemId: String?

    data class Accepted(
        override val itemId: String,
        val delivery: MapCreationDeliveryState,
    ) : MapCreationSubmissionResult

    data class AlreadyRecorded(override val itemId: String) : MapCreationSubmissionResult
    data class InvalidInput(val failure: MapCreationInputFailure) : MapCreationSubmissionResult {
        override val itemId: String? = null
    }
    data class Rejected(
        override val itemId: String?,
        val reason: MapCreationRejection,
    ) : MapCreationSubmissionResult
    data class Failed(
        override val itemId: String?,
        val reason: MapCreationSubmissionFailure,
    ) : MapCreationSubmissionResult
}

enum class MapCreationDeliveryState {
    ENQUEUED,
    MISSING_GROUP_KEY,
    ENQUEUE_REJECTED,
    TRANSPORT_FAILURE,
}

enum class MapCreationRejection {
    NOT_CURRENT_MEMBER,
    INVALID_CATEGORY,
    INVALID_TARGET_STATE,
    STRUCTURAL_REJECTION,
    CONFLICTING_DUPLICATE,
    UNKNOWN,
}

enum class MapCreationSubmissionFailure {
    INVALID_LOCAL_STATE,
    INVALID_CONSTRUCTED_EVENT,
    PERSISTENCE_FAILURE,
    INTERNAL_FAILURE,
}
