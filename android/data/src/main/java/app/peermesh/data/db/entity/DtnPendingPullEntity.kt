package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "dtn_pending_pull",
    primaryKeys = ["peer_id", "gid", "event_id"],
    indices = [
        Index(value = ["gid", "event_id"]),
        Index(value = ["peer_id", "gid", "next_retry_at"]),
        Index(value = ["expires_at"]),
    ],
)
data class DtnPendingPullEntity(
    val peer_id: String,
    val gid: String,
    val event_id: String,
    val requested_at: Long,
    val next_retry_at: Long?,
    val attempt_count: Int,
    val expires_at: Long?,
)
