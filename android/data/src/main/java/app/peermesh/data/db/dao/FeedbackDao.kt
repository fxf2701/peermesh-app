package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.PendingFeedbackEntity

@Dao
interface FeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: PendingFeedbackEntity)

    @Query("SELECT * FROM pending_feedback WHERE feedbackId = :feedbackId LIMIT 1")
    fun findById(feedbackId: String): PendingFeedbackEntity?

    @Query(
        """
        SELECT * FROM pending_feedback
        WHERE deliveryState IN ('pending', 'retry_waiting')
        ORDER BY createdAtMs ASC
        LIMIT :limit
        """
    )
    fun listPending(limit: Int): List<PendingFeedbackEntity>

    @Query(
        """
        UPDATE pending_feedback
        SET deliveryState = :deliveryState,
            attemptCount = :attemptCount,
            lastAttemptAtMs = :lastAttemptAtMs,
            deliveredAtMs = :deliveredAtMs
        WHERE feedbackId = :feedbackId
        """
    )
    fun updateDeliveryState(
        feedbackId: String,
        deliveryState: String,
        attemptCount: Int,
        lastAttemptAtMs: Long?,
        deliveredAtMs: Long?,
    )
}
