package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "event_dedupe",
    primaryKeys = ["gid", "event_id"],
    indices = [
        Index(value = ["expires_at"]),
    ],
)
data class EventDedupeEntity(
    val gid: String,
    val event_id: String,
    val seen_at: Long,
    val expires_at: Long,
)

