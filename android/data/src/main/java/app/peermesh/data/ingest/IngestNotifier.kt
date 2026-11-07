package app.peermesh.data.ingest

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import android.util.Log
import app.peermesh.data.debug.ActivityLog

object IngestNotifier {
    interface Listener {
        fun onEventInserted(gid: String, eventId: String)

        fun onCanonicalMapRepairRequested(gid: String, rootEventId: String) = Unit
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val notifierExecutor = Executors.newSingleThreadExecutor(object : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread {
            return Thread(runnable, "IngestNotifier").apply { isDaemon = true }
        }
    })

    fun add(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun remove(listener: Listener) {
        listeners.remove(listener)
    }

    fun notifyInserted(gid: String, eventId: String) {
        if (listeners.isEmpty()) return
        notifierExecutor.execute {
            val count = listeners.size
            Log.i(TAG, "Ingest notify gid=$gid eventId=$eventId listeners=$count")
            ActivityLog.add(
                label = "Ingest notify",
                source = "ingest",
                message = "listeners=$count",
                eventId = eventId,
                gid = gid,
            )
            listeners.forEach { it.onEventInserted(gid, eventId) }
        }
    }

    fun notifyCanonicalMapRepairRequested(gid: String, rootEventId: String) {
        if (gid.isBlank() || rootEventId.isBlank() || listeners.isEmpty()) return
        notifierExecutor.execute {
            val count = listeners.size
            Log.i(TAG, "Canonical map repair notify gid=$gid rootEventId=$rootEventId listeners=$count")
            ActivityLog.add(
                label = "Map canonical repair requested",
                source = "ingest",
                message = "listeners=$count root_event_id=$rootEventId",
                eventId = rootEventId,
                gid = gid,
            )
            listeners.forEach { it.onCanonicalMapRepairRequested(gid, rootEventId) }
        }
    }

    private const val TAG = "IngestNotifier"
}
