package app.peermesh.map.application.creation

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.map.application.scene.MapSceneAreaGeometry

/**
 * UI-safe user intent for one canonical map-item creation.
 *
 * Trusted actor, event identity, item identity, timestamps, Lamport values,
 * transport metadata and runtime SDK handles must never enter this contract.
 */
sealed interface MapCreationIntent {
    val groupId: String
    val category: MapItemCategory
    val kind: String
    val label: String?
    val note: String?
    val ttlSeconds: Long?
    val creatorInformationSource: MapInformationSource?

    data class Marker(
        override val groupId: String,
        override val category: MapItemCategory,
        override val kind: String,
        override val label: String?,
        override val note: String?,
        override val ttlSeconds: Long?,
        override val creatorInformationSource: MapInformationSource?,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double?,
    ) : MapCreationIntent

    class Area(
        override val groupId: String,
        override val category: MapItemCategory,
        override val kind: String,
        override val label: String?,
        override val note: String?,
        override val ttlSeconds: Long?,
        override val creatorInformationSource: MapInformationSource?,
        geometry: MapSceneAreaGeometry,
    ) : MapCreationIntent
    {
        val geometry: MapSceneAreaGeometry = geometry.deepCopy()
    }
}

internal fun MapSceneAreaGeometry.deepCopy(): MapSceneAreaGeometry = when (this) {
    is MapSceneAreaGeometry.Polygon -> MapSceneAreaGeometry.Polygon(
        rings.map { ring -> ring.toList() },
    )
    is MapSceneAreaGeometry.MultiPolygon -> MapSceneAreaGeometry.MultiPolygon(
        polygons.map { polygon -> polygon.map { ring -> ring.toList() } },
    )
}
