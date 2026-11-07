package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "outbound_batch",
    indices = [
        Index(value = ["status", "lane", "created_at"]),
        Index(value = ["gid", "status", "lane", "created_at"]),
        Index(value = ["locked_at"]),
    ],
)
data class OutboundBatchEntity(
    @PrimaryKey val batch_id: String,
    val gid: String,
    val created_at: Long,
    val updated_at: Long,
    val status: String,
    val lane: String,
    val priority: Int,
    val attempt_count: Int,
    val last_error: String?,
    val locked_at: Long?,
    val sent_at: Long?,
    val payload: ByteArray,
    val event_count: Int,
    val first_event_created_t: Long,
    val last_event_created_t: Long,
    val source: String,
)
