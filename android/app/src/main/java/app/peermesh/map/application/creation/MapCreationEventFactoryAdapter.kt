package app.peermesh.map.application.creation

import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.model.EventCore
import app.peermesh.data.reducer.ReducerConstants
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Narrow adapter over accepted canonical marker/area ADD factories. */
fun interface MapCreationEventFactoryAdapter {
    fun create(
        intent: MapCreationIntent,
        context: MapCreationConstructionContext,
    ): EventCore
}

/** Maps typed creation input to exactly one accepted canonical ADD event. */
class DefaultMapCreationEventFactoryAdapter : MapCreationEventFactoryAdapter {
    override fun create(
        intent: MapCreationIntent,
        context: MapCreationConstructionContext,
    ): EventCore {
        val extension = MapCanonicalAddExtension(intent.category, intent.creatorInformationSource)
        return when (intent) {
            is MapCreationIntent.Marker -> EventFactory.mapMarkerAdd(
                eventId = context.eventId,
                actorDid = context.actorDid,
                createdSeconds = context.createdSeconds,
                lamport = context.lamport,
                markerId = context.itemId,
                layerId = ReducerConstants.DEFAULT_LAYER_ID,
                lat = intent.latitude,
                lon = intent.longitude,
                radiusM = intent.radiusMeters,
                label = intent.label,
                note = intent.note,
                kind = intent.kind,
                ttlSeconds = intent.ttlSeconds,
                domainExtension = extension,
            )
            is MapCreationIntent.Area -> EventFactory.mapAreaAdd(
                eventId = context.eventId,
                actorDid = context.actorDid,
                createdSeconds = context.createdSeconds,
                lamport = context.lamport,
                areaId = context.itemId,
                layerId = ReducerConstants.DEFAULT_LAYER_ID,
                geometry = intent.geometry.toGeoJson(),
                label = intent.label,
                note = intent.note,
                kind = intent.kind,
                ttlSeconds = intent.ttlSeconds,
                domainExtension = extension,
            )
        }
    }

    private fun MapSceneAreaGeometry.toGeoJson(): JsonObject = when (this) {
        is MapSceneAreaGeometry.Polygon -> JsonObject(
            mapOf("type" to JsonPrimitive("Polygon"), "coordinates" to rings.toJson()),
        )
        is MapSceneAreaGeometry.MultiPolygon -> JsonObject(
            mapOf(
                "type" to JsonPrimitive("MultiPolygon"),
                "coordinates" to JsonArray(polygons.map { it.toJson() }),
            ),
        )
    }

    private fun List<List<MapSceneCoordinate>>.toJson(): JsonArray =
        JsonArray(map { ring -> JsonArray(close(ring).map { coordinate -> coordinate.toJson() }) })

    private fun close(ring: List<MapSceneCoordinate>): List<MapSceneCoordinate> =
        if (ring.isNotEmpty() && ring.first() != ring.last()) ring + ring.first() else ring.toList()

    private fun MapSceneCoordinate.toJson(): JsonArray =
        JsonArray(listOf(JsonPrimitive(longitude), JsonPrimitive(latitude)))
}
