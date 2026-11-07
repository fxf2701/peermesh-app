package app.peermesh.data.dtn

import android.os.Looper
import app.peermesh.data.db.dao.OpaqueDtnCapsuleDao
import app.peermesh.data.db.entity.OpaqueDtnCapsuleEntity
import app.peermesh.data.db.entity.OpaqueDtnCapsuleDeliveryState
import app.peermesh.transport.ble.OpaqueRelayCapsule
import app.peermesh.transport.ble.OpaqueDeliveredMarkProbe
import app.peermesh.transport.ble.OpaqueDeliveredMarkReasonCounts
import app.peermesh.transport.ble.OpaqueRelayStore
import java.security.MessageDigest

class RoomOpaqueRelayStore(
    private val capsuleDao: OpaqueDtnCapsuleDao,
    private val maxStoreBytes: Long = DEFAULT_MAX_STORE_BYTES,
    private val retryDelaySeconds: Long = DEFAULT_RETRY_DELAY_SECONDS,
    private val maxDeliveryAttempts: Int = DEFAULT_MAX_DELIVERY_ATTEMPTS,
) : OpaqueRelayStore {
    override fun upsert(capsule: OpaqueRelayCapsule): Boolean {
        cleanupExpired(capsule.receivedAtSeconds)
        if (capsule.payload.isEmpty() || capsule.payloadBytes <= 0L) return false
        if (sha256Hex(capsule.payload) != capsule.payloadSha256) return false
        capsuleDao.upsert(capsule.toEntity())
        pruneToCap()
        return true
    }

    override fun cleanupExpired(nowSeconds: Long): Int {
        val deletedExpired = capsuleDao.deleteExpired(nowSeconds)
        val deletedLegacyNullExpiry = capsuleDao.deleteLegacyNullExpiry(nowSeconds - DEFAULT_CAPSULE_TTL_SECONDS)
        val deletedLegacyForwarded = capsuleDao.deleteLegacyForwardedWithoutDelivery(
            nowSeconds - LEGACY_FORWARDED_WITHOUT_DELIVERY_RETENTION_SECONDS,
        )
        val deletedOrphans = capsuleDao.deleteOrphanDeliveries()
        return deletedExpired + deletedLegacyNullExpiry + deletedLegacyForwarded + deletedOrphans
    }

    override fun listForwardableForDevice(
        deviceId: String,
        nowSeconds: Long,
        limit: Int,
    ): List<OpaqueRelayCapsule> {
        cleanupExpired(nowSeconds)
        if (deviceId.isBlank()) return emptyList()
        return capsuleDao
            .listForwardableForDevice(
                deviceId = deviceId,
                nowSeconds = nowSeconds,
                limit = limit,
            )
            .map { it.toCapsule() }
    }

    override fun markDeliveryQueued(capsuleId: String, deviceId: String, nowSeconds: Long) {
        if (capsuleId.isBlank() || deviceId.isBlank()) return
        capsuleDao.markDeliveryQueued(capsuleId, deviceId, nowSeconds)
    }

    override fun markDeliveryDeliveredByBatchId(
        batchId: String,
        deviceId: String,
        nowSeconds: Long,
    ): Int {
        if (batchId.isBlank() || deviceId.isBlank()) return 0
        return capsuleDao.markBatchDeliveredForDevice(batchId, deviceId, nowSeconds)
    }

    override fun markDeliveryDeliveredForCapsules(
        capsuleIds: Collection<String>,
        deviceId: String,
        nowSeconds: Long,
    ): Int {
        if (capsuleIds.isEmpty() || deviceId.isBlank()) return 0
        return capsuleDao.markCapsulesDeliveredForDevice(capsuleIds.toList(), deviceId, nowSeconds)
    }

    override fun probeMarkDeliveryDeliveredForCapsules(
        capsuleIds: Collection<String>,
        deviceId: String,
        nowSeconds: Long,
    ): OpaqueDeliveredMarkProbe? {
        if (capsuleIds.isEmpty() || deviceId.isBlank()) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        var marked = 0
        var missingCapsule = 0
        var alreadyDelivered = 0
        var failedFinal = 0
        var eligibleButZero = 0
        val distinctCapsules = capsuleIds.distinct()
        distinctCapsules.forEach { capsuleId ->
            val parentExists = capsuleDao.find(capsuleId) != null
            val existing = capsuleDao.findDelivery(capsuleId, deviceId)
            val updated = capsuleDao.markDeliveryDelivered(capsuleId, deviceId, nowSeconds)
            if (updated > 0) {
                marked += updated
            } else when {
                !parentExists -> missingCapsule += 1
                existing?.state == OpaqueDtnCapsuleDeliveryState.DELIVERED -> alreadyDelivered += 1
                existing?.state == OpaqueDtnCapsuleDeliveryState.FAILED_FINAL -> failedFinal += 1
                else -> eligibleButZero += 1
            }
        }
        return OpaqueDeliveredMarkProbe(
            capsuleCount = distinctCapsules.size,
            marked = marked,
            missingCapsule = missingCapsule,
            alreadyDelivered = alreadyDelivered,
            failedFinal = failedFinal,
            eligibleButZero = eligibleButZero,
        )
    }

    override fun probeMarkDeliveryDeliveredByBatchId(
        batchId: String,
        deviceId: String,
        nowSeconds: Long,
    ): OpaqueDeliveredMarkProbe? {
        if (batchId.isBlank() || deviceId.isBlank()) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val capsuleIds = capsuleDao.listCapsuleIdsByBatchId(batchId)
        return if (capsuleIds.isEmpty()) {
            OpaqueDeliveredMarkProbe(
                capsuleCount = 0,
                marked = 0,
                missingCapsule = 1,
                alreadyDelivered = 0,
                failedFinal = 0,
                eligibleButZero = 0,
            )
        } else {
            probeMarkDeliveryDeliveredForCapsules(capsuleIds, deviceId, nowSeconds)
        }
    }

    override fun markDeliveryFailedByBatchId(
        batchId: String,
        deviceId: String,
        nowSeconds: Long,
        reason: String,
    ): Int {
        if (batchId.isBlank() || deviceId.isBlank()) return 0
        return capsuleDao.markBatchFailedForDevice(
            batchId = batchId,
            deviceId = deviceId,
            nowSeconds = nowSeconds,
            reason = reason,
            maxAttempts = maxDeliveryAttempts,
            retryDelaySeconds = retryDelaySeconds,
        )
    }

    override fun markDeliveryFailedForCapsules(
        capsuleIds: Collection<String>,
        deviceId: String,
        nowSeconds: Long,
        reason: String,
    ): Int {
        if (capsuleIds.isEmpty() || deviceId.isBlank()) return 0
        return capsuleDao.markCapsulesFailedForDevice(
            capsuleIds = capsuleIds.toList(),
            deviceId = deviceId,
            nowSeconds = nowSeconds,
            reason = reason,
            maxAttempts = maxDeliveryAttempts,
            retryDelaySeconds = retryDelaySeconds,
        )
    }

    override fun listDeliveredCapsuleIdsByBatchId(
        batchId: String,
        deviceId: String,
    ): List<String> {
        if (batchId.isBlank() || deviceId.isBlank()) return emptyList()
        return capsuleDao.listDeliveredCapsuleIdsByBatchId(batchId, deviceId).distinct()
    }

    override fun listDeliveredCapsuleIdsForCapsules(
        capsuleIds: Collection<String>,
        deviceId: String,
    ): List<String> {
        if (capsuleIds.isEmpty() || deviceId.isBlank()) return emptyList()
        return capsuleDao.listDeliveredCapsuleIdsForDevice(capsuleIds.distinct(), deviceId).distinct()
    }

    override fun deleteCapsules(capsuleIds: Collection<String>): Int {
        if (capsuleIds.isEmpty()) return 0
        return capsuleDao.deleteCapsuleIds(capsuleIds.distinct())
    }

    override fun describeDelivery(capsuleId: String, deviceId: String): String? {
        if (capsuleId.isBlank() || deviceId.isBlank()) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return "main_thread_skipped"
        val row = capsuleDao.findDelivery(capsuleId, deviceId)
        return row?.let {
            "state=${it.state},attempt=${it.attempt_count},last_error=${it.last_error ?: "none"},last_attempt_at=${it.last_attempt_at ?: -1},next_retry_at=${it.next_retry_at ?: -1},delivered_at=${it.delivered_at ?: -1}"
        } ?: "missing"
    }

    override fun describeBatchDelivery(batchId: String, deviceId: String): String? {
        if (batchId.isBlank() || deviceId.isBlank()) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return "main_thread_skipped"
        val capsuleIds = capsuleDao.listCapsuleIdsByBatchId(batchId)
        if (capsuleIds.isEmpty()) return "capsules=0 rows=0"
        var rows = 0
        val stateCounts = linkedMapOf(
            "IN_FLIGHT" to 0,
            "DELIVERED" to 0,
            "FAILED_RETRYABLE" to 0,
            "FAILED_FINAL" to 0,
            "missing" to 0,
        )
        capsuleIds.forEach { capsuleId ->
            val row = capsuleDao.findDelivery(capsuleId, deviceId)
            if (row == null) {
                stateCounts["missing"] = (stateCounts["missing"] ?: 0) + 1
            } else {
                rows += 1
                stateCounts[row.state] = (stateCounts[row.state] ?: 0) + 1
            }
        }
        val summary = stateCounts.entries.joinToString(",") { "${it.key}=${it.value}" }
        return "capsules=${capsuleIds.size} rows=$rows $summary"
    }

    override fun describeDeliveredMarkReasonsForCapsules(
        capsuleIds: Collection<String>,
        deviceId: String,
    ): OpaqueDeliveredMarkReasonCounts? {
        if (capsuleIds.isEmpty() || deviceId.isBlank()) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val reasonCounts = linkedMapOf(
            "eligible" to 0,
            "missing_capsule" to 0,
            "already_delivered" to 0,
            "failed_final" to 0,
        )
        capsuleIds.distinct().forEach { capsuleId ->
            val reason = when {
                capsuleDao.find(capsuleId) == null -> "missing_capsule"
                capsuleDao.findDelivery(capsuleId, deviceId)?.state == OpaqueDtnCapsuleDeliveryState.DELIVERED -> "already_delivered"
                capsuleDao.findDelivery(capsuleId, deviceId)?.state == OpaqueDtnCapsuleDeliveryState.FAILED_FINAL -> "failed_final"
                else -> "eligible"
            }
            reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
        }
        return OpaqueDeliveredMarkReasonCounts(
            capsuleCount = capsuleIds.distinct().size,
            eligible = reasonCounts["eligible"] ?: 0,
            missingCapsule = reasonCounts["missing_capsule"] ?: 0,
            alreadyDelivered = reasonCounts["already_delivered"] ?: 0,
            failedFinal = reasonCounts["failed_final"] ?: 0,
        )
    }

    override fun describeDeliveredMarkReasonsForBatch(
        batchId: String,
        deviceId: String,
    ): OpaqueDeliveredMarkReasonCounts? {
        if (batchId.isBlank() || deviceId.isBlank()) return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val capsuleIds = capsuleDao.listCapsuleIdsByBatchId(batchId)
        if (capsuleIds.isEmpty()) {
            return OpaqueDeliveredMarkReasonCounts(
                capsuleCount = 0,
                eligible = 0,
                missingCapsule = 1,
                alreadyDelivered = 0,
                failedFinal = 0,
            )
        }
        return describeDeliveredMarkReasonsForCapsules(capsuleIds, deviceId)
    }

    override fun deleteByBatchId(batchId: String): Int {
        if (batchId.isBlank()) return 0
        return capsuleDao.deleteByBatchId(batchId)
    }

    private fun pruneToCap() {
        var totalBytes = capsuleDao.totalPayloadBytes()
        if (totalBytes <= maxStoreBytes) return
        capsuleDao.listPruneCandidates(limit = MAX_PRUNE_BATCH).forEach { capsuleId ->
            if (totalBytes <= maxStoreBytes) return
            val capsule = capsuleDao.find(capsuleId) ?: return@forEach
            if (capsuleDao.delete(capsuleId) > 0) {
                totalBytes -= capsule.payload_bytes
            }
        }
    }

    private fun OpaqueRelayCapsule.toEntity(): OpaqueDtnCapsuleEntity {
        return OpaqueDtnCapsuleEntity(
            capsule_id = capsuleId,
            gid = gid,
            gid_hash = sha256Hex(gid.toByteArray(Charsets.UTF_8)),
            event_id = eventId,
            batch_id = batchId,
            kind = kind,
            payload = payload,
            payload_sha256 = payloadSha256,
            payload_bytes = payloadBytes,
            created_t = createdAtSeconds,
            received_at = receivedAtSeconds,
            expires_at = boundedExpiresAt(this),
            hop_count = hopCount,
            max_hops = maxHops,
            priority = priority,
            source_peer_id = sourcePeerId,
            last_forwarded_at = null,
            manifest_id = null,
        )
    }

    private fun OpaqueDtnCapsuleEntity.toCapsule(): OpaqueRelayCapsule {
        return OpaqueRelayCapsule(
            capsuleId = capsule_id,
            gid = gid,
            eventId = event_id,
            batchId = batch_id,
            kind = kind,
            payload = payload,
            payloadSha256 = payload_sha256,
            payloadBytes = payload_bytes,
            createdAtSeconds = created_t,
            receivedAtSeconds = received_at,
            expiresAtSeconds = expires_at,
            hopCount = hop_count,
            maxHops = max_hops,
            priority = priority,
            sourcePeerId = source_peer_id,
        )
    }

    private fun boundedExpiresAt(capsule: OpaqueRelayCapsule): Long {
        val hardExpiresAt = capsule.receivedAtSeconds + DEFAULT_CAPSULE_TTL_SECONDS
        return capsule.expiresAtSeconds?.let { minOf(it, hardExpiresAt) } ?: hardExpiresAt
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val DEFAULT_MAX_STORE_BYTES = 2L * 1024L * 1024L
        private const val DEFAULT_CAPSULE_TTL_SECONDS = 24L * 60L * 60L
        private const val DEFAULT_RETRY_DELAY_SECONDS = 60L
        private const val DEFAULT_MAX_DELIVERY_ATTEMPTS = 6
        private const val LEGACY_FORWARDED_WITHOUT_DELIVERY_RETENTION_SECONDS = 60L * 60L
        private const val MAX_PRUNE_BATCH = 64
    }
}
