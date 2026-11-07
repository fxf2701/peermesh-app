package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "nearby_room_participants",
    primaryKeys = ["room_id", "device_id"],
    indices = [
        Index(value = ["room_id", "status"]),
    ],
)
data class NearbyRoomParticipantEntity(
    val room_id: String,
    val device_id: String,
    val display_name: String?,
    val avatar_id: String?,
    val profile_updated_at_ms: Long?,
    val joined_at_ms: Long,
    val last_seen_at_ms: Long,
    val role: String,
    val status: String,
    val invitation_blocked: Boolean,
    val recipient_encryption_algorithm: String?,
    val recipient_encryption_key_id: String?,
    val recipient_encryption_public_key: ByteArray?,
) {
    companion object {
        const val ROLE_OWNER = "OWNER"
        const val ROLE_PARTICIPANT = "PARTICIPANT"

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_LEFT = "LEFT"
        const val STATUS_KICKED = "KICKED"
    }
}
