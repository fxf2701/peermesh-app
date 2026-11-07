package app.peermesh.map.presentation.detail

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One-shot background execution; cancellation suppresses queued/obsolete work without interrupting writes. */
class AndroidMapItemDetailShellExecution(
    private val executor: ExecutorService = SHARED_EXECUTOR,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : MapItemDetailShellExecution {
    override fun execute(block: () -> Unit): MapItemDetailShellTask {
        val future: Future<*> = executor.submit(block)
        return MapItemDetailShellTask { future.cancel(false) }
    }

    override fun deliver(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        val SHARED_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "map-item-detail").apply { isDaemon = true }
        }
    }
}
