package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.NearbyRoomInviteQuotaEntity

@Dao
interface NearbyRoomInviteQuotaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(quota: NearbyRoomInviteQuotaEntity)

    @Query(
        """
        SELECT * FROM nearby_room_invite_quotas
        WHERE room_id = :roomId
          AND sender_device_id = :senderDeviceId
          AND target_device_id = :targetDeviceId
        """
    )
    fun find(
        roomId: String,
        senderDeviceId: String,
        targetDeviceId: String,
    ): NearbyRoomInviteQuotaEntity?

    @Query("DELETE FROM nearby_room_invite_quotas WHERE room_id = :roomId")
    fun deleteByRoom(roomId: String): Int
}
