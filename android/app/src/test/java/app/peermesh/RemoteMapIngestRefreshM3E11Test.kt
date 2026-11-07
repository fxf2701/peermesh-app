package app.peermesh

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMapIngestRefreshM3E11Test {
    private val activity = File("src/main/java/app/peermesh/GroupMapActivity.kt").readText()

    @Test
    fun `ble and relay accepted map events share the post-commit notification boundary`() {
        val bleService = File("src/main/java/app/peermesh/ble/BleForegroundService.kt").readText()
        val relayRunner = File("src/main/java/app/peermesh/relay/RelaySyncRunner.kt").readText()
        val ingestor = File("../data/src/main/java/app/peermesh/data/ingest/EventIngestor.kt").readText()
        val remoteMapIngest = ingestor.substringAfter("private fun ingestRemoteMap(")
            .substringBefore("is RemoteMapIngestOutcome.ExactDuplicate")

        assertTrue(bleService.contains("EventIngestor("))
        assertTrue(relayRunner.contains("EventIngestor("))
        assertTrue(remoteMapIngest.contains("is RemoteMapIngestOutcome.Accepted"))
        assertTrue(remoteMapIngest.contains("IngestNotifier.notifyInserted(envelope.gid, eventId)"))
    }

    @Test
    fun `accepted remote map notification requests an immediate authoritative refresh`() {
        val listener = activity.substringAfter("ingestListener = object : IngestNotifier.Listener")
            .substringBefore("showLocationPromptIfNeeded()")

        assertTrue(listener.contains("if (!event.type.startsWith(\"MAP.\")"))
        assertTrue(listener.contains("requestRemoteMapRefresh(gid)"))
        assertFalse(listener.contains("Defer map updates to the next scheduled refresh"))
    }

    @Test
    fun `remote refresh is coalesced and retained while a creation draft is active`() {
        val request = activity.substringAfter("private fun requestRemoteMapRefresh(gid: String)")
            .substringBefore("private fun flushPendingRemoteMapRefresh()")

        assertTrue(request.contains("pendingRemoteMapRefreshGid = gid"))
        assertTrue(request.contains("if (isDrafting()) return"))
        assertTrue(request.contains("handler.postDelayed(refresh, REMOTE_MAP_REFRESH_DEBOUNCE_MS)"))
        assertTrue(request.contains("refreshMapUiNow(gid, resetTimer = true)"))
        assertTrue(activity.contains("resumeMapRefreshTimer(resetTimer = true)\n        flushPendingRemoteMapRefresh()"))
    }

    @Test
    fun `paused activity drops queued callbacks because resume performs a full load`() {
        val pause = activity.substringAfter("override fun onPause()")
            .substringBefore("override fun onDestroy()")

        assertTrue(pause.contains("remoteMapRefreshRunnable?.let(handler::removeCallbacks)"))
        assertTrue(pause.contains("pendingRemoteMapRefreshGid = null"))
        assertTrue(activity.substringAfter("override fun onResume()")
            .substringBefore("override fun onPause()")
            .contains("loadMapItems(gid, adapter)"))
    }
}
