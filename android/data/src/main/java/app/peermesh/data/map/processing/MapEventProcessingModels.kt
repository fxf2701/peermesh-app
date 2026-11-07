package app.peermesh.data.map.processing

import app.peermesh.core.model.EventCore

/**
 * M2C1 input to the shared DB-aware map processing seam.
 *
 * [core] remains the original accepted wire event. M2C1 must not reduce an adjusted copy while
 * persisting different ordering inputs.
 */
data class MapEventProcessingRequest(
    val gid: String,
    val core: EventCore,
    val source: String,
    val insertedAtSeconds: Long,
    val localActorDid: String? = null,
)

/** Stable machine-readable semantic rejection values owned by M2C1. */
enum class MapEventRejectionReason(val persistedValue: String) {
    INVALID_REQUEST("invalid_request"),
    UNSUPPORTED_MAP_EVENT("unsupported_map_event"),
    DEDICATED_ACTION_NOT_ENABLED("dedicated_action_not_enabled"),
    ACTOR_NOT_MEMBER("actor_not_member"),
    LOCAL_MEMBER_REMOVED("local_member_removed"),
    TARGET_TYPE_CONFLICT("target_type_conflict"),
    TARGET_NOT_FOUND("target_not_found"),
    CONFLICTING_ADD("conflicting_add"),
    OWNER_REQUIRED("owner_required"),
    OWNER_EDIT_WINDOW_CLOSED("owner_edit_window_closed"),
    INTERACTION_WINDOW_CLOSED("interaction_window_closed"),
    TARGET_EXPIRED("target_expired"),
    TARGET_DELETED("target_deleted"),
    TARGET_CATEGORY_UNRESOLVED("target_category_unresolved"),
    ACTION_NOT_ALLOWED_FOR_CATEGORY("action_not_allowed_for_category"),
    TARGET_RESOLVED("target_resolved"),
    INVALID_ACTION_PAYLOAD("invalid_action_payload"),
    ACTION_PERSISTENCE_INCONSISTENT("action_persistence_inconsistent"),
    INVALID_LEGACY_CONTRIBUTION("invalid_legacy_contribution"),
    LIFECYCLE_REACTIVATION("lifecycle_reactivation"),
    STALE_CANONICAL_EVENT("stale_canonical_event"),
    INVALID_PERSISTED_STATE("invalid_persisted_state"),
}

/**
 * Typed result returned by the transaction owner. Transport enqueue is deliberately outside this
 * result and outside the Room transaction.
 */
sealed interface MapEventProcessingResult {
    data class Accepted(val eventId: String) : MapEventProcessingResult
    data class AcceptedNoChange(val eventId: String) : MapEventProcessingResult
    data class AcceptedDeferred(val eventId: String, val rootEventId: String?) : MapEventProcessingResult
    data class ExactDuplicate(val eventId: String) : MapEventProcessingResult
    data class ConflictingDuplicate(val eventId: String) : MapEventProcessingResult
    data class Rejected(
        val eventId: String?,
        val reason: MapEventRejectionReason,
    ) : MapEventProcessingResult
    data class InvalidPersistedState(val eventId: String, val detail: String) : MapEventProcessingResult
    data class PersistenceFailure(val eventId: String?, val cause: Throwable) : MapEventProcessingResult
}
