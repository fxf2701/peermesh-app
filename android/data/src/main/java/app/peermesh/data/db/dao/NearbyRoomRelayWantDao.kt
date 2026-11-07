package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.NearbyRoomRelayWantEntity

@Dao
interface NearbyRoomRelayWantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(want: NearbyRoomRelayWantEntity)

    @Query(
        """
        SELECT * FROM nearby_room_relay_wants
        WHERE room_id = :roomId
          AND message_compact_id = :messageCompactId
        LIMIT 1
        """
    )
    fun find(roomId: String, messageCompactId: String): NearbyRoomRelayWantEntity?

    @Query(
        """
        SELECT * FROM nearby_room_relay_wants
        WHERE state IN (:states)
          AND expires_at_ms > :nowMs
        ORDER BY updated_at_ms ASC
        LIMIT :limit
        """
    )
    fun listActiveByStates(
        states: List<String>,
        nowMs: Long,
        limit: Int,
    ): List<NearbyRoomRelayWantEntity>

    @Query("DELETE FROM nearby_room_relay_wants WHERE expires_at_ms <= :nowMs")
    fun deleteExpired(nowMs: Long): Int
}
