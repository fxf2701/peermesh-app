package app.peermesh.map.presentation.timeline

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One-shot background execution for timeline reads; cancellation never implies rollback. */
class AndroidMapItemTimelineExecution(
    private val executor: ExecutorService = SHARED_EXECUTOR,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : MapItemTimelineExecution {
    override fun execute(block: () -> Unit): MapItemTimelineTask {
        val future: Future<*> = executor.submit(block)
        return MapItemTimelineTask { future.cancel(false) }
    }

    override fun deliver(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        val SHARED_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "map-item-timeline").apply { isDaemon = true }
        }
    }
}
