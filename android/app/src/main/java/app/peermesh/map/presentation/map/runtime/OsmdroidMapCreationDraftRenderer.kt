package app.peermesh.map.presentation.map.runtime

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import app.peermesh.map.presentation.creation.MapCreationDraft
import app.peermesh.map.presentation.creation.MapCreationDraftGeometry
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Overlay

/** osmdroid-only owner of temporary M3D3 creation artifacts. */
class OsmdroidMapCreationDraftRenderer(
    private val mapView: MapView,
    private val onAreaVertexMoved: (index: Int, latitude: Double, longitude: Double, finished: Boolean) -> Unit,
) : MapCreationDraftRenderer {
    private var marker: Marker? = null
    private var radius: Polygon? = null
    private var areaLine: Polyline? = null
    private var areaPolygon: Polygon? = null
    private val vertices = mutableListOf<Marker>()
    private var draggingIndex: Int? = null
    private var closed = false
    private val dragOverlay = object : Overlay() {
        override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
            if (vertices.isEmpty()) return false
            val projection = mapView.projection
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggingIndex = findAreaVertex(event.x, event.y, projection, DEFAULT_HIT_RADIUS_PX)
                    return draggingIndex != null
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = draggingIndex ?: return false
                    val point = projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                    onAreaVertexMoved(index, point.latitude, point.longitude, false)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val index = draggingIndex ?: return false
                    val point = projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                    draggingIndex = null
                    onAreaVertexMoved(index, point.latitude, point.longitude, true)
                    return true
                }
            }
            return false
        }
    }

    init {
        mapView.overlays.add(dragOverlay)
    }

    override fun render(draft: MapCreationDraft?) {
        if (closed) return
        when (val geometry = draft?.geometry) {
            null -> clear()
            is MapCreationDraftGeometry.Marker -> renderMarker(geometry)
            is MapCreationDraftGeometry.Area -> renderArea(geometry.geometry)
        }
        mapView.invalidate()
    }

    fun findAreaVertex(x: Float, y: Float, projection: Projection, hitRadiusPx: Double): Int? {
        var bestIndex: Int? = null
        var bestDistance = Double.MAX_VALUE
        vertices.forEachIndexed { index, vertex ->
            val screen = projection.toPixels(vertex.position, android.graphics.Point())
            val distance = kotlin.math.hypot((x - screen.x).toDouble(), (y - screen.y).toDouble())
            if (distance < bestDistance) {
                bestIndex = index
                bestDistance = distance
            }
        }
        return bestIndex?.takeIf { bestDistance <= hitRadiusPx }
    }

    override fun clear() {
        marker?.let(mapView.overlays::remove)
        radius?.let(mapView.overlays::remove)
        areaLine?.let(mapView.overlays::remove)
        areaPolygon?.let(mapView.overlays::remove)
        vertices.forEach(mapView.overlays::remove)
        marker = null
        radius = null
        areaLine = null
        areaPolygon = null
        vertices.clear()
        draggingIndex = null
        mapView.invalidate()
    }

    override fun close() {
        if (closed) return
        clear()
        mapView.overlays.remove(dragOverlay)
        closed = true
    }

    private fun renderMarker(value: MapCreationDraftGeometry.Marker) {
        clearArea()
        if (!value.latitude.isFinite() || !value.longitude.isFinite()) {
            marker?.let(mapView.overlays::remove)
            marker = null
            return
        }
        val point = GeoPoint(value.latitude, value.longitude)
        val exactMarker = marker ?: Marker(mapView).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker = it
        }
        exactMarker.position = point
        attach(exactMarker)
        val radiusMeters = value.radiusMeters
        if (radiusMeters != null && radiusMeters.isFinite() && radiusMeters > 0.0) {
            val exactRadius = radius ?: Polygon().also { radius = it }
            exactRadius.points = Polygon.pointsAsCircle(point, radiusMeters)
            exactRadius.outlinePaint.color = DRAFT_COLOR
            exactRadius.fillPaint.color = DRAFT_FILL
            attach(exactRadius)
        } else {
            radius?.let(mapView.overlays::remove)
            radius = null
        }
    }

    private fun renderArea(geometry: MapSceneAreaGeometry) {
        clearMarker()
        val points = geometry.outerRing().map { GeoPoint(it.latitude, it.longitude) }
        val exactLine = areaLine ?: Polyline().also { areaLine = it }
        exactLine.outlinePaint.color = DRAFT_COLOR
        exactLine.outlinePaint.strokeWidth = 6f
        exactLine.setPoints(points)
        attach(exactLine)

        if (points.size >= 3) {
            val exactPolygon = areaPolygon ?: Polygon().also { areaPolygon = it }
            exactPolygon.points = points
            exactPolygon.outlinePaint.color = DRAFT_COLOR
            exactPolygon.outlinePaint.strokeWidth = 4f
            exactPolygon.fillPaint.color = Color.TRANSPARENT
            attach(exactPolygon)
        } else {
            areaPolygon?.let(mapView.overlays::remove)
            areaPolygon = null
        }
        vertices.forEach(mapView.overlays::remove)
        vertices.clear()
        points.forEach { point ->
            Marker(mapView).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = vertexIcon()
                isDraggable = false
                vertices += this
                attach(this)
            }
        }
    }

    private fun clearMarker() {
        marker?.let(mapView.overlays::remove)
        radius?.let(mapView.overlays::remove)
        marker = null
        radius = null
    }

    private fun clearArea() {
        areaLine?.let(mapView.overlays::remove)
        areaPolygon?.let(mapView.overlays::remove)
        vertices.forEach(mapView.overlays::remove)
        areaLine = null
        areaPolygon = null
        vertices.clear()
    }

    private fun MapSceneAreaGeometry.outerRing(): List<MapSceneCoordinate> = when (this) {
        is MapSceneAreaGeometry.Polygon -> rings.firstOrNull().orEmpty()
        is MapSceneAreaGeometry.MultiPolygon -> polygons.firstOrNull()?.firstOrNull().orEmpty()
    }.let { ring -> if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring }

    private fun vertexIcon() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        val size = (mapView.resources.displayMetrics.density * 12).toInt().coerceAtLeast(10)
        setSize(size, size)
        setColor(DRAFT_COLOR)
    }

    private fun attach(overlay: org.osmdroid.views.overlay.Overlay) {
        if (!mapView.overlays.contains(overlay)) mapView.overlays.add(overlay)
    }

    private companion object {
        const val DRAFT_COLOR = 0xFF1976D2.toInt()
        const val DRAFT_FILL = 0x221976D2
        const val DEFAULT_HIT_RADIUS_PX = 40.0
    }
}
