package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_feedback",
    indices = [
        Index(value = ["deliveryState", "createdAtMs"]),
    ]
)
data class PendingFeedbackEntity(
    @PrimaryKey val feedbackId: String,
    val category: String,
    val feedbackType: String,
    val message: String,
    val createdAtMs: Long,
    val appVersion: String?,
    val buildNumber: String?,
    val androidVersion: String?,
    val deviceModel: String?,
    val localDeviceId: String?,
    val source: String,
    val deliveryState: String,
    val attemptCount: Int,
    val lastAttemptAtMs: Long?,
    val deliveredAtMs: Long?,
)
