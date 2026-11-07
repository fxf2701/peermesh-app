package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "nearby_rooms",
    indices = [
        Index(value = ["state", "expires_at_ms"]),
        Index(value = ["last_seen_at_ms"]),
    ],
)
data class NearbyRoomEntity(
    @PrimaryKey val room_id: String,
    val name: String,
    val owner_device_id: String,
    val owner_display_name: String?,
    val created_at_ms: Long,
    val expires_at_ms: Long,
    val closed_at_ms: Long?,
    val state: String,
    val participant_count: Int?,
    val is_local_owner: Boolean,
    val last_seen_at_ms: Long,
    val source: String,
    val metadata_status: String,
    val metadata_last_fetched_at: Long?,
    val metadata_source_device_id: String?,
) {
    companion object {
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_EXPIRED = "EXPIRED"
        const val STATE_CLOSED = "CLOSED"

        const val SOURCE_LOCAL = "LOCAL"
        const val SOURCE_BLE = "BLE"

        const val METADATA_PENDING = "PENDING"
        const val METADATA_RESOLVED = "RESOLVED"
        const val METADATA_FAILED = "FAILED"
    }
}
