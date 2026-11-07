package app.peermesh

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class SysMetadataSyncWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val activeGid = ActiveGroupManager.getActiveGroup(applicationContext)
        if (activeGid.isNullOrBlank()) {
            SysMetadataSyncWorkManager.cancel(applicationContext)
            return Result.success()
        }
        val nearbyCount = runCatching {
            app.peermesh.ble.BleMeshRuntime.get(applicationContext).nearbyDeviceCount()
        }.getOrDefault(0)
        if (nearbyCount <= 0) return Result.success()
        SysEventWriter.syncMetadataSnapshots(
            context = applicationContext,
            gid = activeGid,
            reason = "periodic",
        )
        return Result.success()
    }
}
