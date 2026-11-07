package app.peermesh.core.factory

import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventMeta
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.map.MapCoordinationAcknowledgement
import app.peermesh.core.map.MapCoordinationNote
import app.peermesh.core.map.MapDomainPayloadContract
import app.peermesh.core.map.MapItemEventCodec
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.core.map.MapItemOperation
import app.peermesh.core.map.MapInformationSourcePolicy
import app.peermesh.core.map.MapSituationStatusUpdate
import app.peermesh.core.map.MapSituationUpdate
import app.peermesh.core.types.EventTypes
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object EventFactory {
    fun chatMessage(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        text: String,
        replyTo: String? = null,
        ttlSeconds: Long? = null,
    ): EventCore {
        val data = buildJsonObject {
            put("text", JsonPrimitive(text))
        }
        return baseEvent(
            eventId = eventId,
            type = if (replyTo == null) EventTypes.CHAT_MSG else EventTypes.CHAT_REPLY,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = null, parent = replyTo, target = null),
            meta = meta(ttlSeconds),
            data = data,
        )
    }

    fun chatEdit(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        targetMessageId: String,
        targetCreatedSeconds: Long,
        text: String,
    ): EventCore {
        val data = buildJsonObject {
            put("text", JsonPrimitive(text))
            put("tct", JsonPrimitive(targetCreatedSeconds))
        }
        return baseEvent(
            eventId = eventId,
            type = EventTypes.CHAT_EDIT,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = null, parent = null, target = targetMessageId),
            meta = null,
            data = data,
        )
    }

    fun chatDelete(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        targetMessageId: String,
    ): EventCore {
        val data = buildJsonObject {
            put("target_id", JsonPrimitive(targetMessageId))
        }
        return baseEvent(
            eventId = eventId,
            type = EventTypes.CHAT_DEL,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = null, parent = null, target = targetMessageId),
            meta = null,
            data = data,
        )
    }

    fun sysAck(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        targetEventId: String,
        ackType: String,
        targetEventIds: List<String> = listOf(targetEventId),
    ): EventCore {
        val normalizedTargets = targetEventIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val primaryTarget = normalizedTargets.firstOrNull() ?: targetEventId
        val data = buildJsonObject {
            put("ack_type", JsonPrimitive(ackType))
            put("target_id", JsonPrimitive(primaryTarget))
            put("target_ids", buildJsonArray {
                normalizedTargets.forEach { add(JsonPrimitive(it)) }
            })
        }
        return baseEvent(
            eventId = eventId,
            type = EventTypes.SYS_ACK,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = null, parent = null, target = primaryTarget),
            meta = null,
            data = data,
        )
    }

    fun sysMemberRemoved(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        targetPeerId: String,
    ): EventCore {
        val data = buildJsonObject {
            put("target_peer_id", JsonPrimitive(targetPeerId))
        }
        return baseEvent(
            eventId = eventId,
            type = EventTypes.SYS_MEMBER_REMOVED,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = null, parent = null, target = targetPeerId),
            meta = null,
            data = data,
        )
    }

    fun sysGroupRekey(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        previousKid: String?,
        newKid: String,
        rotationReason: String?,
        ttlSeconds: Long? = null,
    ): EventCore {
        val data = buildJsonObject {
            if (!previousKid.isNullOrBlank()) put("previous_kid", JsonPrimitive(previousKid))
            put("new_kid", JsonPrimitive(newKid))
            if (!rotationReason.isNullOrBlank()) put("rotation_reason", JsonPrimitive(rotationReason))
        }
        return baseEvent(
            eventId = eventId,
            type = EventTypes.SYS_GROUP_REKEY,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = null, parent = null, target = newKid),
            meta = meta(ttlSeconds),
            data = data,
        )
    }

    fun sysGroupKeyShare(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        previousKid: String?,
        newKid: String,
        groupKeyBase64: String,
        ttlSeconds: Long? = null,
    ): EventCore {
        val data = buildJsonObject {
            if (!previousKid.isNullOrBlank()) put("previous_kid", JsonPrimitive(previousKid))
            put("new_kid", JsonPrimitive(newKid))
            put("group_key", JsonPrimitive(groupKeyBase64))
        }
        return baseEvent(
            eventId = eventId,
            type = EventTypes.SYS_GROUP_KEY_SHARE,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = null, parent = null, target = newKid),
            meta = meta(ttlSeconds),
            data = data,
        )
    }

    fun mapMarkerAdd(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        markerId: String,
        layerId: String,
        lat: Double,
        lon: Double,
        radiusM: Double?,
        label: String? = null,
        note: String?,
        kind: String?,
        // Confidence: 2=observed, 1=reported (default), 0=uncertain
        confidence: Int = 1,
        ttlSeconds: Long? = null,
        removalRequestedT: Long? = null,
        removalRequestedActor: String? = null,
        removalReason: String? = null,
        removalCancelT: Long? = null,
        domainExtension: MapCanonicalAddExtension? = null,
    ): EventCore {
        validateDomainExtension(domainExtension, kind, marker = true)
        val data = mapMarkerData(
            markerId,
            layerId,
            lat,
            lon,
            radiusM,
            label,
            note,
            kind,
            confidence,
            removalRequestedT,
            removalRequestedActor,
            removalReason,
            removalCancelT,
            domainExtension,
        )
        return baseEvent(
            eventId = eventId,
            type = EventTypes.MAP_MARKER_ADD,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = eventId, parent = null, target = markerId),
            meta = meta(ttlSeconds),
            data = data,
        )
    }

    fun mapMarkerUpdate(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        markerId: String,
        layerId: String,
        lat: Double,
        lon: Double,
        radiusM: Double?,
        label: String? = null,
        note: String?,
        kind: String?,
        // Confidence: 2=observed, 1=reported (default), 0=uncertain
        confidence: Int = 1,
        ttlSeconds: Long? = null,
        removalRequestedT: Long? = null,
        removalRequestedActor: String? = null,
        removalReason: String? = null,
        removalCancelT: Long? = null,
        rootEventId: String? = null,
    ): EventCore {
        val data = mapMarkerData(
            markerId,
            layerId,
            lat,
            lon,
            radiusM,
            label,
            note,
            kind,
            confidence,
            removalRequestedT,
            removalRequestedActor,
            removalReason,
            removalCancelT,
        )
        return baseEvent(
            eventId = eventId,
            type = EventTypes.MAP_MARKER_UPD,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = rootEventId, parent = null, target = markerId),
            meta = meta(ttlSeconds),
            data = data,
        )
    }

    fun mapMarkerDelete(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        markerId: String,
        rootEventId: String? = null,
    ): EventCore {
        val data = buildJsonObject {
            put("marker_id", JsonPrimitive(markerId))
        }
        return baseEvent(
            eventId = eventId,
            type = EventTypes.MAP_MARKER_DEL,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = rootEventId, parent = null, target = markerId),
            meta = null,
            data = data,
        )
    }

    fun mapAreaAdd(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        areaId: String,
        layerId: String,
        geometry: JsonElement,
        label: String? = null,
        note: String?,
        kind: String?,
        // Confidence: 2=observed, 1=reported (default), 0=uncertain
        confidence: Int = 1,
        ttlSeconds: Long? = null,
        removalRequestedT: Long? = null,
        removalRequestedActor: String? = null,
        removalReason: String? = null,
        removalCancelT: Long? = null,
        domainExtension: MapCanonicalAddExtension? = null,
    ): EventCore {
        validateDomainExtension(domainExtension, kind, marker = false)
        val data = mapAreaData(
            areaId,
            layerId,
            geometry,
            label,
            note,
            kind,
            confidence,
            removalRequestedT,
            removalRequestedActor,
            removalReason,
            removalCancelT,
            domainExtension,
        )
        return baseEvent(
            eventId = eventId,
            type = EventTypes.MAP_AREA_ADD,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = eventId, parent = null, target = areaId),
            meta = meta(ttlSeconds),
            data = data,
        )
    }

    fun mapAreaUpdate(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        areaId: String,
        layerId: String,
        geometry: JsonElement,
        label: String? = null,
        note: String?,
        kind: String?,
        // Confidence: 2=observed, 1=reported (default), 0=uncertain
        confidence: Int = 1,
        ttlSeconds: Long? = null,
        removalRequestedT: Long? = null,
        removalRequestedActor: String? = null,
        removalReason: String? = null,
        removalCancelT: Long? = null,
        rootEventId: String? = null,
    ): EventCore {
        val data = mapAreaData(
            areaId,
            layerId,
            geometry,
            label,
            note,
            kind,
            confidence,
            removalRequestedT,
            removalRequestedActor,
            removalReason,
            removalCancelT,
        )
        return baseEvent(
            eventId = eventId,
            type = EventTypes.MAP_AREA_UPD,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = rootEventId, parent = null, target = areaId),
            meta = meta(ttlSeconds),
            data = data,
        )
    }

    fun sysGroup(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        name: String,
        gid: String? = null,
        ownerDid: String? = null,
        contextProfile: String? = null,
        groupCreatedAt: Long? = null,
        groupUpdatedAt: Long? = null,
    ): EventCore {
        val data = buildJsonObject {
            if (!gid.isNullOrBlank()) put("gid", JsonPrimitive(gid))
            put("name", JsonPrimitive(name))
            if (!ownerDid.isNullOrBlank()) put("owner_did", JsonPrimitive(ownerDid))
            if (!contextProfile.isNullOrBlank()) put("context_profile", JsonPrimitive(contextProfile))
            if (groupCreatedAt != null) put("created_at", JsonPrimitive(groupCreatedAt))
            if (groupUpdatedAt != null) put("updated_at", JsonPrimitive(groupUpdatedAt))
        }
        return baseEvent(
            eventId = eventId,
            type = EventTypes.SYS_GROUP,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = null, parent = null, target = null),
            meta = null,
            data = data,
        )
    }

    fun sysGroupUpdate(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        name: String,
    ): EventCore {
        return sysGroup(
            eventId = eventId,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            name = name,
        ).copy(type = EventTypes.SYS_GROUP_UPD)
    }

    fun sysUser(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        displayName: String,
        avatarId: String? = null,
    ): EventCore {
        val data = buildJsonObject {
            put("display_name", JsonPrimitive(displayName))
            if (!avatarId.isNullOrBlank()) {
                put("avatar_id", JsonPrimitive(avatarId))
            }
        }
        return baseEvent(
            eventId = eventId,
            type = EventTypes.SYS_USER,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = null, parent = null, target = null),
            meta = null,
            data = data,
        )
    }

    fun sysUserUpdate(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        displayName: String,
        avatarId: String? = null,
    ): EventCore {
        return sysUser(
            eventId = eventId,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            displayName = displayName,
            avatarId = avatarId,
        ).copy(type = EventTypes.SYS_USER_UPD)
    }

    fun mapAreaDelete(
        eventId: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        areaId: String,
        rootEventId: String? = null,
    ): EventCore {
        val data = buildJsonObject {
            put("area_id", JsonPrimitive(areaId))
        }
        return baseEvent(
            eventId = eventId,
            type = EventTypes.MAP_AREA_DEL,
            actorDid = actorDid,
            createdSeconds = createdSeconds,
            lamport = lamport,
            ref = EventRef(root = rootEventId, parent = null, target = areaId),
            meta = null,
            data = data,
        )
    }

    fun mapConfirmation(itemType: MapItemType, action: MapConfirmation): EventCore =
        MapItemEventCodec.encode(itemType, action)

    fun mapSituationUpdate(itemType: MapItemType, action: MapSituationUpdate): EventCore =
        MapItemEventCodec.encode(itemType, action)

    fun mapSituationStatus(itemType: MapItemType, action: MapSituationStatusUpdate): EventCore =
        MapItemEventCodec.encode(itemType, action)

    fun mapAcknowledgement(itemType: MapItemType, action: MapCoordinationAcknowledgement): EventCore =
        MapItemEventCodec.encode(itemType, action)

    fun mapCoordinationNote(itemType: MapItemType, action: MapCoordinationNote): EventCore =
        MapItemEventCodec.encode(itemType, action)

    private fun mapMarkerData(
        markerId: String,
        layerId: String,
        lat: Double,
        lon: Double,
        radiusM: Double?,
        label: String?,
        note: String?,
        kind: String?,
        confidence: Int,
        removalRequestedT: Long? = null,
        removalRequestedActor: String? = null,
        removalReason: String? = null,
        removalCancelT: Long? = null,
        domainExtension: MapCanonicalAddExtension? = null,
    ): JsonObject {
        return buildJsonObject {
            put("marker_id", JsonPrimitive(markerId))
            put("layer_id", JsonPrimitive(layerId))
            put("lat", JsonPrimitive(lat))
            put("lon", JsonPrimitive(lon))
            if (radiusM != null) put("radius_m", JsonPrimitive(radiusM))
            if (label != null) put("label", JsonPrimitive(label))
            if (note != null) put("note", JsonPrimitive(note))
            if (kind != null) put("kind", JsonPrimitive(kind))
            put("confidence", JsonPrimitive(confidence))
            if (removalRequestedT != null) put("removal_requested_t", JsonPrimitive(removalRequestedT))
            if (removalRequestedActor != null) put("removal_requested_actor", JsonPrimitive(removalRequestedActor))
            if (removalReason != null) put("removal_reason", JsonPrimitive(removalReason))
            if (removalCancelT != null) put("removal_cancel_t", JsonPrimitive(removalCancelT))
            domainExtension?.let { extension ->
                put(MapDomainPayloadContract.EXTENSION_VERSION_FIELD, MapDomainPayloadContract.EXTENSION_VERSION_1)
                put(MapDomainPayloadContract.CATEGORY_FIELD, extension.category.wireValue)
                extension.source?.let { put(MapDomainPayloadContract.SOURCE_FIELD, it.wireValue) }
            }
        }
    }

    private fun mapAreaData(
        areaId: String,
        layerId: String,
        geometry: JsonElement,
        label: String?,
        note: String?,
        kind: String?,
        confidence: Int,
        removalRequestedT: Long? = null,
        removalRequestedActor: String? = null,
        removalReason: String? = null,
        removalCancelT: Long? = null,
        domainExtension: MapCanonicalAddExtension? = null,
    ): JsonObject {
        return buildJsonObject {
            put("area_id", JsonPrimitive(areaId))
            put("layer_id", JsonPrimitive(layerId))
            put("geometry", geometry)
            if (label != null) put("label", JsonPrimitive(label))
            if (note != null) put("note", JsonPrimitive(note))
            if (kind != null) put("kind", JsonPrimitive(kind))
            put("confidence", JsonPrimitive(confidence))
            if (removalRequestedT != null) put("removal_requested_t", JsonPrimitive(removalRequestedT))
            if (removalRequestedActor != null) put("removal_requested_actor", JsonPrimitive(removalRequestedActor))
            if (removalReason != null) put("removal_reason", JsonPrimitive(removalReason))
            if (removalCancelT != null) put("removal_cancel_t", JsonPrimitive(removalCancelT))
            domainExtension?.let { extension ->
                put(MapDomainPayloadContract.EXTENSION_VERSION_FIELD, MapDomainPayloadContract.EXTENSION_VERSION_1)
                put(MapDomainPayloadContract.CATEGORY_FIELD, extension.category.wireValue)
                extension.source?.let { put(MapDomainPayloadContract.SOURCE_FIELD, it.wireValue) }
            }
        }
    }

    private fun baseEvent(
        eventId: String,
        type: String,
        actorDid: String,
        createdSeconds: Long,
        lamport: Long,
        ref: EventRef,
        meta: EventMeta?,
        data: JsonElement,
    ): EventCore {
        return EventCore(
            ev = 1,
            id = eventId,
            type = type,
            actor = Actor(did = actorDid),
            time = EventTime(t = createdSeconds, lt = lamport),
            ref = ref,
            meta = meta,
            data = data,
        )
    }

    private fun meta(ttlSeconds: Long?): EventMeta? {
        return ttlSeconds?.let { EventMeta(ttl_s = it, prio = null) }
    }

    private fun validateDomainExtension(
        extension: MapCanonicalAddExtension?,
        kind: String?,
        marker: Boolean,
    ) {
        if (extension == null) return
        require(kind != null) { "Domain-aware ADD requires a known kind" }
        val category = MapItemKindCatalog.categoryFor(kind)
        require(category == extension.category) { "Domain category must match normalized kind" }
        require(if (marker) MapItemKindCatalog.supportsMarker(kind) else MapItemKindCatalog.supportsArea(kind)) {
            "Kind is not supported for this map item type"
        }
        require(MapInformationSourcePolicy.isValid(category, MapItemOperation.CREATE, extension.source)) {
            "Invalid information source for domain-aware ADD"
        }
    }
}
