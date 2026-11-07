package app.peermesh.transport.ble.dtn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class DtnCarrierManifestCryptoTest {
    @Test
    fun signedManifestVerifiesWithSenderKey() {
        val key = "remote-manifest-key".toByteArray(Charsets.UTF_8)
        val packet = packetWithManifest(signature = "")
        val unsigned = packet.manifest!!
        val signature = DtnCarrierManifestCrypto.sign(packet, unsigned, key)
        val signedManifest = unsigned.copy(signature = signature)
        val signedPacket = packet.copy(manifest = signedManifest)

        val verifier = HmacDtnCarrierManifestVerifier(
            DtnCarrierManifestKeyResolver { peerId ->
                if (peerId == "remote") key else null
            }
        )

        assertTrue(verifier.verify(signedPacket, signedManifest))
    }

    @Test
    fun signedManifestRejectsTamperedPayloadHash() {
        val key = "remote-manifest-key".toByteArray(Charsets.UTF_8)
        val packet = packetWithManifest(signature = "")
        val unsigned = packet.manifest!!
        val signature = DtnCarrierManifestCrypto.sign(packet, unsigned, key)
        val tamperedManifest = unsigned.copy(
            batches = listOf(unsigned.batches.single().copy(payloadSha256 = "b".repeat(64))),
            signature = signature,
        )
        val tamperedPacket = packet.copy(manifest = tamperedManifest)

        assertFalse(DtnCarrierManifestCrypto.verify(tamperedPacket, tamperedManifest, key))
    }

    @Test
    fun signedManifestRejectsWrongKey() {
        val key = "remote-manifest-key".toByteArray(Charsets.UTF_8)
        val packet = packetWithManifest(signature = "")
        val unsigned = packet.manifest!!
        val signature = DtnCarrierManifestCrypto.sign(packet, unsigned, key)
        val signedManifest = unsigned.copy(signature = signature)
        val signedPacket = packet.copy(manifest = signedManifest)

        assertFalse(
            DtnCarrierManifestCrypto.verify(
                signedPacket,
                signedManifest,
                "other-key".toByteArray(Charsets.UTF_8),
            )
        )
    }

    @Test
    fun ecdsaSignedManifestVerifiesWithSenderPublicKey() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val packet = packetWithManifest(signature = "")
        val unsigned = packet.manifest!!
        val signature = DtnCarrierManifestCrypto.signEcdsaP256(packet, unsigned, keyPair.private)
        val signedManifest = unsigned.copy(signature = signature)
        val signedPacket = packet.copy(manifest = signedManifest)

        val verifier = EcdsaDtnCarrierManifestVerifier(
            DtnCarrierManifestKeyResolver { peerId ->
                if (peerId == "remote") keyPair.public.encoded else null
            }
        )

        assertTrue(verifier.verify(signedPacket, signedManifest))
        assertFalse(
            verifier.verify(
                signedPacket,
                signedManifest.copy(batches = listOf(signedManifest.batches.single().copy(payloadSha256 = "c".repeat(64)))),
            )
        )
    }

    private fun packetWithManifest(signature: String): DtnControlPacket {
        return DtnControlPacket(
            type = DtnControlTypes.MANIFEST_OFFER,
            peerId = "remote",
            gid = "g",
            sessionId = "s",
            manifest = DtnCarrierManifest(
                manifestId = "manifest-1",
                senderPeerId = "remote",
                receiverPeerId = "local",
                gidHash = "gid-hash",
                maxBatches = 2,
                maxBytes = 2_048,
                expiresAt = 2_100,
                nonce = "nonce-1",
                batches = listOf(
                    DtnCarrierManifestBatch(
                        batchId = "batch-1",
                        payloadSha256 = "a".repeat(64),
                        payloadBytes = 512,
                        kind = "app_batch",
                        expiresAt = 2_050,
                        priority = 3,
                    )
                ),
                signature = signature,
            ),
            ts = 1_999,
        )
    }
}
