package app.peermesh

import android.content.Context
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.TrailEntity
import app.peermesh.data.db.entity.TrailPointEntity

object TrailStore {
    fun load(context: Context): List<TrailRecord> {
        val db = AppDatabaseProvider.get(context)
        val trails = db.trailDao().listTrails(200)
        return trails.map { trail ->
            var dist = trail.dist_m
            if (dist <= 0f) {
                val points = db.trailDao().listPoints(trail.trail_id)
                if (points.size > 1) {
                    dist = computeDistance(points)
                    db.trailDao().updateTrail(
                        trailId = trail.trail_id,
                        name = trail.name,
                        mode = trail.mode,
                        startT = trail.start_t,
                        endT = trail.end_t,
                        pointCount = trail.point_count,
                        distM = dist,
                        unstable = trail.unstable,
                        unstableNote = trail.unstable_note,
                    )
                }
            }
            TrailRecord(
                id = trail.trail_id,
                name = trail.name,
                mode = trail.mode,
                start_t = trail.start_t,
                end_t = trail.end_t,
                points = emptyList(),
                point_count = trail.point_count,
                dist_m = dist,
                unstable = trail.unstable,
                unstable_note = trail.unstable_note,
            )
        }
    }

    fun append(context: Context, record: TrailRecord) {
        val db = AppDatabaseProvider.get(context)
        val trail = TrailEntity(
            trail_id = record.id,
            name = record.name,
            mode = record.mode,
            start_t = record.start_t,
            end_t = record.end_t,
            point_count = record.point_count,
            dist_m = record.dist_m,
            unstable = record.unstable,
            unstable_note = record.unstable_note,
        )
        val points = record.points.mapIndexed { index, point ->
            TrailPointEntity(
                tid = record.id,
                idx = index,
                lat = point.lat,
                lon = point.lon,
                t = point.t,
                acc = point.acc,
                spd = point.spd,
                mm = point.mm,
            )
        }
        db.runInTransaction {
            db.trailDao().insertTrail(trail)
            db.trailDao().updateTrail(
                trailId = trail.trail_id,
                name = trail.name,
                mode = trail.mode,
                startT = trail.start_t,
                endT = trail.end_t,
                pointCount = trail.point_count,
                distM = trail.dist_m,
                unstable = trail.unstable,
                unstableNote = trail.unstable_note,
            )
            if (points.isNotEmpty()) {
                db.trailDao().insertPoints(points)
            }
        }
    }

    fun appendPartial(
        context: Context,
        trailId: String,
        name: String,
        mode: String,
        startT: Long,
        endT: Long,
        points: List<TrailPoint>,
        startIndex: Int,
        totalCount: Int,
        distanceMeters: Float,
        unstable: Int,
        unstableNote: String?,
    ) {
        val db = AppDatabaseProvider.get(context)
        val trail = TrailEntity(
            trail_id = trailId,
            name = name,
            mode = mode,
            start_t = startT,
            end_t = endT,
            point_count = totalCount,
            dist_m = distanceMeters,
            unstable = unstable,
            unstable_note = unstableNote,
        )
        val entities = points.mapIndexed { index, point ->
            TrailPointEntity(
                tid = trailId,
                idx = startIndex + index,
                lat = point.lat,
                lon = point.lon,
                t = point.t,
                acc = point.acc,
                spd = point.spd,
                mm = point.mm,
            )
        }
        db.runInTransaction {
            db.trailDao().insertTrail(trail)
            db.trailDao().updateTrail(
                trailId = trailId,
                name = name,
                mode = mode,
                startT = startT,
                endT = endT,
                pointCount = totalCount,
                distM = distanceMeters,
                unstable = unstable,
                unstableNote = unstableNote,
            )
            if (entities.isNotEmpty()) {
                db.trailDao().insertPoints(entities)
            }
        }
    }

    private fun computeDistance(points: List<TrailPointEntity>): Float {
        var total = 0f
        var lastLat = points.first().lat
        var lastLon = points.first().lon
        for (i in 1 until points.size) {
            val p = points[i]
            val result = FloatArray(1)
            android.location.Location.distanceBetween(lastLat, lastLon, p.lat, p.lon, result)
            total += result[0]
            lastLat = p.lat
            lastLon = p.lon
        }
        return total
    }

    fun delete(context: Context, id: String) {
        val db = AppDatabaseProvider.get(context)
        db.trailDao().deleteTrail(id)
    }
}
