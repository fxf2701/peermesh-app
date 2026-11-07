package app.peermesh.data.map.canonical

import app.peermesh.core.map.MapItemType

/** UI-independent canonical read data; contains no Room entity or row ID. */
sealed interface MapItemCanonicalRead {
    val groupId: String
    val itemType: MapItemType
    val itemId: String
    val normalizedKind: String?
    val label: String?
    val note: String?
    val availability: MapCanonicalAvailability
    val expiresAt: Long?
}

enum class MapCanonicalAvailability {
    ACTIVE,
    DELETED,
}

data class MarkerCanonicalRead(
    override val groupId: String,
    override val itemType: MapItemType,
    override val itemId: String,
    override val normalizedKind: String?,
    override val label: String?,
    override val note: String?,
    override val availability: MapCanonicalAvailability,
    override val expiresAt: Long?,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double?,
) : MapItemCanonicalRead

data class AreaCanonicalRead(
    override val groupId: String,
    override val itemType: MapItemType,
    override val itemId: String,
    override val normalizedKind: String?,
    override val label: String?,
    override val note: String?,
    override val availability: MapCanonicalAvailability,
    override val expiresAt: Long?,
    val geometrySummary: MapCanonicalAreaGeometrySummary,
    val geometry: MapCanonicalAreaGeometry? = null,
) : MapItemCanonicalRead

sealed interface MapCanonicalAreaGeometry {
    data class Polygon(
        val rings: List<List<MapCanonicalCoordinate>>,
    ) : MapCanonicalAreaGeometry

    data class MultiPolygon(
        val polygons: List<List<List<MapCanonicalCoordinate>>>,
    ) : MapCanonicalAreaGeometry
}

data class MapCanonicalCoordinate(
    val latitude: Double,
    val longitude: Double,
)

sealed interface MapCanonicalAreaGeometrySummary {
    data class Polygon(val vertexCount: Int) : MapCanonicalAreaGeometrySummary
    data class MultiPolygon(
        val polygonCount: Int,
        val vertexCount: Int,
    ) : MapCanonicalAreaGeometrySummary
}

sealed interface MapItemCanonicalLookupResult {
    data class Found(val item: MapItemCanonicalRead) : MapItemCanonicalLookupResult
    data object Missing : MapItemCanonicalLookupResult
    data object InvalidRecord : MapItemCanonicalLookupResult
    data object ReadFailure : MapItemCanonicalLookupResult
}
