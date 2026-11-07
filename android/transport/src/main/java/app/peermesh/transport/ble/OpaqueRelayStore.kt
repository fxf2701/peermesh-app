package app.peermesh.transport.ble

data class OpaqueRelayCapsule(
    val capsuleId: String,
    val gid: String,
    val eventId: String?,
    val batchId: String?,
    val kind: String,
    val payload: ByteArray,
    val payloadSha256: String,
    val payloadBytes: Long,
    val createdAtSeconds: Long,
    val receivedAtSeconds: Long,
    val expiresAtSeconds: Long?,
    val hopCount: Int,
    val maxHops: Int,
    val priority: Int,
    val sourcePeerId: String?,
)

object OpaqueRelayCapsuleKind {
    const val ENVELOPE = "envelope"
    const val APP_BATCH = "app_batch"
}

data class OpaqueDeliveredMarkReasonCounts(
    val capsuleCount: Int,
    val eligible: Int,
    val missingCapsule: Int,
    val alreadyDelivered: Int,
    val failedFinal: Int,
)

data class OpaqueDeliveredMarkProbe(
    val capsuleCount: Int,
    val marked: Int,
    val missingCapsule: Int,
    val alreadyDelivered: Int,
    val failedFinal: Int,
    val eligibleButZero: Int,
)

interface OpaqueRelayStore {
    fun upsert(capsule: OpaqueRelayCapsule): Boolean
    fun cleanupExpired(nowSeconds: Long): Int
    fun listForwardableForDevice(deviceId: String, nowSeconds: Long, limit: Int): List<OpaqueRelayCapsule>
    fun markDeliveryQueued(capsuleId: String, deviceId: String, nowSeconds: Long)
    fun markDeliveryDeliveredByBatchId(batchId: String, deviceId: String, nowSeconds: Long): Int
    fun markDeliveryDeliveredForCapsules(capsuleIds: Collection<String>, deviceId: String, nowSeconds: Long): Int
    fun markDeliveryFailedByBatchId(batchId: String, deviceId: String, nowSeconds: Long, reason: String): Int
    fun markDeliveryFailedForCapsules(capsuleIds: Collection<String>, deviceId: String, nowSeconds: Long, reason: String): Int
    fun listDeliveredCapsuleIdsByBatchId(batchId: String, deviceId: String): List<String> = emptyList()
    fun listDeliveredCapsuleIdsForCapsules(capsuleIds: Collection<String>, deviceId: String): List<String> = emptyList()
    fun deleteCapsules(capsuleIds: Collection<String>): Int = 0
    fun describeDelivery(capsuleId: String, deviceId: String): String? = null
    fun describeBatchDelivery(batchId: String, deviceId: String): String? = null
    fun describeDeliveredMarkReasonsForCapsules(
        capsuleIds: Collection<String>,
        deviceId: String,
    ): OpaqueDeliveredMarkReasonCounts? = null
    fun describeDeliveredMarkReasonsForBatch(batchId: String, deviceId: String): OpaqueDeliveredMarkReasonCounts? = null
    fun probeMarkDeliveryDeliveredForCapsules(
        capsuleIds: Collection<String>,
        deviceId: String,
        nowSeconds: Long,
    ): OpaqueDeliveredMarkProbe? = null
    fun probeMarkDeliveryDeliveredByBatchId(
        batchId: String,
        deviceId: String,
        nowSeconds: Long,
    ): OpaqueDeliveredMarkProbe? = null

    @Deprecated("Use per-device delivery state instead.")
    fun listForwardable(nowSeconds: Long, limit: Int): List<OpaqueRelayCapsule> = emptyList()

    @Deprecated("Use markDeliveryDeliveredByBatchId instead.")
    fun markForwarded(capsuleId: String, nowSeconds: Long) = Unit

    @Deprecated("Use markDeliveryDeliveredByBatchId instead.")
    fun markForwardedByBatchId(batchId: String, nowSeconds: Long): Int = 0

    fun deleteByBatchId(batchId: String): Int
}
