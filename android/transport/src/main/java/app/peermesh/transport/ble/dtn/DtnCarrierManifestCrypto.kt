package app.peermesh.transport.ble.dtn

import app.peermesh.core.json.CanonicalJson
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun interface DtnCarrierManifestKeyResolver {
    fun keyForPeer(peerId: String): ByteArray?
}

class HmacDtnCarrierManifestVerifier(
    private val keyResolver: DtnCarrierManifestKeyResolver,
) : DtnCarrierManifestVerifier {
    override fun verify(packet: DtnControlPacket, manifest: DtnCarrierManifest): Boolean {
        if (packet.type != DtnControlTypes.MANIFEST_OFFER) return false
        if (packet.peerId != manifest.senderPeerId) return false
        val key = keyResolver.keyForPeer(manifest.senderPeerId) ?: return false
        return DtnCarrierManifestCrypto.verify(packet, manifest, key)
    }
}

class EcdsaDtnCarrierManifestVerifier(
    private val keyResolver: DtnCarrierManifestKeyResolver,
) : DtnCarrierManifestVerifier {
    override fun verify(packet: DtnControlPacket, manifest: DtnCarrierManifest): Boolean {
        if (packet.type != DtnControlTypes.MANIFEST_OFFER) return false
        if (packet.peerId != manifest.senderPeerId) return false
        val publicKeyBytes = keyResolver.keyForPeer(manifest.senderPeerId) ?: return false
        return DtnCarrierManifestCrypto.verifyEcdsaP256(packet, manifest, publicKeyBytes)
    }
}

object DtnCarrierManifestCrypto {
    private const val HMAC_SHA256 = "HmacSHA256"
    private const val ECDSA_SHA256 = "SHA256withECDSA"
    private const val EC = "EC"

    fun sign(packet: DtnControlPacket, manifest: DtnCarrierManifest, key: ByteArray): String {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        val digest = mac.doFinal(signingBytes(packet, manifest))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun verify(packet: DtnControlPacket, manifest: DtnCarrierManifest, key: ByteArray): Boolean {
        val expected = runCatching { Base64.getUrlDecoder().decode(manifest.signature) }.getOrNull()
            ?: return false
        val actual = runCatching {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(key, HMAC_SHA256))
            mac.doFinal(signingBytes(packet, manifest))
        }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    fun signEcdsaP256(packet: DtnControlPacket, manifest: DtnCarrierManifest, privateKey: PrivateKey): String {
        val signature = Signature.getInstance(ECDSA_SHA256)
        signature.initSign(privateKey)
        signature.update(signingBytes(packet, manifest))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())
    }

    fun verifyEcdsaP256(packet: DtnControlPacket, manifest: DtnCarrierManifest, publicKeyBytes: ByteArray): Boolean {
        val signatureBytes = runCatching { Base64.getUrlDecoder().decode(manifest.signature) }.getOrNull()
            ?: return false
        return runCatching {
            val publicKey = KeyFactory.getInstance(EC).generatePublic(X509EncodedKeySpec(publicKeyBytes))
            val signature = Signature.getInstance(ECDSA_SHA256)
            signature.initVerify(publicKey)
            signature.update(signingBytes(packet, manifest))
            signature.verify(signatureBytes)
        }.getOrDefault(false)
    }

    private fun signingBytes(packet: DtnControlPacket, manifest: DtnCarrierManifest): ByteArray {
        val json = buildJsonObject {
            put("v", packet.v)
            put("type", packet.type)
            put("peer_id", packet.peerId)
            packet.gid?.let { put("gid", it) }
            packet.sessionId?.let { put("session_id", it) }
            put("manifest", manifest.signingJson())
        }
        return CanonicalJson.json.encodeToString(JsonObject.serializer(), json).toByteArray(Charsets.UTF_8)
    }

    private fun DtnCarrierManifest.signingJson(): JsonObject {
        return buildJsonObject {
            put("manifest_id", manifestId)
            put("sender_peer_id", senderPeerId)
            receiverPeerId?.let { put("receiver_peer_id", it) }
            put("gid_hash", gidHash)
            put("max_batches", maxBatches)
            put("max_bytes", maxBytes)
            put("expires_at", expiresAt)
            put("nonce", nonce)
            put("batches", buildJsonArray {
                batches.forEach { batch ->
                    add(
                        buildJsonObject {
                            put("batch_id", batch.batchId)
                            put("payload_sha256", batch.payloadSha256)
                            put("payload_bytes", batch.payloadBytes)
                            put("kind", batch.kind)
                            put("expires_at", batch.expiresAt)
                            put("priority", batch.priority)
                        }
                    )
                }
            })
        }
    }
}
