package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "user_profiles",
    primaryKeys = ["gid", "actor_did"],
    indices = [
        Index(value = ["gid", "updated_at"])
    ]
)
data class UserProfileEntity(
    val gid: String,
    val actor_did: String,
    val display_name: String,
    val avatar_id: String?,
    val updated_at: Long,
    val last_event_id: String,
    val last_event_t: Long,
    val last_event_lt: Long,
    val last_event_actor: String,
)
