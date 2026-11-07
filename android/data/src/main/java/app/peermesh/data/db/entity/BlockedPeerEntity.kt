package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocked_peers",
    indices = [
        Index(value = ["blockedPeerId", "groupId", "scope"], unique = true),
        Index(value = ["groupId", "scope"]),
    ]
)
data class BlockedPeerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val blockedPeerId: String,
    val scope: String,
    val groupId: String?,
    val hideMessages: Boolean,
    val hideLocation: Boolean,
    val hideTrail: Boolean,
    val reasonCode: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val expiresAtMs: Long?,
)
