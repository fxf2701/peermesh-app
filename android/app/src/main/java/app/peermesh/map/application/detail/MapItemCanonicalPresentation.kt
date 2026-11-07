package app.peermesh.map.application.detail

sealed interface MapItemCanonicalPresentation {
    val identity: MapItemIdentity
    val normalizedKind: String?
    val label: String?
    val note: String?
    val availability: MapItemCanonicalAvailability
    val expiresAt: Long?
}

enum class MapItemCanonicalAvailability {
    ACTIVE,
    DELETED,
}

data class MapCoordinatesPresentation(
    val latitude: Double,
    val longitude: Double,
)

data class MarkerCanonicalPresentation(
    override val identity: MapItemIdentity,
    override val normalizedKind: String?,
    override val label: String?,
    override val note: String?,
    override val availability: MapItemCanonicalAvailability,
    override val expiresAt: Long?,
    val coordinates: MapCoordinatesPresentation,
    val radiusMeters: Double?,
) : MapItemCanonicalPresentation

data class AreaCanonicalPresentation(
    override val identity: MapItemIdentity,
    override val normalizedKind: String?,
    override val label: String?,
    override val note: String?,
    override val availability: MapItemCanonicalAvailability,
    override val expiresAt: Long?,
    val geometrySummary: MapAreaGeometrySummary,
) : MapItemCanonicalPresentation

sealed interface MapAreaGeometrySummary {
    data class Polygon(val vertexCount: Int) : MapAreaGeometrySummary
    data class MultiPolygon(
        val polygonCount: Int,
        val vertexCount: Int,
    ) : MapAreaGeometrySummary
}
