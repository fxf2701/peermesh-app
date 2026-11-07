package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.NearbyRoomMessageEntity

@Dao
interface NearbyRoomMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMessage(message: NearbyRoomMessageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertPreparedMessage(message: NearbyRoomMessageEntity)

    @Query(
        """
        SELECT * FROM nearby_room_messages
        WHERE room_id = :roomId AND message_id = :messageId
        LIMIT 1
        """
    )
    fun findMessage(roomId: String, messageId: String): NearbyRoomMessageEntity?

    @Query(
        """
        UPDATE nearby_room_messages
        SET is_hidden_locally = :hidden
        WHERE room_id = :roomId AND message_id = :messageId
        """
    )
    fun setHiddenLocally(
        roomId: String,
        messageId: String,
        hidden: Boolean,
    ): Int

    @Query(
        """
        SELECT * FROM nearby_room_messages
        WHERE room_id = :roomId
          AND (
            owner_device_id = :logicalSenderId OR
            (owner_device_id IS NULL AND sender_device_id = :logicalSenderId)
          )
        """
    )
    fun listLogicalSenderCandidates(
        roomId: String,
        logicalSenderId: String,
    ): List<NearbyRoomMessageEntity>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM nearby_room_messages
            WHERE room_id = :roomId
            ORDER BY created_at_ms DESC, message_id DESC
            LIMIT :limit
        )
        ORDER BY created_at_ms ASC, message_id ASC
        """
    )
    fun listMessages(roomId: String, limit: Int): List<NearbyRoomMessageEntity>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM nearby_room_messages
            WHERE room_id = :roomId
              AND (
                created_at_ms < :beforeCreatedAtMs OR
                (created_at_ms = :beforeCreatedAtMs AND message_id < :beforeMessageId)
              )
            ORDER BY created_at_ms DESC, message_id DESC
            LIMIT :limit
        )
        ORDER BY created_at_ms ASC, message_id ASC
        """
    )
    fun listMessagesBefore(
        roomId: String,
        beforeCreatedAtMs: Long,
        beforeMessageId: String,
        limit: Int,
    ): List<NearbyRoomMessageEntity>

    @Query("DELETE FROM nearby_room_messages WHERE room_id = :roomId")
    fun deleteByRoom(roomId: String): Int

    @Query("DELETE FROM nearby_room_messages WHERE ttl_expires_at_ms <= :nowMs")
    fun deleteExpired(nowMs: Long): Int
}
