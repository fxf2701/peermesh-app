package app.peermesh.map.application.rendering

data class MapRenderEnvironment(
    val zoomBand: MapZoomBand,
    val densityBand: MapDensityBand,
)

enum class MapZoomBand {
    FAR,
    MEDIUM,
    NEAR,
}

enum class MapDensityBand {
    SPARSE,
    MODERATE,
    DENSE,
}
