package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "dtn_peer_state",
    primaryKeys = ["peer_id", "gid"],
    indices = [
        Index(value = ["peer_id", "gid", "last_seen_at"]),
        Index(value = ["gid", "last_seen_at"]),
    ],
)
data class DtnPeerStateEntity(
    val peer_id: String,
    val gid: String,
    val last_seen_at: Long,
    val last_session_id: String?,
    val last_sync_at: Long?,
    val fail_count: Int,
    val backoff_until: Long?,
)
