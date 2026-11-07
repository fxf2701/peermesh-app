package app.peermesh

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object LiveTrailCleanupWorkManager {
    private const val WORK_NAME = "LiveTrailCleanup"

    fun enqueue(context: Context) {
        if (!PeerMeshCapabilities.canUseSharedLiveTrail) {
            cancel(context)
            return
        }
        val request = PeriodicWorkRequestBuilder<LiveTrailCleanupWorker>(6, TimeUnit.HOURS)
            .addTag(LiveTrailCleanupWorker.TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
