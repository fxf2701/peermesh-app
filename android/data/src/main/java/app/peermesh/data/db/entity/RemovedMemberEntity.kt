package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "removed_members",
    indices = [
        Index(
            value = ["groupId", "removedAtSeconds"],
            name = "index_removed_members_groupId_removedAtSeconds",
        ),
    ],
    primaryKeys = ["groupId", "peerId"],
)
data class RemovedMemberEntity(
    val groupId: String,
    val peerId: String,
    val removedByPeerId: String,
    val removalEventId: String,
    val removedAtSeconds: Long,
    val lastEventT: Long,
    val lastEventLt: Long,
    val lastEventActor: String,
)
