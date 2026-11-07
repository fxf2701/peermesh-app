package app.peermesh

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLegacyCleanupM3D5CTest {
    private val activity = source("java/app/peermesh/GroupMapActivity.kt")
    private val adapter = source("java/app/peermesh/MapListAdapter.kt")

    @Test
    fun `normal area form hides technical geometry and presents a drawn-area summary`() {
        val layout = source("res/layout/dialog_map_area.xml")
        val geometryInput = layout.substringAfter("""android:id="@+id/area_geometry"""")
            .substringBefore("/>")

        assertTrue(geometryInput.contains("""android:visibility="gone""""))
        assertTrue(layout.contains("""android:id="@+id/area_geometry_summary""""))
        assertTrue(activity.contains("geometrySummary.text = areaGeometrySummary"))
        assertTrue(activity.contains("map_area_geometry_polygon_summary"))
        assertTrue(activity.contains("map_area_geometry_multipolygon_summary"))
        assertFalse(activity.contains("existing.geometry_json.take(80)"))
    }

    @Test
    fun `owner edit reads persisted category and never infers it from kind`() {
        assertTrue(activity.contains(".mapItemDomainStateDao()"))
        assertTrue(activity.contains(".find(gid, \"marker\", marker.marker_id)"))
        assertTrue(activity.contains(".find(gid, \"area\", area.area_id)"))
        assertTrue(activity.contains("MapItemCategory::fromWireValue"))
        assertTrue(activity.contains("MapKinds.markerCreationOptions(groupProfile, it)"))
        assertTrue(activity.contains("MapKinds.areaCreationOptions(groupProfile, it)"))
        assertTrue(activity.contains("R.string.map_category_unavailable"))
        assertFalse(activity.contains("val markerKinds = MapKinds.kindOptions(groupProfile)"))
        assertFalse(activity.contains("val areaKinds = MapKinds.areaKindOptions(groupProfile)"))
    }

    @Test
    fun `existing edit preserves legacy confidence and hides legacy contribution controls`() {
        assertTrue(activity.contains("val confidence = if (existing != null)"))
        assertTrue(activity.contains("existing.confidence"))
        assertTrue(activity.contains("if (existing != null) {\n            confidenceGroup.visibility = View.GONE"))
        assertTrue(activity.contains("contribTitle.visibility = View.GONE"))
        assertTrue(activity.contains("contribMore.visibility = View.GONE"))
        assertTrue(activity.contains("contribList.removeAllViews()"))
    }

    @Test
    fun `map rows expose canonical owner edit and delete without vote badges or removal request links`() {
        assertTrue(activity.contains("onMarkerDelete = { marker -> if (marker.gid == currentGid) confirmDeleteMarker"))
        assertTrue(activity.contains("onAreaDelete = { area -> if (area.gid == currentGid) confirmDeleteArea"))
        assertTrue(adapter.contains("label.text = baseLabel"))
        assertTrue(adapter.contains("val canMutate = isOwner && markerDeleteAllowedIds.contains(marker.marker_id)"))
        assertTrue(adapter.contains("val canMutate = isOwner && areaDeleteAllowedIds.contains(area.area_id)"))
        assertTrue(adapter.contains("edit.visibility = if (canMutate) View.VISIBLE else View.GONE"))
        assertTrue(adapter.contains("delete.visibility = if (canMutate) View.VISIBLE else View.GONE"))
        assertFalse(adapter.contains("R.string.map_removal_request"))
        assertFalse(adapter.contains("label.text = \"\$baseLabel \${voteBadges"))
    }

    @Test
    fun `confirmed deletion refreshes only after the local delete write completes`() {
        assertTrue(activity.contains("val deleteScopeGeneration = mapGroupScopeGeneration"))
        assertTrue(activity.contains("submitMarkerDelete(gid, marker) { deleted ->"))
        assertTrue(activity.contains("submitAreaDelete(gid, area) { deleted ->"))
        assertTrue(activity.contains("if (deleted && isCurrentMapScope(gid, deleteScopeGeneration))"))
        assertTrue(activity.contains("runOnUiThread { onResult(deleted) }"))
        assertFalse(activity.contains("submitMarkerDelete(gid, marker)\n                refreshMapUi(gid)"))
        assertFalse(activity.contains("submitAreaDelete(gid, area)\n                refreshMapUi(gid)"))
    }

    private fun source(relative: String): String = File("src/main", relative).readText()
}
