package app.peermesh.map.presentation.map.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmdroidAreaLabelPlacementTest {
    private val source = File(
        "src/main/java/app/peermesh/map/presentation/map/runtime/OsmdroidMapRuntimeRenderer.kt",
    ).readText()

    @Test fun `only labels with an area top inset anchor below their centroid icon`() {
        assertTrue(source.contains("else if (it.topInsetPx > 0)"))
        assertTrue(source.contains("else if (label.topInsetPx > 0)"))
        assertTrue(source.contains("setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)"))
        assertTrue(source.contains("without affecting point-marker labels"))
    }
}
