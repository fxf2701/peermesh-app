package app.peermesh.core.map

import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.types.EventTypes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class MapItemType(val wireValue: String) {
    MARKER("marker"),
    AREA("area");

    companion object {
        fun fromWireValue(value: String?): MapItemType? = entries.firstOrNull { it.wireValue == value }
    }
}

data class MapCanonicalAddExtension(
    val category: MapItemCategory,
    val source: MapInformationSource?,
)

sealed interface MapItemInteractionEvent {
    val itemType: MapItemType

    data class Confirmation(
        override val itemType: MapItemType,
        val action: MapConfirmation,
    ) : MapItemInteractionEvent

    data class SituationUpdate(
        override val itemType: MapItemType,
        val action: MapSituationUpdate,
    ) : MapItemInteractionEvent

    data class SituationStatus(
        override val itemType: MapItemType,
        val action: MapSituationStatusUpdate,
    ) : MapItemInteractionEvent

    data class Acknowledgement(
        override val itemType: MapItemType,
        val action: MapCoordinationAcknowledgement,
    ) : MapItemInteractionEvent

    data class CoordinationNote(
        override val itemType: MapItemType,
        val action: MapCoordinationNote,
    ) : MapItemInteractionEvent
}

object MapItemEventContract {
    fun eventTypeFor(action: MapItemAction): String = when (action) {
        MapItemAction.CONFIRM -> EventTypes.MAP_ITEM_CONFIRM
        MapItemAction.ADD_UPDATE -> EventTypes.MAP_ITEM_SITUATION_UPDATE
        MapItemAction.UPDATE_STATUS -> EventTypes.MAP_ITEM_SITUATION_STATUS
        MapItemAction.ACKNOWLEDGE -> EventTypes.MAP_ITEM_ACKNOWLEDGE
        MapItemAction.ADD_NOTE -> EventTypes.MAP_ITEM_NOTE
    }

    fun actionForEventType(type: String): MapItemAction? = when (type) {
        EventTypes.MAP_ITEM_CONFIRM -> MapItemAction.CONFIRM
        EventTypes.MAP_ITEM_SITUATION_UPDATE -> MapItemAction.ADD_UPDATE
        EventTypes.MAP_ITEM_SITUATION_STATUS -> MapItemAction.UPDATE_STATUS
        EventTypes.MAP_ITEM_ACKNOWLEDGE -> MapItemAction.ACKNOWLEDGE
        EventTypes.MAP_ITEM_NOTE -> MapItemAction.ADD_NOTE
        else -> null
    }
}

object MapItemEventCodec {
    fun encode(itemType: MapItemType, action: MapConfirmation): EventCore = event(
        itemType, action.metadata, MapItemAction.CONFIRM, action.source, action.note,
    )

    fun encode(itemType: MapItemType, action: MapSituationUpdate): EventCore = event(
        itemType, action.metadata, MapItemAction.ADD_UPDATE, action.source, action.content,
        updateType = action.updateType,
    )

    fun encode(itemType: MapItemType, action: MapSituationStatusUpdate): EventCore = event(
        itemType, action.metadata, MapItemAction.UPDATE_STATUS, action.source, action.note,
        lifecycle = action.status,
    )

    fun encode(itemType: MapItemType, action: MapCoordinationAcknowledgement): EventCore =
        event(itemType, action.metadata, MapItemAction.ACKNOWLEDGE, null, null)

    fun encode(itemType: MapItemType, action: MapCoordinationNote): EventCore =
        event(itemType, action.metadata, MapItemAction.ADD_NOTE, null, action.content)

    fun decode(core: EventCore): MapItemInteractionEvent? {
        if (core.ev != 1) return null
        val expectedAction = MapItemEventContract.actionForEventType(core.type) ?: return null
        val payload = core.data as? JsonObject ?: return null
        if (!hasValidCommonShape(core, payload, expectedAction)) return null
        val itemType = MapItemType.fromWireValue(payload.string(MapDomainPayloadContract.ITEM_TYPE_FIELD))
            ?: return null
        val source = payload.optionalSource() ?: if (payload.containsKey(MapDomainPayloadContract.SOURCE_FIELD)) return null else null
        val note = payload.string(MapDomainPayloadContract.ACTION_NOTE_FIELD)
        if (payload.containsKey(MapDomainPayloadContract.ACTION_NOTE_FIELD) && note == null) return null
        val metadata = MapActionMetadata(
            actionId = core.id,
            itemId = core.ref.target!!,
            actorDid = core.actor.did,
            timestampSeconds = core.time.t,
            eventLamport = core.time.lt ?: 0L,
        )
        return when (expectedAction) {
            MapItemAction.CONFIRM -> {
                if (payload.hasAny(MapDomainPayloadContract.UPDATE_TYPE_FIELD, MapDomainPayloadContract.LIFECYCLE_STATUS_FIELD)) return null
                MapItemInteractionEvent.Confirmation(itemType, MapConfirmation(metadata, source, note))
            }
            MapItemAction.ADD_UPDATE -> {
                val updateType = MapSituationUpdateType.fromWireValue(payload.string(MapDomainPayloadContract.UPDATE_TYPE_FIELD)) ?: return null
                if (note.isNullOrBlank() || payload.containsKey(MapDomainPayloadContract.LIFECYCLE_STATUS_FIELD)) return null
                MapItemInteractionEvent.SituationUpdate(itemType, MapSituationUpdate(metadata, note, source, updateType))
            }
            MapItemAction.UPDATE_STATUS -> {
                val lifecycle = MapSituationLifecycleStatus.fromWireValue(payload.string(MapDomainPayloadContract.LIFECYCLE_STATUS_FIELD)) ?: return null
                if (!MapSituationStatusActionPolicy.isValidTarget(lifecycle) || payload.containsKey(MapDomainPayloadContract.UPDATE_TYPE_FIELD)) return null
                MapItemInteractionEvent.SituationStatus(itemType, MapSituationStatusUpdate(metadata, lifecycle, note, source))
            }
            MapItemAction.ACKNOWLEDGE -> {
                if (payload.hasAny(MapDomainPayloadContract.SOURCE_FIELD, MapDomainPayloadContract.ACTION_NOTE_FIELD, MapDomainPayloadContract.UPDATE_TYPE_FIELD, MapDomainPayloadContract.LIFECYCLE_STATUS_FIELD)) return null
                MapItemInteractionEvent.Acknowledgement(itemType, MapCoordinationAcknowledgement(metadata))
            }
            MapItemAction.ADD_NOTE -> {
                if (note.isNullOrBlank() || payload.hasAny(MapDomainPayloadContract.SOURCE_FIELD, MapDomainPayloadContract.UPDATE_TYPE_FIELD, MapDomainPayloadContract.LIFECYCLE_STATUS_FIELD)) return null
                MapItemInteractionEvent.CoordinationNote(itemType, MapCoordinationNote(metadata, note))
            }
        }
    }

    private fun event(
        itemType: MapItemType,
        metadata: MapActionMetadata,
        action: MapItemAction,
        source: MapInformationSource?,
        note: String?,
        updateType: MapSituationUpdateType? = null,
        lifecycle: MapSituationLifecycleStatus? = null,
    ): EventCore {
        require(MapActionInvariantPolicy.isMetadataValid(metadata)) { "Invalid map action metadata" }
        when (action) {
            MapItemAction.ADD_UPDATE -> require(!note.isNullOrBlank()) { "Situation update content must not be blank" }
            MapItemAction.ADD_NOTE -> require(!note.isNullOrBlank()) { "Coordination note content must not be blank" }
            MapItemAction.UPDATE_STATUS -> require(
                lifecycle != null && MapSituationStatusActionPolicy.isValidTarget(lifecycle),
            ) { "User-created status must target no_longer_active" }
            else -> Unit
        }
        return EventCore(
        ev = 1,
        id = metadata.actionId,
        type = MapItemEventContract.eventTypeFor(action),
        actor = Actor(metadata.actorDid),
        time = EventTime(metadata.timestampSeconds, metadata.eventLamport),
        ref = EventRef(target = metadata.itemId),
        data = buildJsonObject {
            put(MapDomainPayloadContract.EXTENSION_VERSION_FIELD, MapDomainPayloadContract.EXTENSION_VERSION_1)
            put(MapDomainPayloadContract.ITEM_TYPE_FIELD, itemType.wireValue)
            put(MapDomainPayloadContract.ACTION_FIELD, action.wireValue)
            put(MapDomainPayloadContract.ACTION_ID_FIELD, metadata.actionId)
            put(MapDomainPayloadContract.ACTION_TIMESTAMP_FIELD, metadata.timestampSeconds)
            source?.let { put(MapDomainPayloadContract.SOURCE_FIELD, it.wireValue) }
            note?.let { put(MapDomainPayloadContract.ACTION_NOTE_FIELD, it) }
            updateType?.let { put(MapDomainPayloadContract.UPDATE_TYPE_FIELD, it.wireValue) }
            lifecycle?.let { put(MapDomainPayloadContract.LIFECYCLE_STATUS_FIELD, it.wireValue) }
        },
        )
    }

    private fun hasValidCommonShape(core: EventCore, payload: JsonObject, action: MapItemAction): Boolean =
        payload.long(MapDomainPayloadContract.EXTENSION_VERSION_FIELD) == MapDomainPayloadContract.EXTENSION_VERSION_1.toLong() &&
            core.id.isNotBlank() && core.actor.did.isNotBlank() && core.time.t > 0 &&
            (core.time.lt == null || core.time.lt >= 0) && core.ref.target?.isNotBlank() == true &&
            payload.string(MapDomainPayloadContract.ACTION_FIELD) == action.wireValue &&
            payload.string(MapDomainPayloadContract.ACTION_ID_FIELD) == core.id &&
            payload.long(MapDomainPayloadContract.ACTION_TIMESTAMP_FIELD) == core.time.t &&
            !payload.keys.any { it in FORBIDDEN_CANONICAL_FIELDS }

    private fun JsonObject.optionalSource(): MapInformationSource? =
        MapInformationSource.fromWireValue(string(MapDomainPayloadContract.SOURCE_FIELD))

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.toString()
        ?.toLongOrNull()

    private fun JsonObject.hasAny(vararg fields: String): Boolean = fields.any(::containsKey)

    val forbiddenCanonicalFields: Set<String> get() = FORBIDDEN_CANONICAL_FIELDS

    private val FORBIDDEN_CANONICAL_FIELDS = setOf(
        "target_id", "item_id", "marker_id", "area_id", "lat", "lon", "radius_m", "geometry", "layer_id", "label", "note", "kind",
        "confidence", "removal_requested_t", "removal_requested_actor", "removal_reason", "removal_cancel_t",
        MapDomainPayloadContract.CATEGORY_FIELD,
        "ttl_s", "expires_at", "owner_edit_until", "interaction_until",
        "actor", "actor_did", "timestamp", "lamport", "lt", "t",
    )
}
