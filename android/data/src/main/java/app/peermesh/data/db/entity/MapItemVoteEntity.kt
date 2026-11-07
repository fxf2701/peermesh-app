package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "map_item_votes",
    primaryKeys = ["gid", "item_id", "actor_did"],
    indices = [
        Index(value = ["gid", "item_id"]),
        Index(value = ["gid", "actor_did"])
    ]
)
data class MapItemVoteEntity(
    val gid: String,
    val item_id: String,
    val item_type: String, // marker | area
    val actor_did: String,
    val note: String?,
    val confidence: Int,
    val created_t: Long,
    val updated_t: Long,
)
