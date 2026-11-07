package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "nearby_room_relay_offers",
    primaryKeys = ["room_id", "message_compact_id", "target_device_id"],
    indices = [
        Index(value = ["state", "offered_at_ms"]),
        Index(value = ["expires_at_ms"]),
    ],
)
data class NearbyRoomRelayOfferEntity(
    val room_id: String,
    val message_compact_id: String,
    val target_device_id: String,
    val state: String,
    val offered_at_ms: Long,
    val expires_at_ms: Long,
) {
    companion object {
        const val STATE_PENDING = "PENDING"
        const val STATE_SENT = "SENT"
        const val STATE_FAILED = "FAILED"
    }
}
