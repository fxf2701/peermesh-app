package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.NearbyRoomMuteStateEntity

@Dao
interface NearbyRoomMuteStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: NearbyRoomMuteStateEntity)

    @Query(
        """
        SELECT * FROM nearby_room_mute_states
        WHERE room_id = :roomId
          AND target_device_id = :targetDeviceId
          AND expires_at_ms > :nowMs
        LIMIT 1
        """
    )
    fun findActive(
        roomId: String,
        targetDeviceId: String,
        nowMs: Long,
    ): NearbyRoomMuteStateEntity?

    @Query(
        """
        SELECT target_device_id FROM nearby_room_mute_states
        WHERE room_id = :roomId
          AND is_muted = 1
          AND expires_at_ms > :nowMs
        """
    )
    fun listMutedPeerIds(roomId: String, nowMs: Long): List<String>

    @Query("DELETE FROM nearby_room_mute_states WHERE room_id = :roomId")
    fun deleteByRoom(roomId: String): Int

    @Query("DELETE FROM nearby_room_mute_states WHERE expires_at_ms <= :nowMs")
    fun deleteExpired(nowMs: Long): Int
}
