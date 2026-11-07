package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "group_peer_routes",
    primaryKeys = ["gid", "actor_did", "device_id"],
    indices = [
        Index(value = ["gid", "actor_did", "updated_at"]),
        Index(value = ["gid", "device_id", "updated_at"]),
    ],
)
data class GroupPeerRouteEntity(
    val gid: String,
    val actor_did: String,
    val device_id: String,
    val first_seen_at: Long,
    val updated_at: Long,
)
