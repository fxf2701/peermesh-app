package app.peermesh.map.application.creation

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapInformationSourcePolicy
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.core.map.MapItemOperation
import app.peermesh.core.map.MapSourceRequirement

/** Application authority for explicit category-kind-shape compatibility. */
fun interface MapCreationCompatibilityPolicy {
    fun evaluate(input: MapCreationCompatibilityInput): MapCreationCompatibilityResult
}

data class MapCreationCompatibilityInput(
    val category: MapItemCategory,
    val shape: MapCreationShape,
    val kind: String,
    val creatorInformationSource: MapInformationSource?,
    val radiusMeters: Double? = null,
)

enum class MapCreationShape {
    MARKER,
    AREA,
}

sealed interface MapCreationCompatibilityResult {
    data object Compatible : MapCreationCompatibilityResult
    data class Incompatible(val reason: MapCreationCompatibilityFailure) : MapCreationCompatibilityResult
}

enum class MapCreationCompatibilityFailure {
    BLANK_KIND,
    CATEGORY_KIND_MISMATCH,
    CATEGORY_SHAPE_MISMATCH,
    KIND_SHAPE_MISMATCH,
    CREATOR_SOURCE_REQUIRED,
    CREATOR_SOURCE_FORBIDDEN,
    RADIUS_FORBIDDEN,
    UNSUPPORTED_COMBINATION,
}

/** Explicit policy over the accepted canonical kind and creator-source contracts. */
class DefaultMapCreationCompatibilityPolicy : MapCreationCompatibilityPolicy {
    override fun evaluate(input: MapCreationCompatibilityInput): MapCreationCompatibilityResult {
        val kind = input.kind.trim()
        if (kind.isEmpty()) return incompatible(MapCreationCompatibilityFailure.BLANK_KIND)
        val catalogCategory = MapItemKindCatalog.categoryFor(kind)
            ?: return incompatible(MapCreationCompatibilityFailure.UNSUPPORTED_COMBINATION)
        if (catalogCategory != input.category) {
            return incompatible(MapCreationCompatibilityFailure.CATEGORY_KIND_MISMATCH)
        }
        if (input.shape == MapCreationShape.AREA &&
            MapItemKindCatalog.canonicalKinds.none {
                MapItemKindCatalog.categoryFor(it) == input.category &&
                    MapItemKindCatalog.supportsArea(it)
            }
        ) {
            return incompatible(MapCreationCompatibilityFailure.CATEGORY_SHAPE_MISMATCH)
        }
        val shapeSupported = when (input.shape) {
            MapCreationShape.MARKER -> MapItemKindCatalog.supportsMarker(kind)
            MapCreationShape.AREA -> MapItemKindCatalog.supportsArea(kind)
        }
        if (!shapeSupported) return incompatible(MapCreationCompatibilityFailure.KIND_SHAPE_MISMATCH)
        if (input.shape == MapCreationShape.AREA && input.radiusMeters != null) {
            return incompatible(MapCreationCompatibilityFailure.RADIUS_FORBIDDEN)
        }
        return when (MapInformationSourcePolicy.requirementFor(input.category, MapItemOperation.CREATE)) {
            MapSourceRequirement.REQUIRED ->
                if (input.creatorInformationSource == null) {
                    incompatible(MapCreationCompatibilityFailure.CREATOR_SOURCE_REQUIRED)
                } else {
                    MapCreationCompatibilityResult.Compatible
                }
            MapSourceRequirement.OPTIONAL -> MapCreationCompatibilityResult.Compatible
            MapSourceRequirement.FORBIDDEN ->
                if (input.creatorInformationSource != null) {
                    incompatible(MapCreationCompatibilityFailure.CREATOR_SOURCE_FORBIDDEN)
                } else {
                    MapCreationCompatibilityResult.Compatible
                }
            null -> incompatible(MapCreationCompatibilityFailure.UNSUPPORTED_COMBINATION)
        }
    }

    private fun incompatible(reason: MapCreationCompatibilityFailure) =
        MapCreationCompatibilityResult.Incompatible(reason)
}
