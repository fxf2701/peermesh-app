package app.peermesh.relay

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.peermesh.data.debug.ActivityLog
import app.peermesh.PeerMeshCapabilities
import java.util.concurrent.TimeUnit

object RelaySyncWorkManager {
    private const val WORK_NAME = "relay_sync_periodic"
    private const val MAP_REPAIR_WORK_NAME = "relay_map_dependency_repair"

    fun enqueue(context: Context) {
        if (!PeerMeshCapabilities.canUseRelaySync) {
            cancel(context)
            return
        }
        if (!RelaySyncSettings.isEnabled(context)) {
            cancel(context)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RelaySyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(MAP_REPAIR_WORK_NAME)
    }

    fun requestMapDependencyRepair(context: Context, gid: String, rootEventId: String) {
        if (!PeerMeshCapabilities.canUseRelaySync || !RelaySyncSettings.isEnabled(context)) {
            ActivityLog.add(
                label = "Map canonical repair scheduling skipped",
                source = "relay",
                message = "gid=$gid root_event_id=$rootEventId reason=relay_unavailable",
                eventId = rootEventId,
                gid = gid,
            )
            return
        }
        if (RelaySyncService.isSyncRunning() &&
            RelaySyncCoordinator.get(context).requestImmediateRun()
        ) {
            ActivityLog.add(
                label = "Map canonical repair scheduled",
                source = "relay",
                message = "gid=$gid root_event_id=$rootEventId path=running_scheduler",
                eventId = rootEventId,
                gid = gid,
            )
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RelaySyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MAP_REPAIR_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        ActivityLog.add(
            label = "Map canonical repair scheduled",
            source = "relay",
            message = "gid=$gid root_event_id=$rootEventId path=one_time_worker",
            eventId = rootEventId,
            gid = gid,
        )
    }
}
