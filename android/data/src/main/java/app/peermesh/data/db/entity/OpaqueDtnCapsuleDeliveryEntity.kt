package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "opaque_dtn_capsule_delivery",
    primaryKeys = ["capsule_id", "device_id"],
    foreignKeys = [
        ForeignKey(
            entity = OpaqueDtnCapsuleEntity::class,
            parentColumns = ["capsule_id"],
            childColumns = ["capsule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["capsule_id", "state"]),
        Index(value = ["device_id", "state"]),
        Index(value = ["state", "next_retry_at"]),
    ],
)
data class OpaqueDtnCapsuleDeliveryEntity(
    val capsule_id: String,
    val device_id: String,
    val state: String,
    val attempt_count: Int,
    val last_error: String?,
    val last_attempt_at: Long?,
    val next_retry_at: Long?,
    val delivered_at: Long?,
    val updated_at: Long,
)

object OpaqueDtnCapsuleDeliveryState {
    const val IN_FLIGHT = "IN_FLIGHT"
    const val DELIVERED = "DELIVERED"
    const val FAILED_RETRYABLE = "FAILED_RETRYABLE"
    const val FAILED_FINAL = "FAILED_FINAL"
}