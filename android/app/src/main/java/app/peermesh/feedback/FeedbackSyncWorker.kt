package app.peermesh.feedback

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class FeedbackSyncWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val summary = FeedbackRepository(applicationContext).syncPendingFeedback()
        return when {
            !summary.endpointConfigured -> Result.success()
            summary.retryPending -> Result.retry()
            else -> Result.success()
        }
    }
}
