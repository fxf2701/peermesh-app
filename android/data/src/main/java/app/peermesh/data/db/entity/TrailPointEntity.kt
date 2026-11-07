package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "trail_points",
    primaryKeys = ["tid", "idx"],
    foreignKeys = [
        ForeignKey(
            entity = TrailEntity::class,
            parentColumns = ["trail_id"],
            childColumns = ["tid"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["tid"])
    ]
)
data class TrailPointEntity(
    val tid: String,
    val idx: Int,
    val lat: Double,
    val lon: Double,
    val t: Long,
    val acc: Float?,
    val spd: Float?,
    val mm: Int,
)
