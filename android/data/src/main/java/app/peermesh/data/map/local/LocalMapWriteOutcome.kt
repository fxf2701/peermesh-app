package app.peermesh.data.map.local

import app.peermesh.data.map.processing.MapEventRejectionReason

/**
 * M2C4B compatibility outcomes consumed by the app layer.
 *
 * These outcomes deliberately contain no Room entities, reducer state, replay details, projection
 * details, BLE types, or relay types.
 */
sealed interface LocalMapWriteOutcome {
    /** Newly committed durable event. This is the only outcome eligible for post-commit transport. */
    data class AcceptedNew(val eventId: String) : LocalMapWriteOutcome

    /** Processor accepted the canonical operation but inserted no new durable event/state change. */
    data class AcceptedNoChange(val eventId: String) : LocalMapWriteOutcome

    /** Same identity and same persisted semantic content. Must not be enqueued as newly accepted. */
    data class ExactDuplicate(val eventId: String) : LocalMapWriteOutcome

    /** Same identity with conflicting semantic content. Must not be enqueued. */
    data class ConflictingDuplicate(val eventId: String) : LocalMapWriteOutcome

    /** Stable semantic rejection suitable for diagnostics and app-safe feedback mapping. */
    data class SemanticRejected(
        val eventId: String?,
        val reason: MapEventRejectionReason,
    ) : LocalMapWriteOutcome

    /** Persisted data violated an accepted invariant. Keep internal detail out of user-facing text. */
    data class InvalidPersistedState(
        val eventId: String,
        val detail: String,
    ) : LocalMapWriteOutcome

    /** Shared processor transaction failed and rolled back. */
    data class PersistenceFailure(
        val eventId: String?,
        val cause: Throwable,
    ) : LocalMapWriteOutcome
}

/** True only when the event was newly committed and may cross the post-commit transport boundary. */
val LocalMapWriteOutcome.isNewlyAcceptedForTransport: Boolean
    get() = this is LocalMapWriteOutcome.AcceptedNew
