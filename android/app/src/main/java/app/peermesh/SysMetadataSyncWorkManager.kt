package app.peermesh

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SysMetadataSyncWorkManager {
    private const val WORK_NAME = "sys_metadata_sync"

    fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<SysMetadataSyncWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
