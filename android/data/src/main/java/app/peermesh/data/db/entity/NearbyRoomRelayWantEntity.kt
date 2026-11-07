package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "nearby_room_relay_wants",
    primaryKeys = ["room_id", "message_compact_id"],
    indices = [
        Index(value = ["state", "updated_at_ms"]),
        Index(value = ["expires_at_ms"]),
    ],
)
data class NearbyRoomRelayWantEntity(
    val room_id: String,
    val message_compact_id: String,
    val state: String,
    val candidate_source_device_ids: String,
    val message_owner_device_id: String?,
    val full_message_id: String?,
    val attempt_count: Int,
    val last_requested_at_ms: Long?,
    val next_source_index: Int,
    val updated_at_ms: Long,
    val expires_at_ms: Long,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_REQUESTING = "REQUESTING"
        const val STATE_RESOLVED = "RESOLVED"
        const val STATE_FAILED = "FAILED"
    }
}
