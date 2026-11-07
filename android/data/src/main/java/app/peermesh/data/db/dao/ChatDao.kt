package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.ChatMessageEntity

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE gid = :gid AND message_id = :messageId")
    fun findMessage(gid: String, messageId: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE gid = :gid AND last_event_id = :eventId LIMIT 1")
    fun findMessageByEventId(gid: String, eventId: String): ChatMessageEntity?

    @Query(
        "UPDATE chat_messages " +
            "SET delivery_status = :status " +
            "WHERE gid = :gid AND message_id IN (:messageIds) " +
            "AND delivery_status != :seenStatus"
    )
    fun updateDeliveryStatusIfNotSeen(
        gid: String,
        messageIds: List<String>,
        status: String,
        seenStatus: String,
    )

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM chat_messages
            WHERE gid = :gid
            ORDER BY created_t DESC, message_id DESC
            LIMIT :limit
        )
        ORDER BY created_t ASC, message_id ASC
        """
    )
    fun listMessages(gid: String, limit: Int): List<ChatMessageEntity>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM chat_messages
            WHERE gid = :gid
              AND (
                created_t < :beforeCreatedT OR
                (created_t = :beforeCreatedT AND message_id < :beforeMessageId)
              )
            ORDER BY created_t DESC, message_id DESC
            LIMIT :limit
        )
        ORDER BY created_t ASC, message_id ASC
        """
    )
    fun listMessagesBefore(
        gid: String,
        beforeCreatedT: Long,
        beforeMessageId: String,
        limit: Int,
    ): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
