package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "dtn_advertised",
    primaryKeys = ["peer_id", "gid", "event_id"],
    indices = [
        Index(value = ["gid", "event_id"]),
        Index(value = ["peer_id", "gid", "advertised_at"]),
        Index(value = ["expires_at"]),
    ],
)
data class DtnAdvertisedEntity(
    val peer_id: String,
    val gid: String,
    val event_id: String,
    val advertised_at: Long,
    val expires_at: Long?,
)
