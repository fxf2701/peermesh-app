package app.peermesh.map.presentation.map.runtime

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import app.peermesh.map.presentation.map.resources.AndroidMapRenderModel
import app.peermesh.map.presentation.map.resources.ResolvedMapOutlinePattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

@RunWith(AndroidJUnit4::class)
class OsmdroidMapRuntimeRendererM3D2Test {
    @Test fun repeatedRenderReusesArtifactsPreservesHolesAndLeavesUnrelatedOverlay() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(::runRendererScenario)
    }

    private fun runRendererScenario() {
        val map = MapView(ApplicationProvider.getApplicationContext())
        val unrelated = object : Overlay() {}
        map.overlays.add(unrelated)
        val registry = MapRuntimeRenderRegistry()
        val clicks = mutableListOf<MapItemIdentity>()
        val renderer = OsmdroidMapRuntimeRenderer(map, registry) { clicks += it }
        val markerId = MapItemIdentity("g", MapItemType.MARKER, "same")
        val areaId = MapItemIdentity("g", MapItemType.AREA, "same")
        val outer = listOf(
            MapSceneCoordinate(0.0, 0.0),
            MapSceneCoordinate(0.0, 2.0),
            MapSceneCoordinate(2.0, 2.0),
            MapSceneCoordinate(0.0, 0.0),
        )
        val hole = listOf(
            MapSceneCoordinate(0.5, 0.5),
            MapSceneCoordinate(0.5, 1.0),
            MapSceneCoordinate(1.0, 0.5),
            MapSceneCoordinate(0.5, 0.5),
        )
        val models = listOf(
            AndroidMapRenderModel.Marker(
                markerId,
                10.0,
                20.0,
                ColorDrawable(Color.RED),
                255,
                1f,
                null,
                null,
                "Situation",
            ),
            AndroidMapRenderModel.Area(
                areaId,
                MapSceneAreaGeometry.MultiPolygon(listOf(listOf(outer, hole), listOf(outer))),
                Color.argb(40, 255, 0, 0),
                Color.RED,
                2f,
                ResolvedMapOutlinePattern(null),
                1f,
                null,
                "Situation area",
            ),
        )

        renderer.render(models)
        val marker = registry.get(markerId) as MapRuntimeArtifactBundle.MarkerArtifacts
        val area = registry.get(areaId) as MapRuntimeArtifactBundle.AreaArtifacts
        assertEquals(2, registry.size())
        assertEquals(2, area.polygons.size)
        assertEquals(1, area.polygons.first().holes.size)
        assertEquals(markerId, marker.marker.relatedObject)
        assertEquals(areaId, area.polygons.first().relatedObject)
        val overlayCount = map.overlays.size

        renderer.render(models)
        assertSame(marker.marker, (registry.get(markerId) as MapRuntimeArtifactBundle.MarkerArtifacts).marker)
        assertSame(area.polygons.first(), (registry.get(areaId) as MapRuntimeArtifactBundle.AreaArtifacts).polygons.first())
        assertEquals(overlayCount, map.overlays.size)
        assertTrue(map.overlays.contains(unrelated))

        renderer.render(listOf(models.first()))
        assertEquals(1, registry.size())
        assertTrue(registry.get(areaId) == null)
        assertTrue(map.overlays.contains(unrelated))

        renderer.clear()
        renderer.clear()
        assertEquals(0, registry.size())
        assertEquals(listOf(unrelated), map.overlays)
        renderer.close()
        renderer.close()
        renderer.render(models)
        assertEquals(0, registry.size())
    }
}
