package app.peermesh.ble

import android.content.Context
import app.peermesh.OutboundBatchDispatcher
import app.peermesh.transport.adapter.MeshDispatchJob
import app.peermesh.transport.adapter.MeshDispatchJobSource

class DispatcherJobSource(
    private val appContext: Context,
) : MeshDispatchJobSource {

    override fun claimNextDispatchJob(deviceId: String, allowAck: Boolean): MeshDispatchJob? {
        val job = OutboundBatchDispatcher.claimNextDispatchJob(
            context = appContext,
            deviceId = deviceId,
            allowAck = allowAck,
        ) ?: return null

        return MeshDispatchJob(
            batchId = job.batchId,
            deviceId = job.deviceId,
            payload = job.payload,
        )
    }

    override fun failDispatchJob(
        batchId: String,
        deviceId: String,
        reason: String,
        retryDelayMs: Long,
    ) {
        OutboundBatchDispatcher.failDispatchJob(
            context = appContext,
            batchId = batchId,
            deviceId = deviceId,
            reason = reason,
            retryDelayMs = retryDelayMs,
        )
        OutboundBatchDispatcher.kick(
            context = appContext,
            reason = "dispatch_job_source_failed",
        )
    }
}
