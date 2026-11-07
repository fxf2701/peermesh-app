package app.peermesh.data.dtn

import app.peermesh.data.db.dao.DtnPeerManifestKeyDao
import app.peermesh.transport.ble.dtn.DtnManifestKeyAlgorithms
import app.peermesh.transport.ble.dtn.DtnCarrierManifestKeyResolver

class RoomDtnCarrierManifestKeyResolver(
    private val keyDao: DtnPeerManifestKeyDao,
    private val algorithm: String = DtnManifestKeyAlgorithms.ECDSA_P256_SHA256,
) : DtnCarrierManifestKeyResolver {
    override fun keyForPeer(peerId: String): ByteArray? {
        return keyDao.find(peerId = peerId, algorithm = algorithm)?.key_bytes
    }

}
