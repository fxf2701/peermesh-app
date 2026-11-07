package app.peermesh.relay

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.peermesh.PeerMeshCapabilities

class RelaySyncResumeWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        if (!PeerMeshCapabilities.canUseRelaySync) {
            RelaySyncLifecycleCoordinator.onCapabilityUnavailable(applicationContext)
            return Result.success()
        }
        if (!RelaySyncSettings.isEnabled(applicationContext)) {
            RelaySyncLifecycleCoordinator.onUserDisabledRelay(applicationContext)
            return Result.success()
        }
        RelaySyncLifecycleCoordinator.onResumeWorkerTriggered(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "relay_sync_resume"

        fun enqueue(context: Context) {
            if (!PeerMeshCapabilities.canUseRelaySync) {
                RelaySyncLifecycleCoordinator.onCapabilityUnavailable(context)
                return
            }
            if (!RelaySyncSettings.isEnabled(context)) {
                RelaySyncLifecycleCoordinator.onUserDisabledRelay(context)
                return
            }
            val mode = RelaySyncSettings.load(context)
            if (mode == SyncMode.BATTERY_SAVER) {
                RelaySyncLifecycleCoordinator.onBackgroundModeRequested(context)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<RelaySyncResumeWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
