package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.DtnPendingPullEntity

@Dao
interface DtnPendingPullDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<DtnPendingPullEntity>)

    @Query(
        """
        SELECT * FROM dtn_pending_pull
        WHERE peer_id = :peerId
          AND gid = :gid
          AND (next_retry_at IS NULL OR next_retry_at <= :nowSeconds)
          AND (expires_at IS NULL OR expires_at > :nowSeconds)
        ORDER BY requested_at ASC, event_id ASC
        LIMIT :limit
        """
    )
    fun listDue(peerId: String, gid: String, nowSeconds: Long, limit: Int): List<DtnPendingPullEntity>

    @Query("DELETE FROM dtn_pending_pull WHERE peer_id = :peerId AND gid = :gid AND event_id IN (:eventIds)")
    fun deleteDelivered(peerId: String, gid: String, eventIds: List<String>): Int

    @Query("DELETE FROM dtn_pending_pull WHERE expires_at IS NOT NULL AND expires_at < :nowSeconds")
    fun deleteExpired(nowSeconds: Long): Int

    @Query("DELETE FROM dtn_pending_pull WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
