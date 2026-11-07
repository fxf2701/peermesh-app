package app.peermesh.data.relay

import app.peermesh.core.factory.EventFactory
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.map.MapActionMetadata
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationUpdate
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventMeta
import app.peermesh.data.db.entity.EventEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object M2D2RelayMapFixtures {
    const val GID = "m2d2-group"
    const val KID = "m2d2-key"
    const val LOCAL = "local"
    const val MESH = "mesh"
    const val RELAY = "relay"
    const val DTN = "dtn"
    val groupKey = ByteArray(32) { (it + 1).toByte() }

    fun markerAdd(id: String = "marker-add", timestamp: Long = 1_700_000_000L): EventCore =
        EventFactory.mapMarkerAdd(
            eventId = id,
            actorDid = "did:peer:owner",
            createdSeconds = timestamp,
            lamport = 41,
            markerId = "marker-1",
            layerId = "layer-risk",
            lat = 10.762622,
            lon = 106.660172,
            radiusM = 25.0,
            label = "Flooded road",
            note = "Waist-deep water",
            kind = "risk",
            confidence = 2,
            ttlSeconds = 7_200,
            domainExtension = MapCanonicalAddExtension(
                category = MapItemCategory.SITUATION,
                source = MapInformationSource.FIRSTHAND,
            ),
        ).withRelayKey()

    fun markerUpdate(id: String = "marker-update", timestamp: Long = 1_700_000_001L): EventCore =
        EventFactory.mapMarkerUpdate(
            eventId = id,
            actorDid = "did:peer:owner",
            createdSeconds = timestamp,
            lamport = 42,
            markerId = "marker-1",
            layerId = "layer-risk",
            lat = 10.763,
            lon = 106.661,
            radiusM = 30.0,
            label = "Flood receding",
            note = "Passable by truck",
            kind = "risk",
            confidence = 1,
            ttlSeconds = 3_600,
        ).withRelayKey()

    fun areaAdd(id: String = "area-add", timestamp: Long = 1_700_000_000L): EventCore =
        EventFactory.mapAreaAdd(
            eventId = id,
            actorDid = "did:peer:owner",
            createdSeconds = timestamp,
            lamport = 43,
            areaId = "area-1",
            layerId = "layer-risk",
            geometry = buildJsonArray {
                add(buildJsonArray { add(JsonPrimitive(106.66)); add(JsonPrimitive(10.76)) })
                add(buildJsonArray { add(JsonPrimitive(106.67)); add(JsonPrimitive(10.77)) })
            },
            label = "Evacuation area",
            note = "Avoid low ground",
            kind = "risk",
            confidence = 2,
            ttlSeconds = 10_800,
            domainExtension = MapCanonicalAddExtension(
                category = MapItemCategory.SITUATION,
                source = MapInformationSource.REPORTED_BY_OTHERS,
            ),
        ).withRelayKey()

    fun confirmation(id: String = "confirmation", timestamp: Long = 1_700_000_002L): EventCore =
        EventFactory.mapConfirmation(
            MapItemType.MARKER,
            MapConfirmation(
                metadata = MapActionMetadata(id, "marker-1", "did:peer:observer", timestamp, 44),
                source = MapInformationSource.FIRSTHAND,
                note = "Confirmed in person",
            ),
        ).withRelayKey()

    fun situationUpdate(id: String = "situation-update", timestamp: Long = 1_700_000_003L): EventCore =
        EventFactory.mapSituationUpdate(
            MapItemType.MARKER,
            MapSituationUpdate(
                metadata = MapActionMetadata(id, "marker-1", "did:peer:observer", timestamp, 45),
                content = "Water level is falling",
                source = MapInformationSource.FIRSTHAND,
                updateType = MapSituationUpdateType.SITUATION_CHANGED,
            ),
        ).withRelayKey()

    fun conflicting(core: EventCore): EventCore = core.copy(data = buildJsonObject {
        put("conflicting_payload", "same identity, different semantics")
    })

    fun entity(
        core: EventCore,
        rowId: Long = 1,
        source: String = LOCAL,
        insertedAt: Long = core.time.t + 10,
        invalidAt: Long? = null,
    ): EventEntity = EventEntity(
        id = rowId,
        gid = GID,
        event_id = core.id,
        type = core.type,
        source = source,
        created_t = core.time.t,
        time_lt = core.time.lt ?: 0,
        actor_did = core.actor.did,
        ref_root = core.ref.root,
        ref_parent = core.ref.parent,
        ref_target = core.ref.target,
        ttl_s = core.meta?.ttl_s,
        payload_json = CanonicalJson.json.encodeToString(EventCore.serializer(), core),
        inserted_at = insertedAt,
        invalid_at = invalidAt,
        invalid_reason = invalidAt?.let { "rejected" },
    )

    fun EventCore.withRelayKey(): EventCore = copy(
        meta = (meta ?: EventMeta()).copy(kid = KID),
    )
}
