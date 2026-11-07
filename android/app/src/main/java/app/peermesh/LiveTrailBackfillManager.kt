package app.peermesh

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.LiveTrailPointEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object LiveTrailBackfillManager {
    fun backfillIfEnded(context: android.content.Context, gid: String, trailId: String) {
        Thread {
            val db = AppDatabaseProvider.get(context)
            val trail = db.liveTrailDao().findTrail(gid, trailId) ?: return@Thread
            if (trail.active == 1) return@Thread
            app.peermesh.data.debug.ActivityLog.add(
                label = "Live trail backfill",
                source = "trail",
                message = "trigger=ended trail=$trailId",
                gid = gid,
            )
            backfillNow(context, gid, trailId)
        }.start()
    }

    fun backfillNow(context: android.content.Context, gid: String, trailId: String) {
        val db = AppDatabaseProvider.get(context)
        val events = db.eventsDao().listByTypeAndTarget(
            gid,
            EventTypes.LIVE_TRAIL_POINT_BATCH,
            trailId,
        )
        if (events.isEmpty()) return
        val startMs = System.currentTimeMillis()
        app.peermesh.data.debug.ActivityLog.add(
            label = "Live trail backfill",
            source = "trail",
            message = "start trail=$trailId events=${events.size}",
            gid = gid,
        )

        events.forEach { entity ->
            val core = parseCore(entity.payload_json) ?: return@forEach
            val points = parsePoints(core, trailId) ?: return@forEach
            if (points.isNotEmpty()) {
                db.liveTrailDao().insertPoints(points)
            }
        }

        val points = db.liveTrailDao().listPoints(trailId)
        if (points.isEmpty()) return
        val dist = computeDistance(points)
        db.liveTrailDao().updateTrailStats(gid, trailId, points.size, dist)
        val elapsedMs = System.currentTimeMillis() - startMs
        app.peermesh.data.debug.ActivityLog.add(
            label = "Live trail backfill",
            source = "trail",
            message = "done trail=$trailId points=${points.size} dist=${"%.1f".format(dist)}m elapsedMs=$elapsedMs",
            gid = gid,
        )
    }

    private fun parseCore(json: String): EventCore? {
        return runCatching {
            CanonicalJson.json.decodeFromString(EventCore.serializer(), json)
        }.getOrNull()
    }

    private fun parsePoints(core: EventCore, trailId: String): List<LiveTrailPointEntity>? {
        val data = core.data as? JsonObject ?: return null
        val pointsElement = data["points"] as? JsonArray ?: return null
        val startIndex = (data["start_index"] as? JsonPrimitive)?.intOrNull() ?: 0
        if (pointsElement.isEmpty()) return emptyList()

        val points = ArrayList<LiveTrailPointEntity>(pointsElement.size)
        pointsElement.forEachIndexed { idx, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            val lat = (obj["lat"] as? JsonPrimitive)?.doubleOrNull() ?: return@forEachIndexed
            val lon = (obj["lon"] as? JsonPrimitive)?.doubleOrNull() ?: return@forEachIndexed
            val t = (obj["t"] as? JsonPrimitive)?.longOrNull() ?: return@forEachIndexed
            val acc = (obj["acc"] as? JsonPrimitive)?.floatOrNull()
            val spd = (obj["spd"] as? JsonPrimitive)?.floatOrNull()
            val mm = (obj["mm"] as? JsonPrimitive)?.intOrNull() ?: 0
            points.add(
                LiveTrailPointEntity(
                    trail_id = trailId,
                    idx = startIndex + idx,
                    lat = lat,
                    lon = lon,
                    t = t,
                    acc = acc,
                    spd = spd,
                    mm = mm,
                )
            )
        }
        return points
    }

    private fun computeDistance(points: List<LiveTrailPointEntity>): Float {
        if (points.size < 2) return 0f
        val ordered = points.sortedBy { it.idx }
        var total = 0f
        var prev = ordered.first()
        for (i in 1 until ordered.size) {
            val next = ordered[i]
            val result = FloatArray(1)
            android.location.Location.distanceBetween(prev.lat, prev.lon, next.lat, next.lon, result)
            total += result[0]
            prev = next
        }
        return total
    }

    private fun JsonPrimitive.doubleOrNull(): Double? {
        return if (isString) content.toDoubleOrNull() else toString().toDoubleOrNull()
    }

    private fun JsonPrimitive.floatOrNull(): Float? {
        return if (isString) content.toFloatOrNull() else toString().toFloatOrNull()
    }

    private fun JsonPrimitive.longOrNull(): Long? {
        return if (isString) content.toLongOrNull() else toString().toLongOrNull()
    }

    private fun JsonPrimitive.intOrNull(): Int? {
        return if (isString) content.toIntOrNull() else toString().toIntOrNull()
    }
}
