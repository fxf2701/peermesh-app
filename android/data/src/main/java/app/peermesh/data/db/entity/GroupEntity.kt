package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val gid: String,
    val kid: String?,
    val name: String?,
    val owner_did: String?,
    val context_profile: String = "urban",
    val created_at: Long,
    val updated_at: Long,
)
