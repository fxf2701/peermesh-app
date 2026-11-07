package app.peermesh.data.map.canonical

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import app.peermesh.data.reducer.ReducerConstants
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Strict mapper boundary. It must never silently repair invalid persisted data. */
internal object MapItemCanonicalEntityMapper {
    fun marker(entity: MapMarkerEntity): MapItemCanonicalLookupResult {
        if (entity.gid.isBlank() || entity.marker_id.isBlank()) return invalid()
        if (!validCoordinate(entity.lat, entity.lon)) return invalid()
        if (entity.radius_m?.let { !it.isFinite() || it <= 0.0 } == true) return invalid()
        val availability = availability(entity.status) ?: return invalid()
        if (!validExpiry(entity.expires_at)) return invalid()
        return MapItemCanonicalLookupResult.Found(
            MarkerCanonicalRead(
                groupId = entity.gid,
                itemType = MapItemType.MARKER,
                itemId = entity.marker_id,
                normalizedKind = MapItemKindCatalog.normalize(entity.kind),
                label = entity.label,
                note = entity.note,
                availability = availability,
                expiresAt = entity.expires_at,
                latitude = entity.lat,
                longitude = entity.lon,
                radiusMeters = entity.radius_m,
            ),
        )
    }

    fun area(entity: MapAreaEntity): MapItemCanonicalLookupResult {
        if (entity.gid.isBlank() || entity.area_id.isBlank()) return invalid()
        val availability = availability(entity.status) ?: return invalid()
        if (!validExpiry(entity.expires_at)) return invalid()
        val geometry = parseGeometry(entity.geometry_json) ?: return invalid()
        return MapItemCanonicalLookupResult.Found(
            AreaCanonicalRead(
                groupId = entity.gid,
                itemType = MapItemType.AREA,
                itemId = entity.area_id,
                normalizedKind = MapItemKindCatalog.normalize(entity.kind),
                label = entity.label,
                note = entity.note,
                availability = availability,
                expiresAt = entity.expires_at,
                geometrySummary = geometry.summary(),
                geometry = geometry,
            ),
        )
    }

    private fun availability(status: String): MapCanonicalAvailability? = when (status) {
        ReducerConstants.STATUS_ACTIVE -> MapCanonicalAvailability.ACTIVE
        ReducerConstants.STATUS_DELETED -> MapCanonicalAvailability.DELETED
        else -> null
    }

    private fun validExpiry(expiresAt: Long?): Boolean = expiresAt == null || expiresAt > 0L

    private fun validCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun parseGeometry(raw: String): MapCanonicalAreaGeometry? {
        val root = try {
            CanonicalJson.json.parseToJsonElement(raw)
        } catch (_: Exception) {
            return null
        } as? JsonObject ?: return null
        val type = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val coordinates = root["coordinates"] as? JsonArray ?: return null
        return when (type) {
            "Polygon" -> MapCanonicalAreaGeometry.Polygon(polygon(coordinates) ?: return null)
            "MultiPolygon" -> {
                if (coordinates.isEmpty()) return null
                MapCanonicalAreaGeometry.MultiPolygon(
                    coordinates.map { polygon(it as? JsonArray ?: return null) ?: return null },
                )
            }
            else -> null
        }
    }

    private fun polygon(rings: JsonArray): List<List<MapCanonicalCoordinate>>? {
        if (rings.isEmpty()) return null
        return rings.map { ringElement ->
            val ring = ringElement as? JsonArray ?: return null
            if (ring.size < 4) return null
            val points = ring.map { coordinate(it) ?: return null }
            if (points.first() != points.last()) return null
            if (points.dropLast(1).distinct().size < 3) return null
            points
        }
    }

    private fun coordinate(element: JsonElement): MapCanonicalCoordinate? {
        val values = element as? JsonArray ?: return null
        if (values.size != 2) return null
        val longitude = (values[0] as? JsonPrimitive)?.doubleOrNull ?: return null
        val latitude = (values[1] as? JsonPrimitive)?.doubleOrNull ?: return null
        return if (validCoordinate(latitude, longitude)) {
            MapCanonicalCoordinate(latitude, longitude)
        } else {
            null
        }
    }

    private fun MapCanonicalAreaGeometry.summary(): MapCanonicalAreaGeometrySummary = when (this) {
        is MapCanonicalAreaGeometry.Polygon ->
            MapCanonicalAreaGeometrySummary.Polygon(rings.sumOf { it.geometricVertexCount() })
        is MapCanonicalAreaGeometry.MultiPolygon ->
            MapCanonicalAreaGeometrySummary.MultiPolygon(
                polygonCount = polygons.size,
                vertexCount = polygons.sumOf { polygon -> polygon.sumOf { it.geometricVertexCount() } },
            )
    }

    private fun List<MapCanonicalCoordinate>.geometricVertexCount(): Int = size - 1

    private fun invalid() = MapItemCanonicalLookupResult.InvalidRecord
}
