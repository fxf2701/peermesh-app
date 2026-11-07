package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "moderation_actions",
    indices = [
        Index(value = ["groupId", "createdAtMs"]),
        Index(value = ["targetPeerId", "actionType"]),
    ]
)
data class ModerationActionEntity(
    @PrimaryKey val actionId: String,
    val groupId: String?,
    val actorPeerId: String,
    val targetPeerId: String,
    val actionType: String,
    val createdAtMs: Long,
    val metadataJson: String?,
)
