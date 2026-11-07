package app.peermesh.moderation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ModerationSyncWorkManager {
    private const val PERIODIC_WORK_NAME = "moderation_sync_periodic"
    private const val ONE_TIME_WORK_NAME = "moderation_sync_once"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<ModerationSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        val oneTime = OneTimeWorkRequestBuilder<ModerationSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_TIME_WORK_NAME, androidx.work.ExistingWorkPolicy.REPLACE, oneTime)
    }
}
