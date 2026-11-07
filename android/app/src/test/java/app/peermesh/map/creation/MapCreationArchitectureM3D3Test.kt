package app.peermesh.map.creation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCreationArchitectureM3D3Test {
    @Test fun `activity creation path has no canonical add construction`() {
        val source = source("app/peermesh/GroupMapActivity.kt")
        assertFalse(source.contains("EventFactory.mapMarkerAdd("))
        assertFalse(source.contains("EventFactory.mapAreaAdd("))
        assertTrue(source.contains("mapCreationController.submit { startedSubmissionId ->"))
        assertTrue(source.contains("refreshMapUiNow("))
    }

    @Test fun `new creation flow has no broad throwable catch or runCatching`() {
        val root = File("src/main/java/app/peermesh/map")
        val source = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("creation") }
            .joinToString("\n") { it.readText() }
        assertFalse(source.contains("catch (_: Throwable)"))
        assertFalse(source.contains("runCatching"))
    }

    private fun source(relative: String): String = File("src/main/java", relative).readText()
}
