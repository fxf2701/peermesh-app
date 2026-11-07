package app.peermesh.moderation

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class ModerationSyncWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val summary = ModerationRepository(applicationContext).syncPendingReports()
        return when {
            !summary.endpointConfigured -> Result.success()
            summary.retryPending -> Result.retry()
            else -> Result.success()
        }
    }
}
