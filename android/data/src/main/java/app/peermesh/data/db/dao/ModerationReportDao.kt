package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.PendingModerationReportEntity

@Dao
interface ModerationReportDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: PendingModerationReportEntity)

    @Query(
        """
        SELECT * FROM moderation_reports
        WHERE deliveryState IN ('pending', 'retry_waiting')
        ORDER BY createdAtMs ASC
        LIMIT :limit
        """
    )
    fun listPending(limit: Int): List<PendingModerationReportEntity>

    @Query(
        """
        UPDATE moderation_reports
        SET deliveryState = :deliveryState,
            attemptCount = :attemptCount,
            lastAttemptAtMs = :lastAttemptAtMs,
            deliveredAtMs = :deliveredAtMs
        WHERE reportId = :reportId
        """
    )
    fun updateDeliveryState(
        reportId: String,
        deliveryState: String,
        attemptCount: Int,
        lastAttemptAtMs: Long?,
        deliveredAtMs: Long?,
    )
}
