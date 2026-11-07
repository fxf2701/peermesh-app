package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.NearbyRoomRelayOfferEntity

@Dao
interface NearbyRoomRelayOfferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(offer: NearbyRoomRelayOfferEntity)

    @Query(
        """
        SELECT * FROM nearby_room_relay_offers
        WHERE room_id = :roomId
          AND message_compact_id = :messageCompactId
          AND target_device_id = :targetDeviceId
        LIMIT 1
        """
    )
    fun find(roomId: String, messageCompactId: String, targetDeviceId: String): NearbyRoomRelayOfferEntity?

    @Query(
        """
        SELECT * FROM nearby_room_relay_offers
        WHERE state = :state
          AND expires_at_ms > :nowMs
        ORDER BY offered_at_ms ASC
        LIMIT :limit
        """
    )
    fun listByState(
        state: String,
        nowMs: Long,
        limit: Int,
    ): List<NearbyRoomRelayOfferEntity>

    @Query(
        """
        UPDATE nearby_room_relay_offers
        SET state = :state,
            offered_at_ms = :updatedAtMs
        WHERE room_id = :roomId
          AND message_compact_id = :messageCompactId
          AND target_device_id = :targetDeviceId
        """
    )
    fun updateState(
        roomId: String,
        messageCompactId: String,
        targetDeviceId: String,
        state: String,
        updatedAtMs: Long,
    ): Int

    @Query("DELETE FROM nearby_room_relay_offers WHERE expires_at_ms <= :nowMs")
    fun deleteExpired(nowMs: Long): Int
}
