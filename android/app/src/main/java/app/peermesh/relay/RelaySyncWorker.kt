package app.peermesh.relay

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.peermesh.PeerMeshCapabilities

class RelaySyncWorker(
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
        if (RelaySyncService.isSyncRunning()) {
            return Result.success()
        }
        val report = RelaySyncRunner(applicationContext).runOnce()
        return if (report.success) Result.success() else Result.retry()
    }
}
