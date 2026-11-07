package app.peermesh.data.db.entity

import androidx.room.Entity

@Entity(
    tableName = "nearby_room_invite_quotas",
    primaryKeys = ["room_id", "sender_device_id", "target_device_id"],
)
data class NearbyRoomInviteQuotaEntity(
    val room_id: String,
    val sender_device_id: String,
    val target_device_id: String,
    val sent_count: Int,
    val last_sent_at_ms: Long,
    val blocked_after_decline: Boolean,
)
