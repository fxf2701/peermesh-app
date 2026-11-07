package app.peermesh.data.dtn

import app.peermesh.core.encoding.Base64Url
import app.peermesh.data.db.dao.OutboundBatchDao
import app.peermesh.data.db.dao.OpaqueDtnCapsuleDao
import app.peermesh.data.transport.DtnTransportPolicy
import app.peermesh.transport.ble.dtn.DtnCarrierDataItem
import app.peermesh.transport.ble.dtn.DtnCarrierDataSource
import java.security.MessageDigest

class RoomDtnCarrierDataSource(
    private val outboundBatchDao: OutboundBatchDao,
    private val opaqueCapsuleDao: OpaqueDtnCapsuleDao? = null,
) : DtnCarrierDataSource {
    override fun loadCarrierData(
        gid: String,
        manifestId: String,
        batchIds: Collection<String>,
        nowSeconds: Long,
        maxBytes: Long,
    ): List<DtnCarrierDataItem> {
        if (manifestId.isBlank() || batchIds.isEmpty()) return emptyList()
        var totalBytes = 0L
        val result = ArrayList<DtnCarrierDataItem>(batchIds.size)
        val requested = batchIds.distinct()
        val emitted = LinkedHashSet<String>()
        requested.forEach { batchId ->
            val row = outboundBatchDao.findBatch(batchId) ?: return@forEach
            if (row.gid != gid) return@forEach
            val payloadBytes = row.payload.size.toLong()
            if (payloadBytes <= 0) return@forEach
            val nextBytes = totalBytes + payloadBytes
            if (nextBytes > maxBytes) return@forEach
            result += DtnCarrierDataItem(
                batchId = row.batch_id,
                payload = Base64Url.encode(row.payload),
                payloadSha256 = sha256Hex(row.payload),
                payloadBytes = payloadBytes,
                hopCount = 0,
                maxHops = DtnTransportPolicy.DEFAULT_MAX_HOPS,
            )
            totalBytes = nextBytes
            emitted += batchId
        }
        val remaining = requested.filterNot { it in emitted }
        if (remaining.isNotEmpty()) {
            val capsulesById = opaqueCapsuleDao
                ?.loadByIds(gid = gid, capsuleIds = remaining, nowSeconds = nowSeconds)
                ?.associateBy { it.capsule_id }
                .orEmpty()
            remaining.forEach { capsuleId ->
                val capsule = capsulesById[capsuleId] ?: return@forEach
                val payloadBytes = capsule.payload.size.toLong()
                if (payloadBytes <= 0) return@forEach
                val nextBytes = totalBytes + payloadBytes
                if (nextBytes > maxBytes) return@forEach
                val payloadSha256 = sha256Hex(capsule.payload)
                if (payloadSha256 != capsule.payload_sha256) return@forEach
                val nextHopCount = capsule.hop_count + 1
                if (nextHopCount >= capsule.max_hops) return@forEach
                result += DtnCarrierDataItem(
                    batchId = capsule.capsule_id,
                    payload = Base64Url.encode(capsule.payload),
                    payloadSha256 = payloadSha256,
                    payloadBytes = payloadBytes,
                    hopCount = nextHopCount,
                    maxHops = capsule.max_hops,
                )
                totalBytes = nextBytes
            }
        }
        return result
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
