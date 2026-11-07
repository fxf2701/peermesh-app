package app.peermesh

import androidx.work.Worker
import androidx.work.WorkerParameters
import app.peermesh.nearby.NearbyRoomRepository

class NearbyRoomCleanupWorker(
    context: android.content.Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        NearbyRoomRepository(applicationContext).cleanupTerminalRoomData()
        return Result.success()
    }

    companion object {
        const val TAG = "NearbyRoomCleanupWorker"
    }
}
