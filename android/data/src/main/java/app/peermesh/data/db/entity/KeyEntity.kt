package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "keys")
data class KeyEntity(
    @PrimaryKey val gid: String,
    val kid: String,
    val created_at: Long,
    val updated_at: Long,
)
