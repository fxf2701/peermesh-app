package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "outbound_batch_event",
    primaryKeys = ["batch_id", "event_id"],
    foreignKeys = [
        ForeignKey(
            entity = OutboundBatchEntity::class,
            parentColumns = ["batch_id"],
            childColumns = ["batch_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["batch_id", "ordinal_in_batch"]),
        Index(value = ["event_id"]),
    ],
)
data class OutboundBatchEventEntity(
    val batch_id: String,
    val event_id: String,
    val created_t: Long,
    val ordinal_in_batch: Int,
)
