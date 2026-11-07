package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "dtn_sync_cursor",
    primaryKeys = ["peer_id", "gid"],
    indices = [
        Index(value = ["gid", "updated_at"]),
    ],
)
data class DtnSyncCursorEntity(
    val peer_id: String,
    val gid: String,
    val last_inserted_at: Long,
    val last_event_id: String,
    val updated_at: Long,
)
