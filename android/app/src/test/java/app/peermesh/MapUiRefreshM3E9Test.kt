package app.peermesh

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapUiRefreshM3E9Test {
    private val activity = File("src/main/java/app/peermesh/GroupMapActivity.kt").readText()

    @Test
    fun `owner edit refreshes the map only after its submission result is accepted`() {
        assertTrue(activity.contains("submitMarkerAsync(gid, existing, lat, lon, radius, kind, label, note, confidence, ttlSeconds) { ok ->"))
        assertTrue(activity.contains("submitAreaAsync(gid, existing, geometry, kind, label, note, confidence, ttlSeconds) { ok ->"))
        assertTrue(activity.contains("if (ok) {\n                        if (isSwitch)"))
        assertTrue(activity.contains("finishFlow(dialog, onFinished)\n                        refreshMapUi(gid)"))
    }

    @Test
    fun `confirmed delete refreshes the map only after the local write result`() {
        assertTrue(activity.contains("submitMarkerDelete(gid, marker) { deleted ->"))
        assertTrue(activity.contains("submitAreaDelete(gid, area) { deleted ->"))
        assertTrue(activity.contains("if (deleted && isCurrentMapScope(gid, deleteScopeGeneration))"))
        assertTrue(activity.contains("val deleted = storeAndBroadcast(\n                gid,\n                withCanonicalRoot("))
        assertTrue(activity.contains("runOnUiThread { onResult(deleted) }"))
        assertFalse(activity.contains("submitMarkerDelete(gid, marker)\n                refreshMapUi(gid)"))
        assertFalse(activity.contains("submitAreaDelete(gid, area)\n                refreshMapUi(gid)"))
    }
}
