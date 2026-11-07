package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.EventEntity

@Dao
interface EventsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: EventEntity): Long

    @Query(
        """
        SELECT * FROM events
        WHERE (inserted_at > :since)
           OR (inserted_at = :since AND event_id > :sinceEventId)
        ORDER BY inserted_at ASC, event_id ASC
        LIMIT :limit
        """
    )
    fun loadSince(since: Long, sinceEventId: String, limit: Int): List<EventEntity>

    @Query(
        """
        SELECT * FROM events
        WHERE source != :source
          AND invalid_at IS NULL
          AND ((inserted_at > :since)
            OR (inserted_at = :since AND event_id > :sinceEventId))
        ORDER BY inserted_at ASC, event_id ASC
        LIMIT :limit
        """
    )
    fun loadSinceExcludingSource(
        since: Long,
        sinceEventId: String,
        limit: Int,
        source: String,
    ): List<EventEntity>

    @Query(
        """
        SELECT * FROM events
        WHERE source NOT IN (:sources)
          AND invalid_at IS NULL
          AND id > :afterRowId
        ORDER BY id ASC
        LIMIT :limit
        """
    )
    fun loadAfterRowIdExcludingSources(
        afterRowId: Long,
        limit: Int,
        sources: List<String>,
    ): List<EventEntity>

    @Query(
        """
        SELECT event_id FROM events
        WHERE gid = :gid
          AND invalid_at IS NULL
          AND ((inserted_at > :since)
            OR (inserted_at = :since AND event_id > :sinceEventId))
        ORDER BY inserted_at ASC, event_id ASC
        LIMIT :limit
        """
    )
    fun listRecentEventIdsForDtn(
        gid: String,
        since: Long,
        sinceEventId: String,
        limit: Int,
    ): List<String>

    @Query(
        """
        SELECT * FROM events
        WHERE gid = :gid
          AND event_id IN (:eventIds)
          AND invalid_at IS NULL
        ORDER BY inserted_at ASC, event_id ASC
        """
    )
    fun loadByEventIdsForDtn(gid: String, eventIds: List<String>): List<EventEntity>

    @Query(
        """
        SELECT * FROM events
        WHERE gid = :gid
          AND invalid_at IS NULL
          AND ((inserted_at > :lastInsertedAt)
            OR (inserted_at = :lastInsertedAt AND event_id > :lastEventId))
        ORDER BY inserted_at ASC, event_id ASC
        LIMIT :limit
        """
    )
    fun loadDtnCursorWindow(
        gid: String,
        lastInsertedAt: Long,
        lastEventId: String,
        limit: Int,
    ): List<EventEntity>

    @Query(
        """
        UPDATE events
        SET invalid_at = :invalidAt, invalid_reason = :reason
        WHERE gid = :gid AND event_id = :eventId
        """
    )
    fun markInvalid(gid: String, eventId: String, invalidAt: Long, reason: String)

    @Query(
        """
        DELETE FROM events
        WHERE invalid_at IS NOT NULL AND invalid_at < :cutoff
        """
    )
    fun deleteInvalidBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM events WHERE gid = :gid AND event_id = :eventId")
    fun exists(gid: String, eventId: String): Int

    @Query("SELECT * FROM events WHERE gid = :gid AND event_id = :eventId LIMIT 1")
    fun findById(gid: String, eventId: String): EventEntity?

    @Query(
        """
        SELECT actor_did FROM events
        WHERE gid = :gid
          AND invalid_at IS NULL
          AND created_t >= :sinceCreatedT
        GROUP BY actor_did
        ORDER BY MAX(created_t) DESC, actor_did ASC
        """
    )
    fun listActiveActorIdsSince(gid: String, sinceCreatedT: Long): List<String>

    @Query("DELETE FROM events WHERE gid = :gid AND event_id = :eventId")
    fun deleteByEventId(gid: String, eventId: String): Int

    @Query("SELECT * FROM events WHERE gid = :gid AND type = :type AND ref_target = :targetId ORDER BY created_t ASC, id ASC")
    fun listByTypeAndTarget(gid: String, type: String, targetId: String): List<EventEntity>

    @Query(
        """
        SELECT DISTINCT e.ref_root FROM events e
        INNER JOIN map_item_actions a
          ON a.gid = e.gid AND a.event_id = e.event_id
        WHERE e.gid = :gid
          AND a.materialization_state = 'pending_target'
          AND e.ref_root IS NOT NULL
          AND e.ref_root != ''
        ORDER BY e.ref_root ASC
        LIMIT :limit
        """
    )
    fun listPendingMapActionRootEventIds(gid: String, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM events WHERE gid = :gid AND type = :type AND invalid_at IS NULL")
    fun countByType(gid: String, type: String): Int

    @Query(
        """
        DELETE FROM events
        WHERE gid = :gid AND ref_target = :targetId AND type IN (:types)
        """
    )
    fun deleteByTypesAndTarget(gid: String, targetId: String, types: List<String>): Int

    @Query("DELETE FROM events WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
