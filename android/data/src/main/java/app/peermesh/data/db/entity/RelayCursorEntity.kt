package app.peermesh.data.db.entity

import androidx.room.Entity

@Entity(
    tableName = "relay_cursors",
    primaryKeys = ["relay_url", "gid"]
)
data class RelayCursorEntity(
    val relay_url: String,
    val gid: String,
    val cursor: String,
    val updated_at: Long,
)
