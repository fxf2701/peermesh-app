package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.PendingCanonicalMapEventEntity

@Dao
interface PendingCanonicalMapEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(entity: PendingCanonicalMapEventEntity): Long

    @Query(
        """
        SELECT * FROM pending_canonical_map_events
        WHERE gid = :gid AND event_id = :eventId
        LIMIT 1
        """
    )
    fun find(gid: String, eventId: String): PendingCanonicalMapEventEntity?

    @Query(
        """
        SELECT * FROM pending_canonical_map_events
        WHERE gid = :gid
          AND item_type = :itemType
          AND item_id = :itemId
          AND materialization_state = 'pending_missing_canonical'
        ORDER BY event_t ASC, event_lt ASC, actor_did ASC, event_id ASC
        """
    )
    fun findPendingTarget(
        gid: String,
        itemType: String,
        itemId: String,
    ): List<PendingCanonicalMapEventEntity>

    @Query(
        """
        SELECT COUNT(*) FROM pending_canonical_map_events
        WHERE gid = :gid
          AND item_type = :itemType
          AND item_id = :itemId
          AND materialization_state = 'pending_missing_canonical'
        """
    )
    fun countPendingTarget(gid: String, itemType: String, itemId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM pending_canonical_map_events
        WHERE gid = :gid
          AND materialization_state = 'pending_missing_canonical'
        """
    )
    fun countPendingGroup(gid: String): Int

    @Query(
        """
        SELECT DISTINCT root_event_id FROM pending_canonical_map_events
        WHERE gid = :gid
          AND materialization_state = 'pending_missing_canonical'
          AND root_event_id IS NOT NULL
          AND root_event_id != ''
        ORDER BY root_event_id ASC
        LIMIT :limit
        """
    )
    fun listPendingRootEventIds(gid: String, limit: Int): List<String>

    @Query(
        """
        UPDATE pending_canonical_map_events
        SET materialization_state = :nextState,
            rejection_reason = :rejectionReason,
            updated_at = :updatedAt,
            replay_attempt_count = replay_attempt_count + 1
        WHERE gid = :gid
          AND event_id = :eventId
          AND materialization_state = 'pending_missing_canonical'
        """
    )
    fun transitionPending(
        gid: String,
        eventId: String,
        nextState: String,
        rejectionReason: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        DELETE FROM pending_canonical_map_events
        WHERE materialization_state = 'pending_missing_canonical'
          AND expires_at <= :nowSeconds
        """
    )
    fun deleteExpiredPending(nowSeconds: Long): Int

    @Query(
        """
        DELETE FROM pending_canonical_map_events
        WHERE materialization_state != 'pending_missing_canonical'
          AND updated_at < :cutoffSeconds
        """
    )
    fun deleteTerminalBefore(cutoffSeconds: Long): Int
}
