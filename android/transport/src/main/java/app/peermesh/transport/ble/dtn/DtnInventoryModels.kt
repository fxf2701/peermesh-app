package app.peermesh.transport.ble.dtn

data class DtnInventoryItem(
    val gid: String,
    val eventId: String,
    val type: String,
    val createdAtSeconds: Long,
    val insertedAtSeconds: Long,
    val priority: Int = DtnForwardPolicy.priorityForEventType(type),
)

interface DtnInventoryStore {
    fun listInventory(gid: String, limit: Int): List<DtnInventoryItem>
    fun listInventoryAfter(
        gid: String,
        lastInsertedAt: Long,
        lastEventId: String,
        limit: Int,
    ): List<DtnInventoryItem> {
        return listInventory(gid, limit)
    }

    fun existingEventIds(gid: String, eventIds: Collection<String>): Set<String>
    fun loadData(gid: String, eventIds: Collection<String>, limit: Int): List<DtnDataItem>

    /**
     * Roots which are durably required to materialize locally but are absent from the normal
     * inventory. They are deliberately independent of a peer's advertised window: a peer with
     * durable history may serve the root even when it has not advertised it in this session.
     */
    fun listDependencyRepairWants(gid: String, limit: Int): List<String> = emptyList()
}

fun interface DtnDataReceiver {
    fun accept(gid: String, item: DtnDataItem): Boolean
}

data class DtnSyncCursor(
    val lastInsertedAt: Long,
    val lastEventId: String,
)

interface DtnSyncStateStore {
    fun markPeerSeen(peerId: String, gid: String, sessionId: String?, nowSeconds: Long)
    fun loadCursor(peerId: String, gid: String): DtnSyncCursor?
    fun saveCursor(peerId: String, gid: String, cursor: DtnSyncCursor, nowSeconds: Long)
    fun markSyncSuccess(peerId: String, gid: String, nowSeconds: Long)
    fun markSyncFailure(peerId: String, gid: String, backoffUntilSeconds: Long?)
}

fun interface DtnLocalCloudStatusProvider {
    fun currentStatus(nowSeconds: Long): DtnCloudStatus?
}

fun interface DtnPeerHelloObserver {
    fun onPeerHello(
        peerId: String,
        gid: String,
        sessionId: String?,
        cloudStatus: DtnCloudStatus?,
        seenAtSeconds: Long,
    )
}

fun interface DtnCarrierManifestStore {
    fun saveAcceptedManifest(
        peerId: String,
        sessionId: String?,
        manifest: DtnCarrierManifest,
        acceptedBatchIds: List<String>,
        nowSeconds: Long,
    )
}

fun interface DtnCarrierManifestVerifier {
    fun verify(packet: DtnControlPacket, manifest: DtnCarrierManifest): Boolean
}

fun interface DtnLocalManifestKeyProvider {
    fun currentKey(): DtnPeerManifestKey?
}

fun interface DtnPeerManifestKeyStore {
    fun savePeerKey(peerId: String, algorithm: String, publicKey: String, nowSeconds: Long): Boolean
}

data class DtnCarrierOfferBatch(
    val batchId: String,
    val payloadSha256: String,
    val payloadBytes: Long,
    val kind: String,
    val createdAtSeconds: Long,
    val expiresAtSeconds: Long,
    val priority: Int,
)

interface DtnCarrierManifestOfferSource {
    fun loadOfferBatches(
        gid: String,
        nowSeconds: Long,
        maxBatches: Int = DtnCarrierManifestPolicy.DEFAULT_MAX_MANIFEST_BATCHES,
        maxBytes: Long = DtnCarrierManifestPolicy.DEFAULT_MAX_MANIFEST_BYTES,
    ): List<DtnCarrierOfferBatch>
}

fun interface DtnCarrierManifestSigner {
    fun sign(packet: DtnControlPacket, manifest: DtnCarrierManifest): String
}

interface DtnCarrierDataSource {
    fun loadCarrierData(
        gid: String,
        manifestId: String,
        batchIds: Collection<String>,
        nowSeconds: Long,
        maxBytes: Long = DtnCarrierManifestPolicy.DEFAULT_MAX_MANIFEST_BYTES,
    ): List<DtnCarrierDataItem>
}

fun interface DtnCarrierDataReceiver {
    fun accept(gid: String, peerId: String, manifestId: String, item: DtnCarrierDataItem, nowSeconds: Long): Boolean
}
