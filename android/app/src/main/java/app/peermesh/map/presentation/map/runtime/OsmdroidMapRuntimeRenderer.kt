package app.peermesh.map.presentation.map.runtime

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import app.peermesh.map.presentation.map.resources.AndroidMapRenderModel
import app.peermesh.map.presentation.map.resources.ResolvedMapLabel
import app.peermesh.map.presentation.map.resources.ResolvedMapRadius
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/**
 * osmdroid-only M3D2 diff renderer.
 *
 * Implementation constraints:
 * - receive resolved Android models only, never MapSceneItem/domain projections;
 * - key every bundle by exact MapItemIdentity;
 * - add/update/remove only artifacts owned by this renderer;
 * - preserve unrelated map overlays and M3D3 creation-draft artifacts;
 * - render repeated equivalent snapshots idempotently;
 * - preserve Polygon/MultiPolygon structure without flattening rings.
 */
class OsmdroidMapRuntimeRenderer(
    private val mapView: MapView,
    private val registry: MapRuntimeRenderRegistry,
    private val clickListener: MapRuntimeItemClickListener,
) : MapRuntimeRenderer {
    private var closed = false
    /** Start of our contiguous persisted-overlay band; retained across clear(). */
    private var ownedBandStart: Int? = null

    override fun render(snapshot: List<AndroidMapRenderModel>) {
        if (closed) return
        require(snapshot.map { it.identity }.distinct().size == snapshot.size) {
            "render model identities must be unique"
        }
        val incoming = snapshot.associateBy { it.identity }
        (registry.identities() - incoming.keys).forEach(::remove)
        snapshot.sortedBy { it.zOrder() }.forEach { model ->
            val existing = registry.get(model.identity)
            when {
                existing == null -> registry.put(model.identity, create(model))
                existing.matches(model) -> registry.put(model.identity, update(existing, model))
                else -> {
                    remove(model.identity)
                    registry.put(model.identity, create(model))
                }
            }
        }
        // osmdroid has no overlay z-index. Reinsert only our existing handles in the
        // resolved bundle order, leaving draft, location, trail and event overlays alone.
        reorderOwnedArtifacts(snapshot.sortedBy { it.zOrder() })
        mapView.invalidate()
    }

    override fun clear() {
        if (closed) return
        registry.identities().flatMap { registry.get(it)?.overlaysInRenderOrder().orEmpty() }
            .mapNotNull { mapView.overlays.indexOf(it).takeIf { index -> index >= 0 } }
            .minOrNull()?.let { ownedBandStart = it }
        val removed = registry.clear()
        removed.forEach(::removeArtifacts)
        if (removed.isNotEmpty()) mapView.invalidate()
    }

    override fun close() {
        if (closed) return
        clear()
        closed = true
    }

    private fun create(model: AndroidMapRenderModel): MapRuntimeArtifactBundle = when (model) {
        is AndroidMapRenderModel.Marker -> {
            val marker = Marker(mapView)
            configureMarker(marker, model)
            add(marker)
            MapRuntimeArtifactBundle.MarkerArtifacts(
                model.identity,
                marker,
                createLabel(model.identity, GeoPoint(model.latitude, model.longitude), model.label),
                createRadius(model.identity, GeoPoint(model.latitude, model.longitude), model.radius),
            )
        }
        is AndroidMapRenderModel.Area -> {
            val polygons = model.geometry.members().map { rings ->
                Polygon(mapView).apply {
                    points = rings.first().map { it.toGeoPoint() }
                    setHoles(rings.drop(1).map { ring -> ring.map { it.toGeoPoint() } })
                    fillPaint.color = model.fillColor
                    outlinePaint.color = model.outlineColor
                    outlinePaint.strokeWidth = model.outlineWidthPx
                    outlinePaint.pathEffect = model.outlinePattern.pathEffect
                    title = model.contentDescription
                    subDescription = model.contentDescription
                    relatedObject = model.identity
                    setOnClickListener { _, _, _ ->
                        clickListener.onItemClicked(model.identity)
                        true
                    }
                }.also(::add)
            }
            val centroid = model.geometry.centroid()
            MapRuntimeArtifactBundle.AreaArtifacts(
                model.identity,
                polygons,
                createAreaKindIcon(model.identity, centroid, model.kindIcon, model.contentDescription),
                createLabel(model.identity, centroid, model.label),
            )
        }
    }

    private fun update(
        bundle: MapRuntimeArtifactBundle,
        model: AndroidMapRenderModel,
    ): MapRuntimeArtifactBundle = when {
        bundle is MapRuntimeArtifactBundle.MarkerArtifacts && model is AndroidMapRenderModel.Marker -> {
            configureMarker(bundle.marker, model)
            val point = GeoPoint(model.latitude, model.longitude)
            bundle.copy(
                labelMarker = updateLabel(bundle.labelMarker, model.identity, point, model.label),
                radiusPolygon = updateRadius(bundle.radiusPolygon, model.identity, point, model.radius),
            )
        }
        bundle is MapRuntimeArtifactBundle.AreaArtifacts && model is AndroidMapRenderModel.Area -> {
            val members = model.geometry.members()
            bundle.polygons.zip(members).forEach { (polygon, rings) ->
                polygon.points = rings.first().map { it.toGeoPoint() }
                polygon.setHoles(rings.drop(1).map { ring -> ring.map { it.toGeoPoint() } })
                polygon.fillPaint.color = model.fillColor
                polygon.outlinePaint.color = model.outlineColor
                polygon.outlinePaint.strokeWidth = model.outlineWidthPx
                polygon.outlinePaint.pathEffect = model.outlinePattern.pathEffect
                polygon.title = model.contentDescription
                polygon.subDescription = model.contentDescription
                polygon.relatedObject = model.identity
                polygon.setOnClickListener { _, _, _ ->
                    clickListener.onItemClicked(model.identity)
                    true
                }
            }
            bundle.copy(
                kindIconMarker = updateAreaKindIcon(
                    bundle.kindIconMarker,
                    model.identity,
                    model.geometry.centroid(),
                    model.kindIcon,
                    model.contentDescription,
                ),
                labelMarker = updateLabel(
                    bundle.labelMarker,
                    model.identity,
                    model.geometry.centroid(),
                    model.label,
                ),
            )
        }
        else -> error("shape mismatch must be replaced before update")
    }

    private fun configureMarker(marker: Marker, model: AndroidMapRenderModel.Marker) {
        marker.position = GeoPoint(model.latitude, model.longitude)
        marker.icon = model.icon
        marker.alpha = model.alpha / 255f
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.isFlat = true
        marker.title = model.contentDescription
        marker.subDescription = model.contentDescription
        marker.relatedObject = model.identity
        marker.setOnMarkerClickListener { _, _ ->
            clickListener.onItemClicked(model.identity)
            true
        }
    }

    private fun createAreaKindIcon(
        identity: MapItemIdentity,
        position: GeoPoint,
        icon: android.graphics.drawable.Drawable,
        contentDescription: String,
    ): Marker = Marker(mapView).apply {
        this.position = position
        this.icon = icon
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        isFlat = true
        title = contentDescription
        subDescription = contentDescription
        relatedObject = identity
        setOnMarkerClickListener { _, _ ->
            clickListener.onItemClicked(identity)
            true
        }
    }.also(::add)

    private fun updateAreaKindIcon(
        marker: Marker,
        identity: MapItemIdentity,
        position: GeoPoint,
        icon: android.graphics.drawable.Drawable,
        contentDescription: String,
    ): Marker = marker.apply {
        this.position = position
        this.icon = icon
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        isFlat = true
        title = contentDescription
        subDescription = contentDescription
        relatedObject = identity
        setOnMarkerClickListener { _, _ ->
            clickListener.onItemClicked(identity)
            true
        }
    }

    private fun createLabel(
        identity: MapItemIdentity,
        position: GeoPoint,
        label: ResolvedMapLabel?,
    ): Marker? = label?.let {
        Marker(mapView).apply {
            this.position = position
            icon = it.drawable
            if (it.leadingInsetPx > 0) {
                // The drawable's transparent leading inset equals the marker
                // frame width. Anchor it at the item coordinate, so its visible
                // badge begins to the right of the semantic marker icon.
                setAnchor(Marker.ANCHOR_LEFT, Marker.ANCHOR_CENTER)
            } else if (it.topInsetPx > 0) {
                // The drawable's transparent top inset reserves the semantic
                // icon's lower half plus a gap, placing Marker and Area labels
                // below their icon and leaving the upper-right status badge clear.
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
            } else {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            isFlat = true
            title = it.text
            relatedObject = identity
            setOnMarkerClickListener { _, _ ->
                clickListener.onItemClicked(identity)
                true
            }
        }.also(::add)
    }

    private fun updateLabel(
        marker: Marker?,
        identity: MapItemIdentity,
        position: GeoPoint,
        label: ResolvedMapLabel?,
    ): Marker? = when {
        label == null -> {
            marker?.let(::removeOverlay)
            null
        }
        marker == null -> createLabel(identity, position, label)
        else -> marker.apply {
            this.position = position
            icon = label.drawable
            if (label.leadingInsetPx > 0) {
                setAnchor(Marker.ANCHOR_LEFT, Marker.ANCHOR_CENTER)
            } else if (label.topInsetPx > 0) {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
            } else {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            title = label.text
            relatedObject = identity
            setOnMarkerClickListener { _, _ ->
                clickListener.onItemClicked(identity)
                true
            }
        }
    }

    private fun createRadius(
        identity: MapItemIdentity,
        center: GeoPoint,
        radius: ResolvedMapRadius?,
    ): Polygon? = radius?.let {
        Polygon(mapView).apply {
            points = Polygon.pointsAsCircle(center, it.meters)
            fillPaint.color = it.fillColor
            outlinePaint.color = it.outlineColor
            outlinePaint.strokeWidth = it.outlineWidthPx
            relatedObject = identity
        }.also(::add)
    }

    private fun updateRadius(
        polygon: Polygon?,
        identity: MapItemIdentity,
        center: GeoPoint,
        radius: ResolvedMapRadius?,
    ): Polygon? = when {
        radius == null -> {
            polygon?.let(::removeOverlay)
            null
        }
        polygon == null -> createRadius(identity, center, radius)
        else -> polygon.apply {
            points = Polygon.pointsAsCircle(center, radius.meters)
            fillPaint.color = radius.fillColor
            outlinePaint.color = radius.outlineColor
            outlinePaint.strokeWidth = radius.outlineWidthPx
            relatedObject = identity
        }
    }

    private fun remove(identity: MapItemIdentity) {
        registry.remove(identity)?.let(::removeArtifacts)
    }

    private fun removeArtifacts(bundle: MapRuntimeArtifactBundle) {
        when (bundle) {
            is MapRuntimeArtifactBundle.MarkerArtifacts -> {
                removeOverlay(bundle.marker)
                bundle.labelMarker?.let(::removeOverlay)
                bundle.radiusPolygon?.let(::removeOverlay)
            }
            is MapRuntimeArtifactBundle.AreaArtifacts -> {
                bundle.polygons.forEach(::removeOverlay)
                removeOverlay(bundle.kindIconMarker)
                bundle.labelMarker?.let(::removeOverlay)
            }
        }
    }

    private fun add(overlay: org.osmdroid.views.overlay.Overlay) {
        if (!mapView.overlays.contains(overlay)) mapView.overlays.add(overlay)
    }

    private fun removeOverlay(overlay: org.osmdroid.views.overlay.Overlay) {
        mapView.overlays.remove(overlay)
    }

    private fun reorderOwnedArtifacts(models: List<AndroidMapRenderModel>) {
        val ordered = models.flatMap { model ->
            registry.get(model.identity)?.overlaysInRenderOrder().orEmpty()
        }
        val currentBandStart = ordered.mapNotNull { overlay ->
            mapView.overlays.indexOf(overlay).takeIf { it >= 0 }
        }.minOrNull() ?: return
        // On first render, the first created artifact defines the band. Later clears
        // retain that anchor, clamped against unrelated overlay changes.
        val bandStart = (ownedBandStart ?: currentBandStart).coerceIn(0, mapView.overlays.size)
        // Remove/reinsert the same instances in their original ownership band. This
        // deliberately does not clear the map or move persisted artifacts above
        // unrelated draft/location/trail/event overlays.
        ordered.forEach(::removeOverlay)
        ordered.forEachIndexed { offset, overlay ->
            if (!mapView.overlays.contains(overlay)) {
                mapView.overlays.add((bandStart + offset).coerceAtMost(mapView.overlays.size), overlay)
            }
        }
        ownedBandStart = bandStart
    }

    /** Deterministic child order: area fill/boundaries, area cue, marker radius, main item, then label. */
    private fun MapRuntimeArtifactBundle.overlaysInRenderOrder(): List<org.osmdroid.views.overlay.Overlay> = when (this) {
        is MapRuntimeArtifactBundle.MarkerArtifacts -> listOfNotNull(radiusPolygon, marker, labelMarker)
        is MapRuntimeArtifactBundle.AreaArtifacts ->
            buildList<org.osmdroid.views.overlay.Overlay> {
                addAll(polygons)
                add(kindIconMarker)
                labelMarker?.let(::add)
            }
    }

    private fun MapRuntimeArtifactBundle.matches(model: AndroidMapRenderModel): Boolean = when {
        this is MapRuntimeArtifactBundle.MarkerArtifacts && model is AndroidMapRenderModel.Marker -> true
        this is MapRuntimeArtifactBundle.AreaArtifacts && model is AndroidMapRenderModel.Area ->
            polygons.size == model.geometry.members().size
        else -> false
    }

    private fun AndroidMapRenderModel.zOrder(): Float = when (this) {
        is AndroidMapRenderModel.Marker -> zIndex
        is AndroidMapRenderModel.Area -> zIndex
    }

    private fun MapSceneAreaGeometry.members(): List<List<List<MapSceneCoordinate>>> = when (this) {
        is MapSceneAreaGeometry.Polygon -> listOf(rings)
        is MapSceneAreaGeometry.MultiPolygon -> polygons
    }

    private fun MapSceneAreaGeometry.centroid(): GeoPoint {
        val outerPoints = members().flatMap { it.firstOrNull().orEmpty() }
        if (outerPoints.isEmpty()) return GeoPoint(0.0, 0.0)
        return GeoPoint(
            outerPoints.sumOf { it.latitude } / outerPoints.size,
            outerPoints.sumOf { it.longitude } / outerPoints.size,
        )
    }

    private fun MapSceneCoordinate.toGeoPoint() = GeoPoint(latitude, longitude)
}
