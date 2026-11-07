package app.peermesh.map.application.scene

/** UI- and SDK-independent geometry prepared for a future runtime renderer. */
sealed interface MapSceneGeometry {
    data class Marker(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double?,
    ) : MapSceneGeometry

    data class Area(
        val geometry: MapSceneAreaGeometry,
    ) : MapSceneGeometry
}

sealed interface MapSceneAreaGeometry {
    data class Polygon(val rings: List<List<MapSceneCoordinate>>) : MapSceneAreaGeometry
    data class MultiPolygon(val polygons: List<List<List<MapSceneCoordinate>>>) : MapSceneAreaGeometry
}

data class MapSceneCoordinate(
    val latitude: Double,
    val longitude: Double,
)
