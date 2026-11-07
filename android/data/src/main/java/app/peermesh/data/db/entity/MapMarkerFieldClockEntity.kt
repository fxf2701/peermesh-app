package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "map_marker_field_clock",
    primaryKeys = ["gid", "marker_id", "field"],
    indices = [
        Index(value = ["gid", "marker_id"])
    ]
)
data class MapMarkerFieldClockEntity(
    val gid: String,
    val marker_id: String,
    val field: String,
    val last_event_t: Long,
    val last_event_lt: Long,
    val last_event_actor: String,
)
