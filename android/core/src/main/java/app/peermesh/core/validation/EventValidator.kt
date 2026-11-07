package app.peermesh.core.validation

import app.peermesh.core.model.EventCore
import app.peermesh.core.map.MapDomainPayloadContract
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapInformationSourcePolicy
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemEventCodec
import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.core.map.MapItemOperation
import app.peermesh.core.types.EventTypes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object EventValidator {
    const val MAX_CHAT_TEXT_LEN = 200

    fun validate(core: EventCore): Boolean {
        if (core.ev != 1) return false
        if (core.id.isBlank()) return false
        if (!isSupportedType(core.type)) return false
        if (core.actor.did.isBlank()) return false
        if (core.time.t <= 0L) return false
        if (core.time.lt != null && core.time.lt < 0L) return false
        return validatePayload(core)
    }

    private fun isSupportedType(type: String): Boolean {
        return EventTypes.isChat(type) ||
            EventTypes.isMap(type) ||
            EventTypes.isSys(type) ||
            EventTypes.isLiveTrail(type)
    }

    private fun validatePayload(core: EventCore): Boolean {
        val obj = core.data as? JsonObject
        return when (core.type) {
            EventTypes.CHAT_MSG -> {
                val text = obj?.get("text") as? JsonPrimitive ?: return false
                val value = if (text.isString) text.content else text.toString()
                value.length <= MAX_CHAT_TEXT_LEN
            }
            EventTypes.CHAT_REPLY -> {
                val text = obj?.get("text") as? JsonPrimitive ?: return false
                val value = if (text.isString) text.content else text.toString()
                if (value.length > MAX_CHAT_TEXT_LEN) return false
                val replyValue = resolveChatReplyTarget(core, obj)
                replyValue.isNotBlank() && replyValue != core.id
            }
            EventTypes.CHAT_EDIT -> {
                val text = obj?.get("text") as? JsonPrimitive ?: return false
                val value = if (text.isString) text.content else text.toString()
                if (value.length > MAX_CHAT_TEXT_LEN) return false
                val resolved = resolveChatEditTarget(core, obj)
                if (resolved.isBlank() || resolved == core.id) return false
                val targetCreatedT = extractLong(obj, "tct")
                    ?: extractLong(obj, "target_created_t")
                targetCreatedT == null || targetCreatedT > 0L
            }
            EventTypes.CHAT_DEL -> {
                val target = (obj?.get("target_id") as? JsonPrimitive)
                    ?.let { if (it.isString) it.content else it.toString() }
                    ?.trim()
                    .orEmpty()
                val refTarget = core.ref.target?.trim().orEmpty()
                val resolved = target.ifBlank { refTarget }
                resolved.isNotBlank() && resolved != core.id
            }
            EventTypes.MAP_MARKER_ADD, EventTypes.MAP_MARKER_UPD -> {
                val lat = (obj?.get("lat") as? JsonPrimitive)?.let { value ->
                    if (value.isString) value.content.toDoubleOrNull() else value.toString().toDoubleOrNull()
                }
                val lon = (obj?.get("lon") as? JsonPrimitive)?.let { value ->
                    if (value.isString) value.content.toDoubleOrNull() else value.toString().toDoubleOrNull()
                }
                lat != null && lon != null &&
                    (core.type != EventTypes.MAP_MARKER_ADD || validateCanonicalAddDomain(obj, marker = true))
            }
            EventTypes.MAP_MARKER_DEL -> true
            EventTypes.MAP_AREA_ADD, EventTypes.MAP_AREA_UPD -> {
                obj?.get("geometry") != null &&
                    (core.type != EventTypes.MAP_AREA_ADD || validateCanonicalAddDomain(obj, marker = false))
            }
            EventTypes.MAP_AREA_DEL -> true
            EventTypes.MAP_ITEM_CONFIRM,
            EventTypes.MAP_ITEM_SITUATION_UPDATE,
            EventTypes.MAP_ITEM_SITUATION_STATUS,
            EventTypes.MAP_ITEM_ACKNOWLEDGE,
            EventTypes.MAP_ITEM_NOTE,
            -> MapItemEventCodec.decode(core) != null
            EventTypes.SYS_GROUP, EventTypes.SYS_GROUP_UPD -> {
                val name = obj?.get("name") as? JsonPrimitive ?: return false
                val value = if (name.isString) name.content else name.toString()
                value.isNotBlank() && value.length <= 80
            }
            EventTypes.SYS_USER, EventTypes.SYS_USER_UPD -> {
                val name = (obj?.get("display_name") as? JsonPrimitive)
                    ?: (obj?.get("name") as? JsonPrimitive)
                    ?: return false
                val value = if (name.isString) name.content else name.toString()
                if (value.isBlank() || value.length > 80) return false
                val avatar = obj?.get("avatar_id") as? JsonPrimitive
                if (avatar != null) {
                    val avatarValue = if (avatar.isString) avatar.content else avatar.toString()
                    if (avatarValue.isBlank() || avatarValue.length > 40) return false
                }
                true
            }
            EventTypes.SYS_MEMBER_REMOVED -> {
                val payload = obj ?: return false
                val target = (payload["target_peer_id"] as? JsonPrimitive)
                    ?.let { if (it.isString) it.content else it.toString() }
                    ?.trim()
                    .orEmpty()
                val refTarget = core.ref.target?.trim().orEmpty()
                val resolved = target.ifBlank { refTarget }
                resolved.isNotBlank() && resolved != core.actor.did
            }
            EventTypes.SYS_GROUP_REKEY -> {
                val payload = obj ?: return false
                val newKid = (payload["new_kid"] as? JsonPrimitive)
                    ?.let { if (it.isString) it.content else it.toString() }
                    ?.trim()
                    .orEmpty()
                newKid.isNotBlank()
            }
            EventTypes.SYS_GROUP_KEY_SHARE -> {
                val payload = obj ?: return false
                val newKid = (payload["new_kid"] as? JsonPrimitive)
                    ?.let { if (it.isString) it.content else it.toString() }
                    ?.trim()
                    .orEmpty()
                val groupKey = (payload["group_key"] as? JsonPrimitive)
                    ?.let { if (it.isString) it.content else it.toString() }
                    ?.trim()
                    .orEmpty()
                newKid.isNotBlank() && groupKey.isNotBlank()
            }
            EventTypes.SYS_ACK -> {
                val payload = obj ?: return false
                val ackType = payload["ack_type"] as? JsonPrimitive ?: return false
                val ackValue = if (ackType.isString) ackType.content else ackType.toString()
                if (ackValue != "received" && ackValue != "seen") return false
                val targetIds = extractAckTargets(payload)
                targetIds.isNotEmpty()
            }
            EventTypes.LIVE_TRAIL_START -> {
                val payload = obj ?: return false
                val trailId = payload["trail_id"] as? JsonPrimitive ?: return false
                val value = if (trailId.isString) trailId.content else trailId.toString()
                value.isNotBlank()
            }
            EventTypes.LIVE_TRAIL_POINT_BATCH -> {
                val payload = obj ?: return false
                val trailId = payload["trail_id"] as? JsonPrimitive ?: return false
                val value = if (trailId.isString) trailId.content else trailId.toString()
                if (value.isBlank()) return false
                val points = payload["points"] ?: return false
                points is kotlinx.serialization.json.JsonArray
            }
            EventTypes.LIVE_TRAIL_END -> {
                val payload = obj ?: return false
                val trailId = payload["trail_id"] as? JsonPrimitive ?: return false
                val value = if (trailId.isString) trailId.content else trailId.toString()
                value.isNotBlank()
            }
            else -> false
        }
    }

    private fun extractAckTargets(payload: JsonObject): List<String> {
        val list = mutableListOf<String>()
        val target = payload["target_id"] as? JsonPrimitive
        val single = target?.let { if (it.isString) it.content else it.toString() }?.trim()
        if (!single.isNullOrBlank()) {
            list.add(single)
        }
        val targetIds = payload["target_ids"] as? JsonArray
        targetIds?.forEach { item ->
            val primitive = item as? JsonPrimitive ?: return@forEach
            val value = if (primitive.isString) primitive.content else primitive.toString()
            val normalized = value.trim()
            if (normalized.isNotBlank()) {
                list.add(normalized)
            }
        }
        return list.distinct()
    }

    private fun resolveChatReplyTarget(core: EventCore, payload: JsonObject): String {
        val legacy = (payload["reply_to"] as? JsonPrimitive)
            ?.let { if (it.isString) it.content else it.toString() }
            ?.trim()
            .orEmpty()
        val refParent = core.ref.parent?.trim().orEmpty()
        return legacy.ifBlank { refParent }
    }

    private fun resolveChatEditTarget(core: EventCore, payload: JsonObject): String {
        val legacy = (payload["target_id"] as? JsonPrimitive)
            ?.let { if (it.isString) it.content else it.toString() }
            ?.trim()
            .orEmpty()
        val refTarget = core.ref.target?.trim().orEmpty()
        return legacy.ifBlank { refTarget }
    }

    private fun extractLong(payload: JsonObject, key: String): Long? {
        val primitive = payload[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content.toLongOrNull() else primitive.toString().toLongOrNull()
    }

    private fun validateCanonicalAddDomain(payload: JsonObject?, marker: Boolean): Boolean {
        payload ?: return false
        if (!payload.containsKey(MapDomainPayloadContract.EXTENSION_VERSION_FIELD)) return true
        val versionPrimitive = payload[MapDomainPayloadContract.EXTENSION_VERSION_FIELD] as? JsonPrimitive
            ?: return false
        if (versionPrimitive.isString) return false
        val version = versionPrimitive.toString().toLongOrNull()
        if (version != MapDomainPayloadContract.EXTENSION_VERSION_1.toLong()) return false
        val category = MapItemCategory.fromWireValue(extractString(payload, MapDomainPayloadContract.CATEGORY_FIELD))
            ?: return false
        val kind = extractString(payload, "kind") ?: return false
        val catalogCategory = MapItemKindCatalog.categoryFor(kind) ?: return false
        if (category != catalogCategory) return false
        if (marker && !MapItemKindCatalog.supportsMarker(kind)) return false
        if (!marker && !MapItemKindCatalog.supportsArea(kind)) return false
        val hasSource = payload.containsKey(MapDomainPayloadContract.SOURCE_FIELD)
        val source = MapInformationSource.fromWireValue(extractString(payload, MapDomainPayloadContract.SOURCE_FIELD))
        if (hasSource && source == null) return false
        return MapInformationSourcePolicy.isValid(category, MapItemOperation.CREATE, source)
    }

    private fun extractString(payload: JsonObject, key: String): String? =
        (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
