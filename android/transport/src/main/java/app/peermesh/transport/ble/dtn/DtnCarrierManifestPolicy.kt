package app.peermesh.transport.ble.dtn

object DtnCarrierManifestPolicy {
    const val DEFAULT_MAX_MANIFEST_BATCHES = 64
    const val DEFAULT_MAX_MANIFEST_BYTES = 256 * 1024L
    const val SHA256_HEX_LENGTH = 64

    fun acceptedBatchIds(
        packet: DtnControlPacket,
        nowSeconds: Long,
        localPeerId: String,
        maxBatches: Int = DEFAULT_MAX_MANIFEST_BATCHES,
        maxBytes: Long = DEFAULT_MAX_MANIFEST_BYTES,
    ): List<String> {
        val manifest = packet.manifest ?: return emptyList()
        if (packet.type != DtnControlTypes.MANIFEST_OFFER) return emptyList()
        if (manifest.senderPeerId != packet.peerId) return emptyList()
        if (!manifest.receiverPeerId.isNullOrBlank() && manifest.receiverPeerId != localPeerId) return emptyList()
        if (manifest.manifestId.isBlank() || manifest.gidHash.isBlank()) return emptyList()
        if (manifest.nonce.isBlank() || manifest.signature.isBlank()) return emptyList()
        if (manifest.expiresAt <= nowSeconds) return emptyList()
        if (manifest.maxBatches <= 0 || manifest.maxBatches > maxBatches) return emptyList()
        if (manifest.maxBytes <= 0 || manifest.maxBytes > maxBytes) return emptyList()
        if (manifest.batches.isEmpty() || manifest.batches.size > manifest.maxBatches) return emptyList()

        var totalBytes = 0L
        val seenBatchIds = LinkedHashSet<String>()
        val accepted = ArrayList<String>(manifest.batches.size)
        manifest.batches.forEach { batch ->
            if (batch.batchId.isBlank()) return@forEach
            if (!seenBatchIds.add(batch.batchId)) return@forEach
            if (!isValidSha256Hex(batch.payloadSha256)) return@forEach
            if (batch.payloadBytes <= 0) return@forEach
            if (batch.expiresAt <= nowSeconds || batch.expiresAt > manifest.expiresAt) return@forEach
            if (batch.priority < 0) return@forEach
            val nextBytes = totalBytes + batch.payloadBytes
            if (nextBytes > manifest.maxBytes) return@forEach
            totalBytes = nextBytes
            accepted += batch.batchId
        }
        return accepted
    }

    private fun isValidSha256Hex(value: String): Boolean {
        if (value.length != SHA256_HEX_LENGTH) return false
        return value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
