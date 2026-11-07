package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "moderation_reports",
    indices = [
        Index(value = ["deliveryState", "createdAtMs"]),
        Index(value = ["groupId", "reportedPeerId"]),
    ]
)
data class PendingModerationReportEntity(
    @PrimaryKey val reportId: String,
    val groupId: String,
    val reporterPeerId: String,
    val reportedPeerId: String,
    val targetType: String,
    val messageId: String?,
    val eventId: String?,
    val reasonCode: String,
    val userNote: String?,
    val includePlaintext: Boolean,
    val plaintextExcerpt: String?,
    val ciphertextRef: String?,
    val deliveryState: String,
    val attemptCount: Int,
    val createdAtMs: Long,
    val lastAttemptAtMs: Long?,
    val deliveredAtMs: Long?,
)
