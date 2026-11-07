package app.peermesh.data.db.entity

import androidx.room.Entity

@Entity(
    tableName = "relay_push_state",
    primaryKeys = ["relay_url"]
)
data class RelayPushStateEntity(
    val relay_url: String,
    val last_push_row_id: Long,
    val last_push_at: Long,
    val last_push_event_id: String,
    val updated_at: Long,
)
