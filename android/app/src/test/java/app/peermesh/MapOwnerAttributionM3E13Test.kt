package app.peermesh

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOwnerAttributionM3E13Test {
    private val adapter = File("src/main/java/app/peermesh/MapListAdapter.kt").readText()
    private val markerLayout = File("src/main/res/layout/item_map_marker.xml").readText()
    private val areaLayout = File("src/main/res/layout/item_map_area.xml").readText()

    @Test
    fun `marker and area cards share the owner attribution without ttl`() {
        assertTrue(adapter.contains("mapOwnerAttribution(itemView.context, marker.actor_did)"))
        assertTrue(adapter.contains("mapOwnerAttribution(itemView.context, area.actor_did)"))
        assertTrue(adapter.contains("R.string.map_updated_author_format"))
        assertFalse(adapter.contains("formatTtl("))
        assertFalse(adapter.contains("map_updated_ttl_author_format"))
    }

    @Test
    fun `long owner names are safely ellipsized in either card`() {
        assertTrue(markerLayout.contains("android:id=\"@+id/marker_meta\"") && markerLayout.contains("android:ellipsize=\"end\""))
        assertTrue(areaLayout.contains("android:id=\"@+id/area_meta\"") && areaLayout.contains("android:ellipsize=\"end\""))
    }
}
