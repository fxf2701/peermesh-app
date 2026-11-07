package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "trails",
    primaryKeys = ["trail_id"],
    indices = [
        Index(value = ["start_t"])
    ]
)
data class TrailEntity(
    val trail_id: String,
    val name: String,
    val mode: String,
    val start_t: Long,
    val end_t: Long,
    val point_count: Int,
    val dist_m: Float,
    val unstable: Int = 0,
    val unstable_note: String? = null,
)
