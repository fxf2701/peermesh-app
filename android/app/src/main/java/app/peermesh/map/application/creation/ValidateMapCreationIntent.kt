package app.peermesh.map.application.creation

fun interface ValidateMapCreationIntent {
    fun validate(intent: MapCreationIntent): MapCreationValidationResult
}

sealed interface MapCreationValidationResult {
    data object Valid : MapCreationValidationResult
    data class Invalid(val failure: MapCreationInputFailure) : MapCreationValidationResult
}

enum class MapCreationInputFailure {
    BLANK_GROUP_ID,
    BLANK_KIND,
    BLANK_LABEL,
    INVALID_TTL,
    INVALID_COORDINATES,
    INVALID_RADIUS,
    INVALID_AREA_GEOMETRY,
    INCOMPATIBLE_CATEGORY_KIND_SHAPE,
    INVALID_CREATOR_SOURCE,
}

/**
 * Lightweight application validation only.
 * Database-dependent membership, semantic and temporal checks remain processor-owned.
 */
class DefaultValidateMapCreationIntent(
    private val compatibilityPolicy: MapCreationCompatibilityPolicy,
) : ValidateMapCreationIntent {
    override fun validate(intent: MapCreationIntent): MapCreationValidationResult {
        if (intent.groupId.isBlank()) return invalid(MapCreationInputFailure.BLANK_GROUP_ID)
        if (intent.kind.isBlank()) return invalid(MapCreationInputFailure.BLANK_KIND)
        if (intent.label?.isBlank() == true) return invalid(MapCreationInputFailure.BLANK_LABEL)
        if (intent.ttlSeconds?.let { it <= 0L } == true) return invalid(MapCreationInputFailure.INVALID_TTL)

        val geometryFailure = when (intent) {
            is MapCreationIntent.Marker -> when {
                !validCoordinate(intent.latitude, intent.longitude) ->
                    MapCreationInputFailure.INVALID_COORDINATES
                intent.radiusMeters?.let { !it.isFinite() || it <= 0.0 } == true ->
                    MapCreationInputFailure.INVALID_RADIUS
                else -> null
            }
            is MapCreationIntent.Area ->
                if (isValid(intent.geometry)) null else MapCreationInputFailure.INVALID_AREA_GEOMETRY
        }
        if (geometryFailure != null) return invalid(geometryFailure)

        val compatibility = compatibilityPolicy.evaluate(
            MapCreationCompatibilityInput(
                category = intent.category,
                shape = when (intent) {
                    is MapCreationIntent.Marker -> MapCreationShape.MARKER
                    is MapCreationIntent.Area -> MapCreationShape.AREA
                },
                kind = intent.kind,
                creatorInformationSource = intent.creatorInformationSource,
                radiusMeters = (intent as? MapCreationIntent.Marker)?.radiusMeters,
            ),
        )
        return when (compatibility) {
            MapCreationCompatibilityResult.Compatible -> MapCreationValidationResult.Valid
            is MapCreationCompatibilityResult.Incompatible -> invalid(
                if (compatibility.reason in setOf(
                        MapCreationCompatibilityFailure.CREATOR_SOURCE_REQUIRED,
                        MapCreationCompatibilityFailure.CREATOR_SOURCE_FORBIDDEN,
                    )
                ) {
                    MapCreationInputFailure.INVALID_CREATOR_SOURCE
                } else {
                    MapCreationInputFailure.INCOMPATIBLE_CATEGORY_KIND_SHAPE
                },
            )
        }
    }

    private fun validCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun isValid(geometry: app.peermesh.map.application.scene.MapSceneAreaGeometry): Boolean =
        when (geometry) {
            is app.peermesh.map.application.scene.MapSceneAreaGeometry.Polygon ->
                geometry.rings.isNotEmpty() && geometry.rings.all(::isValidRing)
            is app.peermesh.map.application.scene.MapSceneAreaGeometry.MultiPolygon ->
                geometry.polygons.isNotEmpty() &&
                    geometry.polygons.all { polygon -> polygon.isNotEmpty() && polygon.all(::isValidRing) }
        }

    private fun isValidRing(
        ring: List<app.peermesh.map.application.scene.MapSceneCoordinate>,
    ): Boolean {
        if (ring.size < 3 || ring.any { !validCoordinate(it.latitude, it.longitude) }) return false
        val unclosed = if (ring.size > 3 && ring.first() == ring.last()) ring.dropLast(1) else ring
        return unclosed.distinct().size >= 3
    }

    private fun invalid(failure: MapCreationInputFailure) =
        MapCreationValidationResult.Invalid(failure)
}
