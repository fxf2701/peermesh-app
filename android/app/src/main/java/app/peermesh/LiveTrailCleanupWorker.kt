package app.peermesh

import androidx.work.Worker
import androidx.work.WorkerParameters

class LiveTrailCleanupWorker(
    context: android.content.Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        if (!PeerMeshCapabilities.canUseSharedLiveTrail) {
            LiveTrailCleanupWorkManager.cancel(applicationContext)
            return Result.success()
        }
        LiveTrailCleanupManager.runNowBlocking(applicationContext)
        return Result.success()
    }

    companion object {
        const val TAG = "LiveTrailCleanupWorker"
    }
}
