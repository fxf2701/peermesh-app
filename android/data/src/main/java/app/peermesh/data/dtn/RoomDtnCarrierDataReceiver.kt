package app.peermesh.data.dtn

import app.peermesh.core.encoding.Base64Url
import app.peermesh.data.db.dao.DtnCarrierManifestDao
import app.peermesh.data.db.dao.OpaqueDtnCapsuleDao
import app.peermesh.data.db.entity.OpaqueDtnCapsuleEntity
import app.peermesh.transport.ble.dtn.DtnCarrierDataItem
import app.peermesh.transport.ble.dtn.DtnCarrierDataReceiver
import java.security.MessageDigest

class RoomDtnCarrierDataReceiver(
    private val manifestDao: DtnCarrierManifestDao,
    private val capsuleDao: OpaqueDtnCapsuleDao,
    private val maxStoreBytes: Long = DEFAULT_MAX_STORE_BYTES,
    private val maxCapsulesPerPeerWindow: Int = DEFAULT_MAX_CAPSULES_PER_PEER_WINDOW,
    private val peerWindowSeconds: Long = DEFAULT_PEER_WINDOW_SECONDS,
) : DtnCarrierDataReceiver {
    override fun accept(
        gid: String,
        peerId: String,
        manifestId: String,
        item: DtnCarrierDataItem,
        nowSeconds: Long,
    ): Boolean {
        capsuleDao.deleteExpired(nowSeconds)
        if (item.hopCount < 0 || item.maxHops <= 0 || item.hopCount >= item.maxHops) return false
        if (capsuleDao.countFromPeerSince(peerId, nowSeconds - peerWindowSeconds) >= maxCapsulesPerPeerWindow) {
            return false
        }
        val manifest = manifestDao.find(manifestId) ?: return false
        if (manifest.sender_peer_id != peerId) return false
        if (manifest.accepted_at == null || manifest.expires_at <= nowSeconds) return false
        if (manifest.gid_hash != sha256Hex(gid.toByteArray(Charsets.UTF_8))) return false

        val batch = manifestDao.findBatch(manifestId, item.batchId) ?: return false
        if (batch.expires_at <= nowSeconds) return false
        if (item.payloadBytes != batch.payload_bytes) return false
        if (item.payloadSha256 != batch.payload_sha256) return false

        val payload = runCatching { Base64Url.decode(item.payload) }.getOrNull() ?: return false
        if (payload.size.toLong() != batch.payload_bytes) return false
        val payloadSha256 = sha256Hex(payload)
        if (payloadSha256 != batch.payload_sha256) return false

        capsuleDao.upsert(
            OpaqueDtnCapsuleEntity(
                capsule_id = capsuleId(manifestId, batch.batch_id, payloadSha256),
                gid = gid,
                gid_hash = manifest.gid_hash,
                event_id = null,
                batch_id = batch.batch_id,
                kind = batch.kind,
                payload = payload,
                payload_sha256 = payloadSha256,
                payload_bytes = payload.size.toLong(),
                created_t = manifest.created_at,
                received_at = nowSeconds,
                expires_at = batch.expires_at,
                hop_count = item.hopCount,
                max_hops = item.maxHops,
                priority = batch.priority,
                source_peer_id = peerId,
                last_forwarded_at = null,
                manifest_id = manifestId,
            )
        )
        pruneToCap()
        return true
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

    private fun capsuleId(manifestId: String, batchId: String, payloadSha256: String): String {
        return "cap_${sha256Hex("$manifestId|$batchId|$payloadSha256".toByteArray(Charsets.UTF_8)).take(40)}"
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val DEFAULT_MAX_STORE_BYTES = 2L * 1024L * 1024L
        private const val DEFAULT_MAX_CAPSULES_PER_PEER_WINDOW = 64
        private const val DEFAULT_PEER_WINDOW_SECONDS = 10L * 60L
        private const val MAX_PRUNE_BATCH = 64
    }
}
