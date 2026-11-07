package app.peermesh.data.reducer

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.time.EventClock
import app.peermesh.core.time.EventClockComparator
import app.peermesh.core.types.EventTypes
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.db.dao.LiveTrailDao
import app.peermesh.data.db.entity.LiveTrailEntity
import app.peermesh.data.db.entity.LiveTrailPointEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max

class LiveTrailReducer(
    private val liveTrailDao: LiveTrailDao,
) {
    fun apply(event: EventCore, gid: String) {
        when (event.type) {
            EventTypes.LIVE_TRAIL_START -> applyStart(event, gid)
            EventTypes.LIVE_TRAIL_POINT_BATCH -> applyBatch(event, gid)
            EventTypes.LIVE_TRAIL_END -> applyEnd(event, gid)
        }
    }

    private fun applyStart(event: EventCore, gid: String) {
        val trailId = JsonAccess.stringValue(event.data, "trail_id") ?: event.ref.target ?: return
        val existing = liveTrailDao.findTrail(gid, trailId)
        val clock = eventClock(event)
        if (!EventClockComparator.isNewer(clock, existing?.let { existingClock(it) })) return
        closeStaleTrailsForOwner(event, gid, trailId)

        val shareScope = JsonAccess.stringValue(event.data, "share_scope") ?: "group"
        val shareTo = JsonAccess.elementValue(event.data, "share_to")
        val shareJson = shareTo?.let { CanonicalJson.canonicalize(it) }
        val name = JsonAccess.stringValue(event.data, "name")
        val startT = JsonAccess.longValue(event.data, "start_t") ?: event.time.t
        val trail = LiveTrailEntity(
            trail_id = trailId,
            gid = gid,
            owner_did = existing?.owner_did ?: event.actor.did,
            name = name ?: existing?.name,
            share_scope = shareScope,
            share_to_json = shareJson ?: existing?.share_to_json,
            start_t = existing?.start_t ?: startT,
            end_t = null,
            point_count = existing?.point_count ?: 0,
            dist_m = existing?.dist_m ?: 0f,
            active = 1,
            accepted = 1,
            pending_expires_t = null,
            updated_t = event.time.t,
            last_event_id = event.id,
            last_event_t = event.time.t,
            last_event_lt = event.time.lt ?: 0L,
            last_event_actor = event.actor.did,
        )
        liveTrailDao.upsertTrail(trail)
    }

    private fun closeStaleTrailsForOwner(event: EventCore, gid: String, trailId: String) {
        val cutoff = event.time.t - LIVE_TRAIL_STALE_SEC
        if (cutoff <= 0L) return
        val stale = liveTrailDao.findStaleActiveTrailsByOwner(gid, event.actor.did, cutoff, trailId)
        if (stale.isEmpty()) return
        val now = event.time.t
        ActivityLog.add(
            label = "Live trail stale close",
            source = "trail",
            message = "gid=$gid owner=${event.actor.did.take(6)} new=$trailId count=${stale.size}",
        )
        stale.forEach { trail ->
            val closed = trail.copy(
                active = 0,
                end_t = trail.end_t ?: trail.updated_t,
                updated_t = now,
            )
            liveTrailDao.upsertTrail(closed)
        }
    }

    private fun applyBatch(event: EventCore, gid: String) {
        val trailId = JsonAccess.stringValue(event.data, "trail_id") ?: event.ref.target ?: return
        val pointsElement = JsonAccess.elementValue(event.data, "points") as? JsonArray ?: return
        val existing = liveTrailDao.findTrail(gid, trailId)
        val clock = eventClock(event)
        val isNewerEvent = EventClockComparator.isNewer(clock, existing?.let { existingClock(it) })

        val startIndex = JsonAccess.longValue(event.data, "start_index")?.toInt() ?: existing?.point_count ?: 0
        if (pointsElement.isEmpty()) return
        if (existing == null) return
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
        if (points.isEmpty()) return
        liveTrailDao.insertPoints(points)
        if (!isNewerEvent) {
            val maxPointCount = max(existing.point_count, points.maxOf { it.idx } + 1)
            if (maxPointCount > existing.point_count) {
                liveTrailDao.upsertTrail(existing.copy(point_count = maxPointCount))
            }
            return
        }

        val lastPoint = liveTrailDao.findLastPoint(trailId)
        val lastIdx = lastPoint?.idx ?: -1
        var dist = existing.dist_m
        var prevPoint = lastPoint
        val ordered = points.filter { it.idx > lastIdx }.sortedBy { it.idx }
        for (pt in ordered) {
            if (prevPoint != null) {
                dist += distanceBetween(prevPoint, pt)
            }
            prevPoint = pt
        }
        val endT = points.last().t
        val updated = LiveTrailEntity(
            trail_id = trailId,
            gid = gid,
            owner_did = existing.owner_did,
            name = existing.name,
            share_scope = existing.share_scope,
            share_to_json = existing.share_to_json,
            start_t = existing.start_t,
            end_t = endT,
            point_count = max(existing.point_count, startIndex + points.size),
            dist_m = dist,
            active = existing.active,
            accepted = 1,
            pending_expires_t = null,
            updated_t = event.time.t,
            last_event_id = event.id,
            last_event_t = event.time.t,
            last_event_lt = event.time.lt ?: 0L,
            last_event_actor = event.actor.did,
        )
        liveTrailDao.upsertTrail(updated)
    }

    private fun applyEnd(event: EventCore, gid: String) {
        val trailId = JsonAccess.stringValue(event.data, "trail_id") ?: event.ref.target ?: return
        val existing = liveTrailDao.findTrail(gid, trailId) ?: return
        val clock = eventClock(event)
        if (!EventClockComparator.isNewer(clock, existingClock(existing))) return
        val endT = JsonAccess.longValue(event.data, "end_t") ?: event.time.t
        val updated = existing.copy(
            end_t = endT,
            active = 0,
            accepted = 1,
            pending_expires_t = null,
            updated_t = event.time.t,
            last_event_id = event.id,
            last_event_t = event.time.t,
            last_event_lt = event.time.lt ?: 0L,
            last_event_actor = event.actor.did,
        )
        liveTrailDao.upsertTrail(updated)
    }

    private fun eventClock(event: EventCore): EventClock {
        return EventClock(
            t = event.time.t,
            lt = event.time.lt ?: 0L,
            actorDid = event.actor.did,
        )
    }

    private fun existingClock(existing: LiveTrailEntity): EventClock {
        return EventClock(
            t = existing.last_event_t,
            lt = existing.last_event_lt,
            actorDid = existing.last_event_actor,
        )
    }

    private fun distanceBetween(prev: LiveTrailPointEntity, next: LiveTrailPointEntity): Float {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(prev.lat, prev.lon, next.lat, next.lon, result)
        return result[0]
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

    companion object {
        const val LIVE_TRAIL_STALE_SEC = 120L
    }
}
