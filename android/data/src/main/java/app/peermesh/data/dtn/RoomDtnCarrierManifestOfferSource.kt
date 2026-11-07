package app.peermesh.data.dtn

import app.peermesh.data.db.dao.OutboundBatchDao
import app.peermesh.data.db.dao.OpaqueDtnCapsuleDao
import app.peermesh.data.db.entity.OutboundBatchStatus
import app.peermesh.data.db.entity.OpaqueDtnCapsuleKind
import app.peermesh.data.transport.DtnTransportPolicy
import app.peermesh.transport.ble.dtn.DtnCarrierManifestOfferSource
import app.peermesh.transport.ble.dtn.DtnCarrierOfferBatch
import java.security.MessageDigest

class RoomDtnCarrierManifestOfferSource(
    private val outboundBatchDao: OutboundBatchDao,
    private val opaqueCapsuleDao: OpaqueDtnCapsuleDao? = null,
    private val ttlSeconds: Long = DtnTransportPolicy.DEFAULT_TTL_SECONDS.toLong(),
) : DtnCarrierManifestOfferSource {
    override fun loadOfferBatches(
        gid: String,
        nowSeconds: Long,
        maxBatches: Int,
        maxBytes: Long,
    ): List<DtnCarrierOfferBatch> {
        val rows = outboundBatchDao.selectDtnCarrierCandidates(
            gid = gid,
            statuses = listOf(OutboundBatchStatus.QUEUED, OutboundBatchStatus.FAILED_RETRYABLE),
            limit = maxBatches * 2,
        )
        var totalBytes = 0L
        val seenBatchIds = LinkedHashSet<String>()
        val result = ArrayList<DtnCarrierOfferBatch>(maxBatches)
        rows.forEach { row ->
            if (result.size >= maxBatches) return@forEach
            if (!seenBatchIds.add(row.batch_id)) return@forEach
            val payloadBytes = row.payload.size.toLong()
            if (payloadBytes <= 0) return@forEach
            val nextBytes = totalBytes + payloadBytes
            if (nextBytes > maxBytes) return@forEach
            val expiresAt = row.created_at + ttlSeconds
            if (expiresAt <= nowSeconds) return@forEach
            result += DtnCarrierOfferBatch(
                batchId = row.batch_id,
                payloadSha256 = sha256Hex(row.payload),
                payloadBytes = payloadBytes,
                kind = OpaqueDtnCapsuleKind.APP_BATCH,
                createdAtSeconds = row.created_at,
                expiresAtSeconds = expiresAt,
                priority = row.priority,
            )
            totalBytes = nextBytes
        }
        opaqueCapsuleDao
            ?.listInventory(gid = gid, nowSeconds = nowSeconds, limit = maxBatches * 2)
            ?.forEach { capsule ->
                if (result.size >= maxBatches) return@forEach
                if (!seenBatchIds.add(capsule.capsule_id)) return@forEach
                val payloadBytes = capsule.payload_bytes
                if (payloadBytes <= 0) return@forEach
                val nextBytes = totalBytes + payloadBytes
                if (nextBytes > maxBytes) return@forEach
                val expiresAt = capsule.expires_at ?: (nowSeconds + ttlSeconds)
                if (expiresAt <= nowSeconds) return@forEach
                result += DtnCarrierOfferBatch(
                    batchId = capsule.capsule_id,
                    payloadSha256 = capsule.payload_sha256,
                    payloadBytes = payloadBytes,
                    kind = capsule.kind,
                    createdAtSeconds = capsule.created_t,
                    expiresAtSeconds = expiresAt,
                    priority = capsule.priority,
                )
                totalBytes = nextBytes
            }
        return result
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
