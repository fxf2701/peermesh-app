package app.peermesh

import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapMarkerEntity


data class MarkerConsensus(
    val clusterId: String,
    val kind: String?,
    val lat: Double,
    val lon: Double,
    val members: List<MapMarkerEntity>,
    val primary: MapMarkerEntity,
    val reportedCount: Int,
    val observedCount: Int,
    val ttlMedianSec: Long?,
    val ttlMinSec: Long?,
    val ttlMaxSec: Long?,
)


data class AreaConsensus(
    val clusterId: String,
    val kind: String?,
    val centroidLat: Double,
    val centroidLon: Double,
    val members: List<MapAreaEntity>,
    val primary: MapAreaEntity,
    val reportedCount: Int,
    val observedCount: Int,
    val ttlMedianSec: Long?,
    val ttlMinSec: Long?,
    val ttlMaxSec: Long?,
)
