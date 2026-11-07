package app.peermesh.transport.adapter

data class MeshDispatchJob(
    val batchId: String,
    val deviceId: String,
    val payload: ByteArray,
)

interface MeshDispatchJobSource {
    fun claimNextDispatchJob(deviceId: String, allowAck: Boolean = true): MeshDispatchJob?

    fun failDispatchJob(
        batchId: String,
        deviceId: String,
        reason: String,
        retryDelayMs: Long = 30_000L,
    )
}
