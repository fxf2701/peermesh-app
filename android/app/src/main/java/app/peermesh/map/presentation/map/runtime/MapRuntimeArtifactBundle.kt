package app.peermesh.map.presentation.map.runtime

import app.peermesh.map.application.detail.MapItemIdentity
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

sealed interface MapRuntimeArtifactBundle {
    val identity: MapItemIdentity

    data class MarkerArtifacts(
        override val identity: MapItemIdentity,
        val marker: Marker,
        val labelMarker: Marker?,
        val radiusPolygon: Polygon?,
    ) : MapRuntimeArtifactBundle

    data class AreaArtifacts(
        override val identity: MapItemIdentity,
        val polygons: List<Polygon>,
        val kindIconMarker: Marker,
        val labelMarker: Marker?,
    ) : MapRuntimeArtifactBundle
}
