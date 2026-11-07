package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["gid", "event_id"], unique = true),
        Index(value = ["gid", "created_t"]),
        Index(value = ["gid", "type"])
    ]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gid: String,
    val event_id: String,
    val type: String,
    val source: String,
    // UTC epoch seconds (based on EventCore.time.t).
    val created_t: Long,
    // Lamport time / logical clock (not timezone-based).
    val time_lt: Long,
    val actor_did: String,
    val ref_root: String?,
    val ref_parent: String?,
    val ref_target: String?,
    val ttl_s: Long?,
    val payload_json: String,
    // UTC epoch seconds (insert time).
    val inserted_at: Long,
    // UTC epoch seconds when marked invalid (null if valid).
    val invalid_at: Long? = null,
    // Reason why the event was marked invalid.
    val invalid_reason: String? = null,
)
