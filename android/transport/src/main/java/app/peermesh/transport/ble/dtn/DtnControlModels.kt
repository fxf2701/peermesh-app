package app.peermesh.transport.ble.dtn

object DtnControlTypes {
    const val HELLO = "hello"
    const val INV_HAVE = "inv_have"
    const val INV_WANT = "inv_want"
    const val DATA = "data"
    const val ACK = "ack"
    const val MANIFEST_KEY = "manifest_key"
    const val MANIFEST_OFFER = "manifest_offer"
    const val MANIFEST_ACCEPT = "manifest_accept"
}

object DtnManifestKeyAlgorithms {
    const val HMAC_SHA256 = "HMAC_SHA256"
    const val ECDSA_P256_SHA256 = "ECDSA_P256_SHA256"
}

object DtnInventoryModes {
    const val ID_LIST = "id_list"
}

data class DtnControlPacket(
    val v: Int = 1,
    val type: String,
    val peerId: String,
    val gid: String? = null,
    val sessionId: String? = null,
    val mode: String? = null,
    val inventoryTier: String? = null,
    val ids: List<String> = emptyList(),
    val data: List<DtnDataItem> = emptyList(),
    val carrierData: List<DtnCarrierDataItem> = emptyList(),
    val manifestKey: DtnPeerManifestKey? = null,
    val manifest: DtnCarrierManifest? = null,
    val manifestId: String? = null,
    val acceptedBatchIds: List<String> = emptyList(),
    val cloudStatus: DtnCloudStatus? = null,
    val ts: Long,
)

data class DtnCloudStatus(
    val state: String,
    val lastSuccessAtSeconds: Long? = null,
)

object DtnCloudStates {
    const val OK = "ok"
    const val STALE = "stale"
    const val DISABLED = "disabled"
}

data class DtnDataItem(
    val eventId: String,
    val envelope: String,
)

data class DtnCarrierDataItem(
    val batchId: String,
    val payload: String,
    val payloadSha256: String,
    val payloadBytes: Long,
    val hopCount: Int = 0,
    val maxHops: Int = DtnCarrierDataDefaults.DEFAULT_MAX_HOPS,
)

object DtnCarrierDataDefaults {
    const val DEFAULT_MAX_HOPS = 20
}

data class DtnPeerManifestKey(
    val peerId: String,
    val algorithm: String,
    val publicKey: String,
)

data class DtnCarrierManifest(
    val manifestId: String,
    val senderPeerId: String,
    val receiverPeerId: String?,
    val gidHash: String,
    val maxBatches: Int,
    val maxBytes: Long,
    val expiresAt: Long,
    val nonce: String,
    val batches: List<DtnCarrierManifestBatch>,
    val signature: String,
)

data class DtnCarrierManifestBatch(
    val batchId: String,
    val payloadSha256: String,
    val payloadBytes: Long,
    val kind: String,
    val expiresAt: Long,
    val priority: Int,
)
