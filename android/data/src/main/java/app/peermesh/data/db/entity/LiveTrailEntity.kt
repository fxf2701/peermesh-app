package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "live_trails",
    primaryKeys = ["trail_id"],
    indices = [
        Index(value = ["gid", "updated_t"]),
        Index(value = ["gid", "owner_did"]),
        Index(value = ["gid", "active"])
    ]
)
data class LiveTrailEntity(
    val trail_id: String,
    val gid: String,
    val owner_did: String,
    val name: String?,
    val share_scope: String,
    val share_to_json: String?,
    val start_t: Long,
    val end_t: Long?,
    val point_count: Int,
    val dist_m: Float,
    val active: Int,
    val accepted: Int,
    val pending_expires_t: Long?,
    val updated_t: Long,
    val last_event_id: String,
    val last_event_t: Long,
    val last_event_lt: Long,
    val last_event_actor: String,
)
