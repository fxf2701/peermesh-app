package app.peermesh.ble

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class BleMeshWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        if (!app.peermesh.PeerMeshPrefs.isBleMeshUserEnabled(applicationContext)) {
            android.util.Log.i(TAG, "Skip mesh safety: manually disabled by user")
            return Result.success()
        }
        if (!BleForegroundService.canStartMesh(applicationContext)) {
            android.util.Log.i(TAG, "Skip mesh safety: cannot start carrier mesh")
            return Result.success()
        }
        if (BleForegroundService.isMeshRunning()) {
            android.util.Log.i(TAG, "Skip mesh safety: mesh already running")
            return Result.success()
        }
        android.util.Log.i(TAG, "Mesh safety: attempting start")
        BleForegroundService.startMesh(
            applicationContext,
            caller = "BleMeshWorker.doWork",
            reason = "periodic_carrier_safety_start",
        )
        return Result.success()
    }

    companion object {
        private const val TAG = "BleMeshWorker"
    }
}
