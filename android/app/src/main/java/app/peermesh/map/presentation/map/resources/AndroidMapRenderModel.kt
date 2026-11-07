package app.peermesh.map.presentation.map.resources

import android.graphics.PathEffect
import android.graphics.drawable.Drawable
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.scene.MapSceneAreaGeometry

data class ResolvedMapLabel(
    val text: String,
    val drawable: Drawable,
    /**
     * Pixel gap before the visual label. Marker labels use this to start after
     * the semantic icon frame rather than covering the icon at the anchor.
     */
    val leadingInsetPx: Int = 0,
    /** Labels below a semantic kind icon reserve its lower half plus a gap. */
    val topInsetPx: Int = 0,
)

data class ResolvedMapRadius(
    val meters: Double,
    val fillColor: Int,
    val outlineColor: Int,
    val outlineWidthPx: Float,
)

data class ResolvedMapOutlinePattern(
    val pathEffect: PathEffect?,
)

sealed interface AndroidMapRenderModel {
    val identity: MapItemIdentity
    val contentDescription: String

    data class Marker(
        override val identity: MapItemIdentity,
        val latitude: Double,
        val longitude: Double,
        val icon: Drawable,
        val alpha: Int,
        val zIndex: Float,
        val label: ResolvedMapLabel?,
        val radius: ResolvedMapRadius?,
        override val contentDescription: String,
    ) : AndroidMapRenderModel

    data class Area(
        override val identity: MapItemIdentity,
        val geometry: MapSceneAreaGeometry,
        /** Centered semantic kind cue, visually distinct from marker artwork. */
        val kindIcon: Drawable,
        val fillColor: Int,
        val outlineColor: Int,
        val outlineWidthPx: Float,
        val outlinePattern: ResolvedMapOutlinePattern,
        val zIndex: Float,
        val label: ResolvedMapLabel?,
        override val contentDescription: String,
    ) : AndroidMapRenderModel
}
