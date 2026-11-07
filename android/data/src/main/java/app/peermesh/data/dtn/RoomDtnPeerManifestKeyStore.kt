package app.peermesh.data.dtn

import app.peermesh.core.encoding.Base64Url
import app.peermesh.data.db.dao.DtnPeerManifestKeyDao
import app.peermesh.data.db.entity.DtnPeerManifestKeyEntity
import app.peermesh.transport.ble.dtn.DtnPeerManifestKeyStore

class RoomDtnPeerManifestKeyStore(
    private val keyDao: DtnPeerManifestKeyDao,
) : DtnPeerManifestKeyStore {
    override fun savePeerKey(peerId: String, algorithm: String, publicKey: String, nowSeconds: Long): Boolean {
        val keyBytes = runCatching { Base64Url.decode(publicKey) }.getOrNull() ?: return false
        if (peerId.isBlank() || algorithm.isBlank() || keyBytes.isEmpty()) return false
        val existing = keyDao.find(peerId = peerId, algorithm = algorithm)
        keyDao.upsert(
            DtnPeerManifestKeyEntity(
                peer_id = peerId,
                algorithm = algorithm,
                key_bytes = keyBytes,
                first_seen_at = existing?.first_seen_at ?: nowSeconds,
                updated_at = nowSeconds,
            )
        )
        return true
    }
}
