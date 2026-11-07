package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "map_markers",
    primaryKeys = ["gid", "marker_id"],
    indices = [
        Index(value = ["gid", "layer_id", "status"])
    ]
)
data class MapMarkerEntity(
    val gid: String,
    val marker_id: String,
    val layer_id: String,
    val lat: Double,
    val lon: Double,
    val radius_m: Double?,
    val label: String?,
    val note: String?,
    val kind: String?,
    // Confidence: 2=observed, 1=reported (default), 0=uncertain
    val confidence: Int = 1,
    val status: String,
    val created_t: Long,
    val updated_t: Long,
    val actor_did: String,
    val removal_requested_t: Long? = null,
    val removal_requested_actor: String? = null,
    val removal_reason: String? = null,
    val removal_cancel_t: Long? = null,
    val expires_at: Long?,
    val last_event_id: String,
    val last_event_t: Long,
    val last_event_lt: Long,
    val last_event_actor: String,
)
