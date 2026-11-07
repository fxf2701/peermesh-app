package app.peermesh

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object NearbyRoomCleanupWorkManager {
    private const val WORK_NAME = "NearbyRoomCleanup"

    fun enqueue(context: Context) {
        val request = PeriodicWorkRequestBuilder<NearbyRoomCleanupWorker>(30, TimeUnit.MINUTES)
            .addTag(NearbyRoomCleanupWorker.TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
