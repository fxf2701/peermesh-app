package app.peermesh.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "nearby_room_messages",
    primaryKeys = ["room_id", "message_id"],
    indices = [
        Index(value = ["room_id", "created_at_ms"]),
        Index(value = ["room_id", "ttl_expires_at_ms"]),
    ],
)
data class NearbyRoomMessageEntity(
    val room_id: String,
    val message_id: String,
    val reply_to: String? = null,
    val owner_device_id: String?,
    val sender_device_id: String,
    val sender_display_name: String?,
    val body: String,
    val created_at_ms: Long,
    val received_at_ms: Long,
    val ttl_expires_at_ms: Long,
    val delivery_state: String,
    val edited_at_ms: Long?,
    val deleted_at_ms: Long?,
    val local_render_source_body: String? = null,
    @ColumnInfo(defaultValue = "0") val is_hidden_locally: Boolean = false,
) {
    override fun toString(): String =
        "NearbyRoomMessageEntity(room_id=$room_id, message_id=$message_id, reply_to=$reply_to, " +
            "owner_device_id=$owner_device_id, sender_device_id=$sender_device_id, " +
            "sender_display_name=$sender_display_name, body=[REDACTED], created_at_ms=$created_at_ms, " +
            "received_at_ms=$received_at_ms, ttl_expires_at_ms=$ttl_expires_at_ms, " +
            "delivery_state=$delivery_state, edited_at_ms=$edited_at_ms, deleted_at_ms=$deleted_at_ms, " +
            "local_render_source_body=[REDACTED], is_hidden_locally=$is_hidden_locally)"

    companion object {
        const val DELIVERY_LOCAL = "LOCAL"
        const val DELIVERY_SENT = "SENT"
        const val DELIVERY_RECEIVED = "RECEIVED"
        const val DELIVERY_SYSTEM = "SYSTEM"

        const val SYSTEM_SENDER_DEVICE_ID = "__nearby_room_system__"
    }
}
