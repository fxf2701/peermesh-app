package app.peermesh

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import app.peermesh.data.db.entity.LiveTrailEntity
import app.peermesh.map.presentation.list.MapOwnerDisplayName
import app.peermesh.map.presentation.list.MapOwnerDisplayNameResolver
import app.peermesh.map.presentation.list.MapOwnerNameProjection
import kotlin.math.absoluteValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class MapListAdapter(
    private val onToggleMarkers: (Boolean) -> Unit,
    private val onToggleAreas: (Boolean) -> Unit,
    private val onToggleMarkerVisibility: (MapMarkerEntity, Boolean) -> Unit,
    private val onToggleAreaVisibility: (MapAreaEntity, Boolean) -> Unit,
    private val onMarkerEdit: (MapMarkerEntity) -> Unit,
    private val onMarkerDelete: (MapMarkerEntity) -> Unit,
    private val onMarkerFocus: (MapMarkerEntity) -> Unit = {},
    private val onMarkerDetail: (MapMarkerEntity) -> Unit = {},
    private val onAreaEdit: (MapAreaEntity) -> Unit,
    private val onAreaDelete: (MapAreaEntity) -> Unit,
    private val onAreaFocus: (MapAreaEntity) -> Unit = {},
    private val onAreaDetail: (MapAreaEntity) -> Unit = {},
    private val onToggleLiveTrail: (LiveTrailEntity, Boolean) -> Unit,
    private val onDeleteLiveTrail: (LiveTrailEntity) -> Unit,
    private val onOpenSharedTrails: () -> Unit = {},
    private val onViewAllTrails: (() -> Unit)? = null,
    private val trailsOnlyMode: Boolean = false
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = mutableListOf<MapListItem>()
    private var showMarkers = true
    private var showAreas = true
    private var hiddenMarkers: Set<String> = emptySet()
    private var hiddenAreas: Set<String> = emptySet()
    private var selectedTrailId: String? = null
    private var markerVoteCounts: Map<String, Pair<Int, Int>> = emptyMap()
    private var areaVoteCounts: Map<String, Pair<Int, Int>> = emptyMap()
    private var markerContributionPreview: Map<String, String> = emptyMap()
    private var areaContributionPreview: Map<String, String> = emptyMap()
    private var mapOwnerNames: Map<String, MapOwnerNameProjection> = emptyMap()
    private var liveTrailOwners: Map<String, String> = emptyMap()
    private var liveTrailStaleIds: Set<String> = emptySet()
    private var liveTrailReconnectedUntil: Map<String, Long> = emptyMap()
    private var currentActorDid: String? = null
    private var markerDeleteAllowedIds: Set<String> = emptySet()
    private var areaDeleteAllowedIds: Set<String> = emptySet()

    fun submit(
        markers: List<MapMarkerEntity>,
        areas: List<MapAreaEntity>,
        liveTrails: List<LiveTrailEntity>,
        resources: android.content.res.Resources,
        showMarkers: Boolean,
        showAreas: Boolean,
        hiddenMarkers: Set<String>,
        hiddenAreas: Set<String>,
        selectedTrailId: String?,
        markerVoteCounts: Map<String, Pair<Int, Int>>,
        areaVoteCounts: Map<String, Pair<Int, Int>>,
        mapOwnerNames: Map<String, MapOwnerNameProjection>,
        liveTrailOwners: Map<String, String>,
        liveTrailStaleIds: Set<String>,
        liveTrailReconnectedUntil: Map<String, Long>,
        currentActorDid: String?,
        markerDeleteAllowedIds: Set<String> = emptySet(),
        areaDeleteAllowedIds: Set<String> = emptySet(),
    ) {
        this.showMarkers = showMarkers
        this.showAreas = showAreas
        this.hiddenMarkers = hiddenMarkers
        this.hiddenAreas = hiddenAreas
        this.selectedTrailId = selectedTrailId
        this.markerVoteCounts = markerVoteCounts
        this.areaVoteCounts = areaVoteCounts
        this.mapOwnerNames = mapOwnerNames
        this.liveTrailOwners = liveTrailOwners
        this.liveTrailStaleIds = liveTrailStaleIds
        this.liveTrailReconnectedUntil = liveTrailReconnectedUntil
        this.currentActorDid = currentActorDid
        this.markerDeleteAllowedIds = markerDeleteAllowedIds
        this.areaDeleteAllowedIds = areaDeleteAllowedIds
        items.clear()

        if (trailsOnlyMode) {
            items.add(MapListItem.Header(resources.getString(R.string.map_section_live_trails), HeaderType.LIVE_TRAILS, true))

            if (liveTrails.isEmpty()) {
                items.add(MapListItem.Empty(resources.getString(R.string.map_live_trails_empty)))
            } else {
                liveTrails.forEach { items.add(MapListItem.LiveTrail(it)) }
            }
        } else {
            items.add(MapListItem.Header(resources.getString(R.string.map_section_markers), HeaderType.MARKERS, showMarkers))
            if (markers.isEmpty()) {
                items.add(MapListItem.Empty(resources.getString(R.string.map_markers_empty)))
            } else {
                markers.forEach { items.add(MapListItem.Marker(it)) }
            }

            items.add(MapListItem.Header(resources.getString(R.string.map_section_areas), HeaderType.AREAS, showAreas))
            if (areas.isEmpty()) {
                items.add(MapListItem.Empty(resources.getString(R.string.map_areas_empty)))
            } else {
                areas.forEach { items.add(MapListItem.Area(it)) }
            }

            items.add(MapListItem.Header(resources.getString(R.string.map_section_live_trails), HeaderType.LIVE_TRAILS, true))
            items.add(MapListItem.LiveTrailSummary(liveTrails))
        }

        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is MapListItem.Header -> VIEW_HEADER
        is MapListItem.Empty -> VIEW_EMPTY
        is MapListItem.Marker -> VIEW_MARKER
        is MapListItem.Area -> VIEW_AREA
        is MapListItem.LiveTrail -> VIEW_LIVE_TRAIL
        is MapListItem.LiveTrailSummary -> VIEW_LIVE_TRAIL_SUMMARY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_HEADER -> HeaderHolder(inflater.inflate(R.layout.item_map_section_header, parent, false))
            VIEW_EMPTY -> EmptyHolder(inflater.inflate(R.layout.item_map_empty, parent, false))
            VIEW_MARKER -> MarkerHolder(inflater.inflate(R.layout.item_map_marker, parent, false))
            VIEW_AREA -> AreaHolder(inflater.inflate(R.layout.item_map_area, parent, false))
            VIEW_LIVE_TRAIL -> LiveTrailHolder(inflater.inflate(R.layout.item_map_trail, parent, false))
            VIEW_LIVE_TRAIL_SUMMARY -> SharedTrailsSummaryHolder(
                inflater.inflate(R.layout.item_shared_trails_summary, parent, false)
            )
            else -> throw IllegalArgumentException("Unknown view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is MapListItem.Header -> (holder as HeaderHolder).bind(item)
            is MapListItem.Empty -> (holder as EmptyHolder).bind(item)
            is MapListItem.Marker -> (holder as MarkerHolder).bind(item.entity)
            is MapListItem.Area -> (holder as AreaHolder).bind(item.entity)
            is MapListItem.LiveTrail -> (holder as LiveTrailHolder).bind(item.entity)
            is MapListItem.LiveTrailSummary -> (holder as SharedTrailsSummaryHolder).bind(item.trails)
        }
    }

    override fun getItemCount(): Int = items.size

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.map_section_title)
        private val toggle = view.findViewById<Switch>(R.id.map_section_toggle)
        fun bind(item: MapListItem.Header) {
            title.text = item.title
            toggle.setOnCheckedChangeListener(null)
            if (item.type == HeaderType.LIVE_TRAILS) {
                toggle.visibility = View.GONE
                return
            }
            toggle.visibility = View.VISIBLE
            toggle.isChecked = item.isVisible
            toggle.setOnCheckedChangeListener { _, checked ->
                if (item.type == HeaderType.MARKERS) {
                    onToggleMarkers(checked)
                } else {
                    onToggleAreas(checked)
                }
            }
        }
    }

    private inner class EmptyHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val text = view.findViewById<TextView>(R.id.map_empty_text)
        fun bind(item: MapListItem.Empty) {
            text.text = item.message
        }
    }

    private inner class MarkerHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label = view.findViewById<TextView>(R.id.marker_label)
        private val location = view.findViewById<TextView>(R.id.marker_location)
        private val note = view.findViewById<TextView>(R.id.marker_note)
        private val meta = view.findViewById<TextView>(R.id.marker_meta)
        private val contribPreview = view.findViewById<TextView>(R.id.marker_contrib_preview)
        private val removalStatus = view.findViewById<TextView>(R.id.marker_removal_status)
        private val more = view.findViewById<TextView>(R.id.marker_more)
        private val visibilityToggle = view.findViewById<Switch>(R.id.marker_visibility)
        private val edit = view.findViewById<Button>(R.id.marker_edit)
        private val delete = view.findViewById<Button>(R.id.marker_delete)
        private val actions = view.findViewById<View>(R.id.marker_actions)
        private val details = view.findViewById<Button>(R.id.marker_details)

        fun bind(marker: MapMarkerEntity) {
            val actorDid = DeviceIdProvider.get(itemView.context)
            val kind = marker.kind?.takeIf { it.isNotBlank() }
            val labelText = MapKinds.labelForKey(kind) ?: kind
            val baseLabel = if (labelText != null) {
                "M: $labelText"
            } else {
                "M"
            }
            label.text = baseLabel

            val noteDisplay = stripConfidencePrefix(marker.note)
            note.text = noteDisplay?.takeIf { it.isNotBlank() }
                ?: itemView.context.getString(R.string.map_note_empty)

            val nowSeconds = System.currentTimeMillis() / 1000L
            val age = formatAge(itemView.context, nowSeconds - marker.updated_t)
            val updatedLabel = itemView.context.getString(
                R.string.map_updated_author_format,
                age,
                mapOwnerAttribution(itemView.context, marker.actor_did),
            )
            val coords = itemView.context.getString(
                R.string.map_area_location_format,
                formatCoord(marker.lat),
                formatCoord(marker.lon)
            )
            val radiusLabel = marker.radius_m?.takeIf { it > 0.0 }?.let {
                itemView.context.getString(
                    R.string.map_marker_radius_format,
                    String.format(java.util.Locale.US, "%.0f", it)
                )
            }
            location.text = listOfNotNull(coords, radiusLabel).joinToString(" · ")
            meta.text = updatedLabel
            // Contributions are legacy per-item vote UI. Dedicated Situation and
            // Coordination detail actions replace it; never expose it in rows.
            contribPreview.visibility = View.GONE
            val isOwner = marker.actor_did == actorDid
            // Canonical UPD and DEL have the same owner/lifecycle/window gates.
            // Do not offer an Edit button that the writer must reject.
            val canMutate = isOwner && markerDeleteAllowedIds.contains(marker.marker_id)
            val removalActive = isRemovalRequested(marker.removal_requested_t, marker.removal_cancel_t)
            edit.text = itemView.context.getString(R.string.map_edit_marker)
            edit.visibility = if (canMutate) View.VISIBLE else View.GONE
            delete.visibility = if (canMutate) View.VISIBLE else View.GONE
            actions.visibility = if (canMutate) View.VISIBLE else View.GONE
            more.visibility = View.GONE
            removalStatus.visibility = if (removalActive) View.VISIBLE else View.GONE
            if (removalActive) {
                removalStatus.text = if (isOwner) {
                    itemView.context.getString(R.string.map_legacy_removal_owner)
                } else {
                    itemView.context.getString(R.string.map_legacy_removal_other)
                }
            }
            val isVisible = showMarkers && !hiddenMarkers.contains(marker.marker_id)
            visibilityToggle.setOnCheckedChangeListener(null)
            visibilityToggle.isChecked = isVisible
            visibilityToggle.isEnabled = showMarkers
            visibilityToggle.setOnCheckedChangeListener { _, checked ->
                onToggleMarkerVisibility(marker, checked)
            }
            itemView.setOnClickListener { onMarkerFocus(marker) }
            details.setOnClickListener { onMarkerDetail(marker) }
            edit.setOnClickListener { onMarkerEdit(marker) }
            delete.setOnClickListener { onMarkerDelete(marker) }
        }
    }

    private inner class AreaHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label = view.findViewById<TextView>(R.id.area_label)
        private val note = view.findViewById<TextView>(R.id.area_note)
        private val geometry = view.findViewById<TextView>(R.id.area_geometry)
        private val meta = view.findViewById<TextView>(R.id.area_meta)
        private val contribPreview = view.findViewById<TextView>(R.id.area_contrib_preview)
        private val removalStatus = view.findViewById<TextView>(R.id.area_removal_status)
        private val more = view.findViewById<TextView>(R.id.area_more)
        private val visibilityToggle = view.findViewById<Switch>(R.id.area_visibility)
        private val edit = view.findViewById<Button>(R.id.area_edit)
        private val delete = view.findViewById<Button>(R.id.area_delete)
        private val actions = view.findViewById<View>(R.id.area_actions)
        private val details = view.findViewById<Button>(R.id.area_details)

        fun bind(area: MapAreaEntity) {
            val actorDid = DeviceIdProvider.get(itemView.context)
            val kind = area.kind?.takeIf { it.isNotBlank() }
            val labelText = MapKinds.labelForKey(kind) ?: kind
            val baseLabel = if (labelText != null) {
                "A: $labelText"
            } else {
                "A"
            }
            label.text = baseLabel
            note.text = area.note?.takeIf { it.isNotBlank() }
                ?: itemView.context.getString(R.string.map_note_empty)
            val nowSeconds = System.currentTimeMillis() / 1000L
            val age = formatAge(itemView.context, nowSeconds - area.updated_t)
            val updatedLabel = itemView.context.getString(
                R.string.map_updated_author_format,
                age,
                mapOwnerAttribution(itemView.context, area.actor_did),
            )
            val centroid = centroidFromGeometry(area.geometry_json)
            geometry.text = if (centroid != null) {
                itemView.context.getString(
                    R.string.map_area_location_format,
                    formatCoord(centroid.first),
                    formatCoord(centroid.second)
                )
            } else {
                itemView.context.getString(
                    R.string.map_area_location_format,
                    "--",
                    "--"
                )
            }
            meta.text = updatedLabel
            // Contributions are legacy per-item vote UI. Dedicated Situation and
            // Coordination detail actions replace it; never expose it in rows.
            contribPreview.visibility = View.GONE

            val isOwner = area.actor_did == actorDid
            // Canonical UPD and DEL have the same owner/lifecycle/window gates.
            // Do not offer an Edit button that the writer must reject.
            val canMutate = isOwner && areaDeleteAllowedIds.contains(area.area_id)
            val removalActive = isRemovalRequested(area.removal_requested_t, area.removal_cancel_t)
            edit.text = itemView.context.getString(R.string.map_edit_area)
            edit.visibility = if (canMutate) View.VISIBLE else View.GONE
            delete.visibility = if (canMutate) View.VISIBLE else View.GONE
            actions.visibility = if (canMutate) View.VISIBLE else View.GONE
            more.visibility = View.GONE
            removalStatus.visibility = if (removalActive) View.VISIBLE else View.GONE
            if (removalActive) {
                removalStatus.text = if (isOwner) {
                    itemView.context.getString(R.string.map_legacy_removal_owner)
                } else {
                    itemView.context.getString(R.string.map_legacy_removal_other)
                }
            }
            val isVisible = showAreas && !hiddenAreas.contains(area.area_id)
            visibilityToggle.setOnCheckedChangeListener(null)
            visibilityToggle.isChecked = isVisible
            visibilityToggle.isEnabled = showAreas
            visibilityToggle.setOnCheckedChangeListener { _, checked ->
                onToggleAreaVisibility(area, checked)
            }
            itemView.setOnClickListener { onAreaFocus(area) }
            details.setOnClickListener { onAreaDetail(area) }
            edit.setOnClickListener { onAreaEdit(area) }
            delete.setOnClickListener { onAreaDelete(area) }
        }
    }

    private inner class SharedTrailsSummaryHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.shared_trails_summary_title)
        private val meta = view.findViewById<TextView>(R.id.shared_trails_summary_meta)
        private val open = view.findViewById<Button>(R.id.shared_trails_summary_open)

        fun bind(trails: List<LiveTrailEntity>) {
            val liveCount = trails.count { it.active == 1 }
            val archivedCount = trails.size - liveCount

            title.text = itemView.context.getString(
                R.string.map_live_trails_summary_title,
                trails.size
            )

            meta.text = when {
                trails.isEmpty() ->
                    itemView.context.getString(R.string.map_live_trails_summary_empty)

                liveCount == 0 ->
                    itemView.context.getString(
                        R.string.map_live_trails_summary_no_live,
                        archivedCount
                    )

                else ->
                    itemView.context.getString(
                        R.string.map_live_trails_summary_live,
                        liveCount,
                        archivedCount
                    )
            }

            open.setOnClickListener {
                onOpenSharedTrails()
            }

            itemView.setOnClickListener {
                onOpenSharedTrails()
            }
        }
    }

    private inner class LiveTrailHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.trail_label)
        private val meta = view.findViewById<TextView>(R.id.trail_meta)
        private val toggle = view.findViewById<Switch>(R.id.trail_toggle)
        private val deleteArchived = view.findViewById<android.widget.Button>(R.id.trail_delete_archived)

        fun bind(trail: LiveTrailEntity) {
            itemView.setOnClickListener(null)
            deleteArchived.text = itemView.context.getString(R.string.map_live_trail_delete)
            val ownerName = liveTrailOwners[trail.owner_did] ?: shortAuthor(trail.owner_did)
            val nowMs = System.currentTimeMillis()
            val reconnectedUntil = liveTrailReconnectedUntil[trail.trail_id]
            val status = when {
                trail.active != 1 && trail.end_t != null ->
                    itemView.context.getString(R.string.map_live_trail_status_archived)
                reconnectedUntil != null && reconnectedUntil > nowMs ->
                    itemView.context.getString(R.string.map_live_trail_status_reconnected)
                liveTrailStaleIds.contains(trail.trail_id) ->
                    itemView.context.getString(R.string.map_live_trail_status_stale)
                else -> itemView.context.getString(R.string.map_live_trail_status_live)
            }
            val start = java.util.Date(trail.start_t * 1000L)
            val endSeconds = trail.end_t ?: System.currentTimeMillis() / 1000L
            val end = java.util.Date(endSeconds * 1000L)
            val date = android.text.format.DateFormat.getMediumDateFormat(itemView.context).format(start)
            val timeStart = android.text.format.DateFormat.getTimeFormat(itemView.context).format(start)
            val timeEnd = android.text.format.DateFormat.getTimeFormat(itemView.context).format(end)
            val titleLabel = itemView.context.getString(R.string.map_live_trail_title, ownerName)
            title.text = titleLabel
            meta.text = itemView.context.getString(
                R.string.map_live_trail_meta_format,
                status,
                date,
                timeStart,
                timeEnd,
                formatKm(trail.dist_m),
                trail.point_count
            )
            toggle.visibility = View.VISIBLE
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = trail.trail_id == selectedTrailId
            toggle.setOnCheckedChangeListener { _, checked ->
                onToggleLiveTrail(trail, checked)
            }
            val isReceiver = trail.owner_did != currentActorDid
            if (trail.active != 1 && isReceiver) {
                deleteArchived.visibility = View.VISIBLE
                deleteArchived.setOnClickListener { onDeleteLiveTrail(trail) }
            } else {
                deleteArchived.visibility = View.GONE
            }
        }
    }


    private fun formatAge(context: android.content.Context, ageSeconds: Long): String {
        val d = formatDuration(ageSeconds)
        return if (ageSeconds < 0) {
            context.getString(R.string.map_age_in_format, d)
        } else {
            context.getString(R.string.map_age_ago_format, d)
        }
    }

    private fun formatDuration(seconds: Long): String {
        val absSeconds = seconds.absoluteValue
        val minutes = absSeconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "${days}d"
            hours > 0 -> "${hours}h"
            minutes > 0 -> "${minutes}m"
            else -> "${absSeconds}s"
        }
    }

    private fun formatCoord(value: Double): String {
        return String.format(java.util.Locale.US, "%.5f", value)
    }

    private fun formatKm(meters: Float): String {
        return String.format(java.util.Locale.US, "%.2f km", meters / 1000f)
    }

    private fun isRemovalRequested(requestedT: Long?, cancelT: Long?): Boolean {
        if (requestedT == null) return false
        return cancelT == null || cancelT < requestedT
    }

    private fun geometrySummary(raw: String): String {
        return try {
            val element = Json.parseToJsonElement(raw)
            val obj = element as? JsonObject
            val type = obj?.get("type")?.jsonPrimitive?.content
            if (!type.isNullOrBlank()) {
                type
            } else {
                raw.take(60)
            }
        } catch (_: Exception) {
            raw.take(60)
        }
    }

    sealed class MapListItem {
        data class Header(val title: String, val type: HeaderType, val isVisible: Boolean) : MapListItem()
        data class Empty(val message: String) : MapListItem()
        data class Marker(val entity: MapMarkerEntity) : MapListItem()
        data class Area(val entity: MapAreaEntity) : MapListItem()
        data class LiveTrail(val entity: LiveTrailEntity) : MapListItem()
        data class LiveTrailSummary(val trails: List<LiveTrailEntity>) : MapListItem()
    }

    private fun stripConfidencePrefix(note: String?): String? {
        if (note == null) return null
        val trimmed = note.trim()
        return when {
            trimmed.startsWith("[obs]") -> trimmed.removePrefix("[obs]").trim().ifBlank { null }
            trimmed.startsWith("[rep]") -> trimmed.removePrefix("[rep]").trim().ifBlank { null }
            else -> note
        }
    }

    fun updateContributionPreviewText(
        markerText: Map<String, String>,
        areaText: Map<String, String>
    ) {
        markerContributionPreview = markerContributionPreview + markerText
        areaContributionPreview = areaContributionPreview + areaText
    }

    fun getItemAt(position: Int): MapListItem? = items.getOrNull(position)

    private fun formatCount(value: Int): String {
        return when {
            value >= 1_000_000 -> "${value / 1_000_000}M"
            value >= 1_000 -> "${value / 1_000}k"
            else -> value.toString()
        }
    }

    private fun centroidFromGeometry(raw: String): Pair<Double, Double>? {
        return try {
            val element = Json.parseToJsonElement(raw)
            val obj = element as? JsonObject ?: return null
            val coords = obj["coordinates"] as? JsonArray ?: return null
            val ring = coords.firstOrNull() as? JsonArray ?: return null
            var sumLat = 0.0
            var sumLon = 0.0
            var count = 0
            ring.forEach { pt ->
                val pair = pt as? JsonArray ?: return@forEach
                val lon = pair.getOrNull(0)?.jsonPrimitive?.content?.toDoubleOrNull()
                val lat = pair.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull()
                if (lon != null && lat != null) {
                    sumLon += lon
                    sumLat += lat
                    count++
                }
            }
            if (count == 0) null else (sumLat / count) to (sumLon / count)
        } catch (_: Exception) {
            null
        }
    }

    private fun shortAuthor(actorDid: String): String {
        // Anonymous within group: show last 6 chars
        if (actorDid.length <= 6) return actorDid
        return actorDid.takeLast(6)
    }

    private fun mapOwnerAttribution(
        context: android.content.Context,
        ownerActorDid: String?,
    ): String = when (
        val displayName = MapOwnerDisplayNameResolver.resolve(ownerActorDid, currentActorDid, mapOwnerNames)
    ) {
        MapOwnerDisplayName.You -> context.getString(R.string.map_author_you)
        is MapOwnerDisplayName.Named -> context.getString(R.string.map_author_format, displayName.value)
        MapOwnerDisplayName.GroupMember -> context.getString(R.string.map_author_group_member)
    }

    companion object {
        private const val VIEW_HEADER = 1
        private const val VIEW_EMPTY = 2
        private const val VIEW_MARKER = 3
        private const val VIEW_AREA = 4
        private const val VIEW_LIVE_TRAIL = 5
        private const val VIEW_LIVE_TRAIL_SUMMARY = 6
    }

    enum class HeaderType {
        MARKERS,
        AREAS,
        LIVE_TRAILS,
    }
}
