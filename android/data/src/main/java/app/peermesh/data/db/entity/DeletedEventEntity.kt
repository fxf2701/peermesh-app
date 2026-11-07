package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "deleted_events",
    primaryKeys = ["gid", "target_event_id"],
    indices = [
        Index(value = ["gid", "expires_at"]),
        Index(value = ["gid", "delete_event_id"]),
    ]
)
data class DeletedEventEntity(
    val gid: String,
    val target_event_id: String,
    val delete_event_id: String,
    val actor_did: String,
    val deleted_at: Long,
    val expires_at: Long,
    val target_created_t: Long?,
    val delete_created_t: Long,
    val last_event_t: Long,
    val last_event_lt: Long,
    val last_event_actor: String,
)
