package app.peermesh.data.dtn

import app.peermesh.core.encoding.Base64Url
import app.peermesh.data.db.dao.DtnCarrierManifestDao
import app.peermesh.data.db.entity.DtnCarrierManifestBatchEntity
import app.peermesh.data.db.entity.DtnCarrierManifestEntity
import app.peermesh.transport.ble.dtn.DtnCarrierManifest
import app.peermesh.transport.ble.dtn.DtnCarrierManifestStore

class RoomDtnCarrierManifestStore(
    private val manifestDao: DtnCarrierManifestDao,
) : DtnCarrierManifestStore {
    override fun saveAcceptedManifest(
        peerId: String,
        sessionId: String?,
        manifest: DtnCarrierManifest,
        acceptedBatchIds: List<String>,
        nowSeconds: Long,
    ) {
        val accepted = acceptedBatchIds.toSet()
        val batches = manifest.batches
            .filter { it.batchId in accepted }
            .map { batch ->
                DtnCarrierManifestBatchEntity(
                    manifest_id = manifest.manifestId,
                    batch_id = batch.batchId,
                    payload_sha256 = batch.payloadSha256,
                    payload_bytes = batch.payloadBytes,
                    kind = batch.kind,
                    expires_at = batch.expiresAt,
                    priority = batch.priority,
                )
            }
        if (batches.isEmpty()) return

        manifestDao.upsertGraph(
            manifest = DtnCarrierManifestEntity(
                manifest_id = manifest.manifestId,
                session_id = sessionId ?: "",
                sender_peer_id = manifest.senderPeerId.ifBlank { peerId },
                receiver_peer_id = manifest.receiverPeerId,
                gid_hash = manifest.gidHash,
                max_batches = manifest.maxBatches,
                max_bytes = manifest.maxBytes,
                expires_at = manifest.expiresAt,
                nonce = manifest.nonce,
                signature = decodeSignature(manifest.signature),
                created_at = nowSeconds,
                accepted_at = nowSeconds,
            ),
            batches = batches,
        )
    }

    private fun decodeSignature(signature: String): ByteArray {
        return runCatching { Base64Url.decode(signature) }
            .getOrElse { signature.toByteArray(Charsets.UTF_8) }
    }
}
