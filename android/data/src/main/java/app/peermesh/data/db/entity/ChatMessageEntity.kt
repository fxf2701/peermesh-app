package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "chat_messages",
    primaryKeys = ["gid", "message_id"],
    indices = [
        Index(value = ["gid", "created_t"]),
        Index(value = ["gid", "status"])
    ]
)
data class ChatMessageEntity(
    val gid: String,
    val message_id: String,
    val text: String,
    val status: String,
    val delivery_status: String,
    val created_t: Long,
    val updated_t: Long,
    val edited_at: Long?,
    val actor_did: String,
    val reply_to: String?,
    val expires_at: Long?,
    val last_event_id: String,
    val last_event_t: Long,
    val last_event_lt: Long,
    val last_event_actor: String,
    val received_ack_count: Int,
    val received_group_ack_count: Int,
    val received_ack_by: String?,
    val received_group_ack_by: String?,
    val seen_by: String?,
)
