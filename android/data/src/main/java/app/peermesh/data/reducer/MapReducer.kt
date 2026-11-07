package app.peermesh.data.reducer

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.time.EventClock
import app.peermesh.core.time.EventClockComparator
import app.peermesh.core.ttl.TtlHelper
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.dao.MapDao
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapLayerEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import app.peermesh.data.db.entity.MapItemVoteEntity
import kotlinx.serialization.json.JsonPrimitive

class MapReducer(
    private val mapDao: MapDao,
    private val voteDao: app.peermesh.data.db.dao.MapItemVoteDao,
    private val includeEventIdTieBreak: Boolean = false,
    private val preserveTemporalOnUpdate: Boolean = false,
    private val legacyNoExpiryOnAdd: Boolean = false,
    private val acceptExplicitExpiryOnAdd: Boolean = false,
) {
    companion object {
        // Confidence: 2=observed, 1=reported (default), 0=uncertain
        const val CONF_UNCERTAIN = 0
        const val CONF_REPORTED = 1
        const val CONF_OBSERVED = 2
    }

    fun apply(event: EventCore, gid: String) {
        when (event.type) {
            EventTypes.MAP_MARKER_ADD, EventTypes.MAP_MARKER_UPD -> applyMarkerUpsert(event, gid)
            EventTypes.MAP_MARKER_DEL -> applyMarkerDelete(event, gid)
            EventTypes.MAP_AREA_ADD, EventTypes.MAP_AREA_UPD -> applyAreaUpsert(event, gid)
            EventTypes.MAP_AREA_DEL -> applyAreaDelete(event, gid)
        }
    }

    private fun applyMarkerUpsert(event: EventCore, gid: String) {
        val markerId = event.ref.target ?: JsonAccess.stringValue(event.data, "marker_id") ?: event.id
        val existing = mapDao.findMarker(gid, markerId)

        val lat = JsonAccess.doubleValue(event.data, "lat") ?: existing?.lat
        val lon = JsonAccess.doubleValue(event.data, "lon") ?: existing?.lon
        if (lat == null || lon == null) return

        val clock = eventClock(event)
        if (!isNewer(event, clock, existing?.let { existingClock(it) }, existing?.last_event_id)) return

        val layerId = JsonAccess.stringValue(event.data, "layer_id") ?: ReducerConstants.DEFAULT_LAYER_ID
        upsertLayer(gid, layerId, event.time.t)

        val metaTtlSeconds = if (preserveTemporalOnUpdate && existing != null) null else event.meta?.ttl_s
        val explicitExpiresAt = if (acceptExplicitExpiryOnAdd && existing == null && event.type.endsWith(".ADD")) {
            JsonAccess.longValue(event.data, "expires_at")
        } else null
        val expiresAt = if (preserveTemporalOnUpdate && existing != null) {
            existing.expires_at
        } else if (explicitExpiresAt != null) {
            explicitExpiresAt
        } else if (metaTtlSeconds != null) {
            val ttlSeconds = TtlHelper.resolveTtlSeconds(event.type, metaTtlSeconds(event))
            TtlHelper.expiresAt(event.time.t, ttlSeconds)
        } else {
            existing?.expires_at ?: run {
            if (legacyNoExpiryOnAdd && event.meta?.ttl_s == null) null else {
                val ttlSeconds = TtlHelper.resolveTtlSeconds(event.type, null)
                TtlHelper.expiresAt(event.time.t, ttlSeconds)
            }
            }
        }

        val eventNote = JsonAccess.stringValue(event.data, "note")
        val normalizedNote = eventNote?.trim()?.ifBlank { null }
        val eventConfidence = (
            JsonAccess.stringValue(event.data, "confidence")
                ?: (JsonAccess.elementValue(event.data, "confidence") as? JsonPrimitive)?.content
        )?.toIntOrNull()
        val removalRequestedT = JsonAccess.longValue(event.data, "removal_requested_t")
        val removalRequestedActor = JsonAccess.stringValue(event.data, "removal_requested_actor")
        val removalReason = JsonAccess.stringValue(event.data, "removal_reason")
        val removalCancelT = JsonAccess.longValue(event.data, "removal_cancel_t")
        val isNonOwner = existing != null && existing.actor_did != event.actor.did
        if (isNonOwner && (eventNote != null || eventConfidence != null)) {
            val existingVote = voteDao.find(gid, markerId, event.actor.did)
            val created = existingVote?.created_t ?: event.time.t
            val confidence = eventConfidence ?: existingVote?.confidence ?: CONF_REPORTED
            voteDao.upsert(
                MapItemVoteEntity(
                    gid = gid,
                    item_id = markerId,
                    item_type = "marker",
                    actor_did = event.actor.did,
                    note = if (eventNote != null) normalizedNote else existingVote?.note,
                    confidence = confidence,
                    created_t = created,
                    updated_t = event.time.t,
                )
            )
        }

        val marker = MapMarkerEntity(
            marker_id = markerId,
            gid = gid,
            layer_id = layerId,
            lat = lat,
            lon = lon,
            radius_m = JsonAccess.doubleValue(event.data, "radius_m") ?: existing?.radius_m,
            label = JsonAccess.stringValue(event.data, "label") ?: existing?.label,
            note = if (isNonOwner) {
                existing?.note
            } else if (eventNote != null) {
                normalizedNote
            } else {
                existing?.note
            },
            kind = JsonAccess.stringValue(event.data, "kind") ?: existing?.kind,
            confidence = if (isNonOwner) {
                existing?.confidence ?: CONF_REPORTED
            } else {
                eventConfidence ?: existing?.confidence ?: CONF_REPORTED
            },
            status = ReducerConstants.STATUS_ACTIVE,
            created_t = existing?.created_t ?: event.time.t,
            updated_t = event.time.t,
            actor_did = existing?.actor_did ?: event.actor.did,
            removal_requested_t = removalRequestedT ?: existing?.removal_requested_t,
            removal_requested_actor = removalRequestedActor ?: existing?.removal_requested_actor,
            removal_reason = removalReason ?: existing?.removal_reason,
            removal_cancel_t = removalCancelT ?: existing?.removal_cancel_t,
            expires_at = expiresAt,
            last_event_id = event.id,
            last_event_t = event.time.t,
            last_event_lt = event.time.lt ?: 0L,
            last_event_actor = event.actor.did,
        )
        mapDao.upsertMarker(marker)
    }

    private fun applyMarkerDelete(event: EventCore, gid: String) {
        val markerId = event.ref.target ?: JsonAccess.stringValue(event.data, "marker_id") ?: return
        val existing = mapDao.findMarker(gid, markerId) ?: return
        val clock = eventClock(event)
        if (!isNewer(event, clock, existingClock(existing), existing.last_event_id)) return

        val updated = existing.copy(
            status = ReducerConstants.STATUS_DELETED,
            updated_t = event.time.t,
            last_event_id = event.id,
            last_event_t = event.time.t,
            last_event_lt = event.time.lt ?: 0L,
            last_event_actor = event.actor.did,
        )
        mapDao.upsertMarker(updated)
        upsertLayer(gid, existing.layer_id, event.time.t)
    }

    private fun applyAreaUpsert(event: EventCore, gid: String) {
        val areaId = event.ref.target ?: JsonAccess.stringValue(event.data, "area_id") ?: event.id
        val existing = mapDao.findArea(gid, areaId)
        val geometryElement = JsonAccess.elementValue(event.data, "geometry")
        val geometryJson = geometryElement?.let { CanonicalJson.canonicalize(it) } ?: existing?.geometry_json
        if (geometryJson == null) return

        val clock = eventClock(event)
        if (!isNewer(event, clock, existing?.let { existingClock(it) }, existing?.last_event_id)) return

        val layerId = JsonAccess.stringValue(event.data, "layer_id") ?: ReducerConstants.DEFAULT_LAYER_ID
        upsertLayer(gid, layerId, event.time.t)

        val metaTtlSeconds = if (preserveTemporalOnUpdate && existing != null) null else event.meta?.ttl_s
        val explicitExpiresAt = if (acceptExplicitExpiryOnAdd && existing == null && event.type.endsWith(".ADD")) {
            JsonAccess.longValue(event.data, "expires_at")
        } else null
        val expiresAt = if (preserveTemporalOnUpdate && existing != null) {
            existing.expires_at
        } else if (explicitExpiresAt != null) {
            explicitExpiresAt
        } else if (metaTtlSeconds != null) {
            val ttlSeconds = TtlHelper.resolveTtlSeconds(event.type, metaTtlSeconds(event))
            TtlHelper.expiresAt(event.time.t, ttlSeconds)
        } else {
            existing?.expires_at ?: run {
                if (legacyNoExpiryOnAdd && event.meta?.ttl_s == null) null else {
                    val ttlSeconds = TtlHelper.resolveTtlSeconds(event.type, null)
                    TtlHelper.expiresAt(event.time.t, ttlSeconds)
                }
            }
        }

        val eventNote = JsonAccess.stringValue(event.data, "note")
        val normalizedNote = eventNote?.trim()?.ifBlank { null }
        val eventConfidence = (
            JsonAccess.stringValue(event.data, "confidence")
                ?: (JsonAccess.elementValue(event.data, "confidence") as? JsonPrimitive)?.content
        )?.toIntOrNull()
        val removalRequestedT = JsonAccess.longValue(event.data, "removal_requested_t")
        val removalRequestedActor = JsonAccess.stringValue(event.data, "removal_requested_actor")
        val removalReason = JsonAccess.stringValue(event.data, "removal_reason")
        val removalCancelT = JsonAccess.longValue(event.data, "removal_cancel_t")
        val isNonOwner = existing != null && existing.actor_did != event.actor.did
        if (isNonOwner && (eventNote != null || eventConfidence != null)) {
            val existingVote = voteDao.find(gid, areaId, event.actor.did)
            val created = existingVote?.created_t ?: event.time.t
            val confidence = eventConfidence ?: existingVote?.confidence ?: CONF_REPORTED
            voteDao.upsert(
                MapItemVoteEntity(
                    gid = gid,
                    item_id = areaId,
                    item_type = "area",
                    actor_did = event.actor.did,
                    note = if (eventNote != null) normalizedNote else existingVote?.note,
                    confidence = confidence,
                    created_t = created,
                    updated_t = event.time.t,
                )
            )
        }

        val area = MapAreaEntity(
            area_id = areaId,
            gid = gid,
            layer_id = layerId,
            geometry_json = geometryJson,
            label = JsonAccess.stringValue(event.data, "label") ?: existing?.label,
            note = if (isNonOwner) {
                existing?.note
            } else if (eventNote != null) {
                normalizedNote
            } else {
                existing?.note
            },
            kind = JsonAccess.stringValue(event.data, "kind") ?: existing?.kind,
            confidence = if (isNonOwner) {
                existing?.confidence ?: CONF_REPORTED
            } else {
                eventConfidence ?: existing?.confidence ?: CONF_REPORTED
            },
            status = ReducerConstants.STATUS_ACTIVE,
            created_t = existing?.created_t ?: event.time.t,
            updated_t = event.time.t,
            actor_did = existing?.actor_did ?: event.actor.did,
            removal_requested_t = removalRequestedT ?: existing?.removal_requested_t,
            removal_requested_actor = removalRequestedActor ?: existing?.removal_requested_actor,
            removal_reason = removalReason ?: existing?.removal_reason,
            removal_cancel_t = removalCancelT ?: existing?.removal_cancel_t,
            expires_at = expiresAt,
            last_event_id = event.id,
            last_event_t = event.time.t,
            last_event_lt = event.time.lt ?: 0L,
            last_event_actor = event.actor.did,
        )
        mapDao.upsertArea(area)
    }

    private fun applyAreaDelete(event: EventCore, gid: String) {
        val areaId = event.ref.target ?: JsonAccess.stringValue(event.data, "area_id") ?: return
        val existing = mapDao.findArea(gid, areaId) ?: return
        val clock = eventClock(event)
        if (!isNewer(event, clock, existingClock(existing), existing.last_event_id)) return

        val updated = existing.copy(
            status = ReducerConstants.STATUS_DELETED,
            updated_t = event.time.t,
            last_event_id = event.id,
            last_event_t = event.time.t,
            last_event_lt = event.time.lt ?: 0L,
            last_event_actor = event.actor.did,
        )
        mapDao.upsertArea(updated)
        upsertLayer(gid, existing.layer_id, event.time.t)
    }

    private fun upsertLayer(gid: String, layerId: String, updatedAt: Long) {
        val layer = MapLayerEntity(
            layer_id = layerId,
            gid = gid,
            name = null,
            status = ReducerConstants.STATUS_ACTIVE,
            updated_t = updatedAt,
        )
        mapDao.upsertLayer(layer)
    }

    private fun eventClock(event: EventCore): EventClock {
        return EventClock(
            t = event.time.t,
            lt = event.time.lt ?: 0L,
            actorDid = event.actor.did,
        )
    }

    private fun isNewer(event: EventCore, clock: EventClock, existing: EventClock?, existingEventId: String?): Boolean {
        if (EventClockComparator.isNewer(clock, existing)) return true
        return includeEventIdTieBreak && existing != null && existingEventId != null &&
            clock.t == existing.t && clock.lt == existing.lt && clock.actorDid == existing.actorDid && event.id > existingEventId
    }

    private fun metaTtlSeconds(event: EventCore): Long? {
        // Avoid smart-cast on cross-module public property
        val meta = event.meta
        return meta?.ttl_s
    }

    private fun existingClock(existing: MapMarkerEntity): EventClock {
        return EventClock(
            t = existing.last_event_t,
            lt = existing.last_event_lt,
            actorDid = existing.last_event_actor,
        )
    }

    private fun existingClock(existing: MapAreaEntity): EventClock {
        return EventClock(
            t = existing.last_event_t,
            lt = existing.last_event_lt,
            actorDid = existing.last_event_actor,
        )
    }
}
