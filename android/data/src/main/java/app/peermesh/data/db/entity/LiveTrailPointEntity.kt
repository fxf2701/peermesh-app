package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "live_trail_points",
    primaryKeys = ["trail_id", "idx"],
    indices = [
        Index(value = ["trail_id"])
    ]
)
data class LiveTrailPointEntity(
    val trail_id: String,
    val idx: Int,
    val lat: Double,
    val lon: Double,
    val t: Long,
    val acc: Float?,
    val spd: Float?,
    val mm: Int,
)
