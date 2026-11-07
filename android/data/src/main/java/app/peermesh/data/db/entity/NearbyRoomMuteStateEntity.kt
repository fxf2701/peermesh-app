package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "nearby_room_mute_states",
    primaryKeys = ["room_id", "target_device_id"],
    indices = [
        Index(value = ["room_id", "is_muted"]),
        Index(value = ["expires_at_ms"]),
    ],
)
data class NearbyRoomMuteStateEntity(
    val room_id: String,
    val target_device_id: String,
    val actor_device_id: String,
    val is_muted: Boolean,
    val updated_at_ms: Long,
    val expires_at_ms: Long,
)
