package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "outbound_batch_peer_delivery",
    primaryKeys = ["batch_id", "device_id"],
    foreignKeys = [
        ForeignKey(
            entity = OutboundBatchEntity::class,
            parentColumns = ["batch_id"],
            childColumns = ["batch_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["batch_id", "state"]),
        Index(value = ["device_id", "state"]),
        Index(value = ["state", "next_retry_at"]),
    ],
)
data class OutboundBatchPeerDeliveryEntity(
    val batch_id: String,
    val device_id: String,
    val state: String,
    val attempt_count: Int,
    val last_error: String?,
    val last_attempt_at: Long?,
    val next_retry_at: Long?,
    val delivered_at: Long?,
    val updated_at: Long,
)
