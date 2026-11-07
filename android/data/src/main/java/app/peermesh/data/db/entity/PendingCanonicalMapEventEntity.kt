package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Durable canonical map mutation whose immutable ADD dependency is not available locally yet.
 *
 * The exact wire event remains in [EventEntity]; this row owns only dependency/replay state.
 */
@Entity(
    tableName = "pending_canonical_map_events",
    primaryKeys = ["gid", "event_id"],
    indices = [
        Index(value = ["gid", "item_type", "item_id", "materialization_state"]),
        Index(value = ["root_event_id"]),
        Index(value = ["expires_at"]),
    ],
)
data class PendingCanonicalMapEventEntity(
    val gid: String,
    val event_id: String,
    val item_type: String,
    val item_id: String,
    val root_event_id: String?,
    val event_t: Long,
    val event_lt: Long,
    val actor_did: String,
    val event_fingerprint: String,
    val materialization_state: String,
    val rejection_reason: String?,
    val inserted_at: Long,
    val updated_at: Long,
    val expires_at: Long,
    val replay_attempt_count: Int,
)

object PendingCanonicalMapEventState {
    const val PENDING_MISSING_CANONICAL = "pending_missing_canonical"
    const val APPLIED = "applied"
    const val REJECTED = "rejected"
}
