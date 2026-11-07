package app.peermesh

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MapActivityLogLabelGroupTest {
    private val activity = File("src/main/java/app/peermesh/TestActivity.kt").readText()

    @Test
    fun `map sync recovery checkbox covers creation ingestion and dependency repair`() {
        assertTrue(activity.contains("id = \"map_sync_recovery\""))
        assertTrue(activity.contains("title = \"Map Events, Sync & Recovery\""))
        assertTrue(activity.contains("\"Local map event committed\""))
        assertTrue(activity.contains("\"Map event deferred\""))
        assertTrue(activity.contains("\"Map canonical repair requested\""))
        assertTrue(activity.contains("\"Map canonical repair pull finished\""))
        assertTrue(activity.contains("\"Relay map dependency repair\""))
        assertTrue(activity.contains("\"DTN dependency want queued\""))
        assertTrue(activity.contains("\"DTN dependency want recv\""))
        assertTrue(activity.contains("\"DTN dependency data served\""))
        assertTrue(activity.contains("\"DTN dependency data recv\""))
        assertTrue(activity.contains("\"Map transport enqueue failed\""))
    }
}
