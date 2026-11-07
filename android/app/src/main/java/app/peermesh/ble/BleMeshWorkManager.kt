package app.peermesh.ble

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BleMeshWorkManager {
    private const val WORK_NAME = "ble_mesh_safety"
    private const val TAG = "BleMeshWorkManager"

    fun enqueue(context: Context) {
        android.util.Log.i(TAG, "Enqueue BLE mesh safety work")
        val request = PeriodicWorkRequestBuilder<BleMeshWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        android.util.Log.i(TAG, "Cancel BLE mesh safety work")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
