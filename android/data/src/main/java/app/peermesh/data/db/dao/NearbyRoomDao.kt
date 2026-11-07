package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.NearbyRoomEntity

@Dao
interface NearbyRoomDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(room: NearbyRoomEntity)

    @Query("SELECT * FROM nearby_rooms WHERE room_id = :roomId")
    fun findById(roomId: String): NearbyRoomEntity?

    @Query(
        """
        SELECT * FROM nearby_rooms
        WHERE state = :activeState AND expires_at_ms > :nowMs
        ORDER BY last_seen_at_ms DESC, created_at_ms DESC
        """
    )
    fun listActive(nowMs: Long, activeState: String = NearbyRoomEntity.STATE_ACTIVE): List<NearbyRoomEntity>

    @Query(
        """
        SELECT * FROM nearby_rooms
        WHERE state = :activeState
          AND expires_at_ms > :nowMs
        ORDER BY is_local_owner DESC, last_seen_at_ms DESC, created_at_ms DESC
        """
    )
    fun listVisible(
        nowMs: Long,
        activeState: String = NearbyRoomEntity.STATE_ACTIVE,
    ): List<NearbyRoomEntity>

    @Query(
        """
        SELECT * FROM nearby_rooms
        WHERE is_local_owner = 1
          AND state = :activeState
          AND expires_at_ms > :nowMs
        ORDER BY created_at_ms DESC
        LIMIT 1
        """
    )
    fun findActiveLocalOwnedRoom(
        nowMs: Long,
        activeState: String = NearbyRoomEntity.STATE_ACTIVE,
    ): NearbyRoomEntity?

    @Query(
        """
        UPDATE nearby_rooms
        SET state = :expiredState
        WHERE state = :activeState
          AND expires_at_ms <= :nowMs
        """
    )
    fun markExpired(
        nowMs: Long,
        activeState: String = NearbyRoomEntity.STATE_ACTIVE,
        expiredState: String = NearbyRoomEntity.STATE_EXPIRED,
    ): Int

    @Query(
        """
        UPDATE nearby_rooms
        SET state = :closedState,
            closed_at_ms = :closedAtMs
        WHERE room_id = :roomId
        """
    )
    fun markClosed(
        roomId: String,
        closedAtMs: Long,
        closedState: String = NearbyRoomEntity.STATE_CLOSED,
    ): Int

    @Query("DELETE FROM nearby_rooms WHERE expires_at_ms < :retentionCutoffMs")
    fun deleteExpired(retentionCutoffMs: Long): Int

    @Query(
        """
        SELECT room_id FROM nearby_rooms
        WHERE (
            state = :expiredState
            AND expires_at_ms <= :retentionCutoffMs
        ) OR (
            state = :closedState
            AND COALESCE(closed_at_ms, last_seen_at_ms) <= :retentionCutoffMs
        )
        ORDER BY last_seen_at_ms ASC, room_id ASC
        """
    )
    fun listTerminalRoomIdsForCleanup(
        retentionCutoffMs: Long,
        expiredState: String = NearbyRoomEntity.STATE_EXPIRED,
        closedState: String = NearbyRoomEntity.STATE_CLOSED,
    ): List<String>

    @Query("DELETE FROM nearby_rooms WHERE room_id = :roomId")
    fun deleteByRoom(roomId: String): Int
}
