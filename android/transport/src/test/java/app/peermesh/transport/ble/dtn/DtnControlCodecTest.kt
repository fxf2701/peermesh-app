package app.peermesh.transport.ble.dtn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtnControlCodecTest {
    @Test
    fun decodeAcceptsKnownPacket() {
        val packet = DtnControlPacket(
            type = DtnControlTypes.INV_HAVE,
            peerId = "peer-a",
            gid = "group-1",
            sessionId = "session-1",
            mode = DtnInventoryModes.ID_LIST,
            inventoryTier = DtnInventoryTier.HIGH.wireValue,
            ids = listOf("event-1", "event-2"),
            ts = 1_000,
        )

        val decoded = DtnControlCodec.decode(DtnControlCodec.encode(packet))

        assertEquals(packet, decoded)
    }

    @Test
    fun decodeRejectsUnknownType() {
        val json = """{"v":1,"type":"unknown","peer_id":"peer-a","ts":1000}"""

        assertNull(DtnControlCodec.decode(json))
    }

    @Test
    fun decodeRejectsEventEnvelopeJson() {
        val json = """{"v":1,"gid":"g","mid":"m","ts":1000,"enc":{"ct":"cipher"}}"""

        assertNull(DtnControlCodec.decode(json))
    }

    @Test
    fun encodeDecodeRoundTripsManifestOffer() {
        val packet = DtnControlPacket(
            type = DtnControlTypes.MANIFEST_OFFER,
            peerId = "peer-a",
            gid = "group-1",
            sessionId = "session-1",
            manifest = manifest(),
            ts = 1_000,
        )

        val decoded = DtnControlCodec.decode(DtnControlCodec.encode(packet))

        assertEquals(packet, decoded)
    }

    @Test
    fun encodeDecodeRoundTripsManifestAccept() {
        val packet = DtnControlPacket(
            type = DtnControlTypes.MANIFEST_ACCEPT,
            peerId = "peer-b",
            gid = "group-1",
            sessionId = "session-1",
            manifestId = "manifest-1",
            acceptedBatchIds = listOf("batch-1", "batch-2"),
            ts = 1_001,
        )

        val decoded = DtnControlCodec.decode(DtnControlCodec.encode(packet))

        assertEquals(packet, decoded)
    }

    @Test
    fun encodeDecodeRoundTripsManifestKey() {
        val packet = DtnControlPacket(
            type = DtnControlTypes.MANIFEST_KEY,
            peerId = "peer-a",
            gid = "group-1",
            sessionId = "session-1",
            manifestKey = DtnPeerManifestKey(
                peerId = "peer-a",
                algorithm = DtnManifestKeyAlgorithms.ECDSA_P256_SHA256,
                publicKey = "public-key",
            ),
            ts = 1_002,
        )

        val decoded = DtnControlCodec.decode(DtnControlCodec.encode(packet))

        assertEquals(packet, decoded)
    }

    @Test
    fun encodeDecodeRoundTripsCarrierData() {
        val packet = DtnControlPacket(
            type = DtnControlTypes.DATA,
            peerId = "peer-a",
            gid = "group-1",
            sessionId = "session-1",
            manifestId = "manifest-1",
            carrierData = listOf(
                DtnCarrierDataItem(
                    batchId = "batch-1",
                    payload = "payload",
                    payloadSha256 = "a".repeat(64),
                    payloadBytes = 128,
                )
            ),
            ts = 1_003,
        )

        val decoded = DtnControlCodec.decode(DtnControlCodec.encode(packet))

        assertEquals(packet, decoded)
    }

    private fun manifest(): DtnCarrierManifest {
        return DtnCarrierManifest(
            manifestId = "manifest-1",
            senderPeerId = "peer-a",
            receiverPeerId = "peer-b",
            gidHash = "gid-hash",
            maxBatches = 2,
            maxBytes = 2_048,
            expiresAt = 2_000,
            nonce = "nonce-1",
            batches = listOf(
                DtnCarrierManifestBatch(
                    batchId = "batch-1",
                    payloadSha256 = "a".repeat(64),
                    payloadBytes = 512,
                    kind = "app_batch",
                    expiresAt = 1_900,
                    priority = 3,
                ),
            ),
            signature = "sig",
        )
    }
}
