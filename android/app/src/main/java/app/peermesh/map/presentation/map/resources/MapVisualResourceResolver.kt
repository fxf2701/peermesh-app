package app.peermesh.map.presentation.map.resources

import app.peermesh.map.application.rendering.MapItemRenderSpec

fun interface MapVisualResourceResolver {
    fun resolve(spec: MapItemRenderSpec): AndroidMapRenderModel
}
