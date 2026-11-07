package app.peermesh.map.presentation.creation

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.map.application.creation.MapCreationShape
import app.peermesh.map.application.scene.MapSceneAreaGeometry

/** One presentation-owned source of truth for form fields and draft geometry. */
data class MapCreationDraft(
    val draftId: Long,
    val groupId: String,
    val category: MapItemCategory,
    val shape: MapCreationShape,
    val kind: String? = null,
    val label: String? = null,
    val note: String? = null,
    val ttlSeconds: Long? = null,
    val creatorInformationSource: MapInformationSource? = null,
    val geometry: MapCreationDraftGeometry,
    val validation: MapCreationDraftValidation = MapCreationDraftValidation.NotValidated,
)

sealed interface MapCreationDraftGeometry {
    data class Marker(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double? = null,
    ) : MapCreationDraftGeometry

    data class Area(
        val geometry: MapSceneAreaGeometry,
    ) : MapCreationDraftGeometry
}

sealed interface MapCreationDraftValidation {
    data object NotValidated : MapCreationDraftValidation
    data object Valid : MapCreationDraftValidation
    data class Invalid(val reason: MapCreationDraftFailure) : MapCreationDraftValidation
}

enum class MapCreationDraftFailure {
    INVALID_GROUP_SCOPE,
    INCOMPLETE_FORM,
    INVALID_MARKER_GEOMETRY,
    INVALID_AREA_GEOMETRY,
    INCOMPATIBLE_COMBINATION,
}
