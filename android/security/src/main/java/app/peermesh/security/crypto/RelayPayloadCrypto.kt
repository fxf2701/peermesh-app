package app.peermesh.security.crypto

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.relay.RelayEncryptedBody
import app.peermesh.core.relay.RelayEvent
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object RelayPayloadCrypto {
    private const val ENCRYPTION_ALGORITHM = "AES-256-GCM"
    private const val CONTENT_TYPE = "peermesh/app-event+json"
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val NONCE_SIZE_BYTES = 12
    private const val TAG_SIZE_BYTES = 16
    private const val GCM_TAG_SIZE_BITS = TAG_SIZE_BYTES * 8

    data class VerifiedRelayPayload(
        val body: RelayEncryptedBody,
        val core: EventCore,
    )

    fun encryptBody(
        gid: String,
        eid: String,
        ts: Long,
        exp: Long,
        core: EventCore,
        groupKey: ByteArray,
        kid: String,
    ): RelayEncryptedBody {
        val nonce = ByteArray(NONCE_SIZE_BYTES)
        SecureRandom().nextBytes(nonce)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(groupKey, "AES"), GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce))
        val bodyTemplate = RelayEncryptedBody(
            v = 1,
            enc = ENCRYPTION_ALGORITHM,
            kid = kid,
            cty = CONTENT_TYPE,
            nonce = Base64Url.encode(nonce),
            ct = "",
            tag = "",
        )
        cipher.updateAAD(aadBytes(gid, eid, ts, exp, bodyTemplate))
        val plaintext = CanonicalJson.json.encodeToString(EventCore.serializer(), core).toByteArray(Charsets.UTF_8)
        val sealed = cipher.doFinal(plaintext)
        val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_SIZE_BYTES)
        val tag = sealed.copyOfRange(sealed.size - TAG_SIZE_BYTES, sealed.size)
        return bodyTemplate.copy(
            ct = Base64Url.encode(ciphertext),
            tag = Base64Url.encode(tag),
        )
    }

    fun signRelayEvent(event: RelayEvent, groupKey: ByteArray): String {
        val body = bodyOrNull(event) ?: throw IllegalArgumentException("relay body must be RelayEncryptedBody")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(groupKey, "HmacSHA256"))
        val sig = mac.doFinal(eventCanonical(event, body).toByteArray(Charsets.UTF_8))
        return Base64Url.encode(sig)
    }

    fun verifyAndDecrypt(event: RelayEvent, groupKey: ByteArray): VerifiedRelayPayload? {
        val body = bodyOrNull(event) ?: return null
        if (body.v != 1 || body.enc != ENCRYPTION_ALGORITHM || body.cty != CONTENT_TYPE) return null
        val expectedSig = runCatching { Base64Url.decode(event.sig) }.getOrNull() ?: return null
        val actualSig = runCatching { Base64Url.decode(signRelayEvent(event, groupKey)) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(expectedSig, actualSig)) return null

        return runCatching {
            val nonce = Base64Url.decode(body.nonce)
            val ciphertext = Base64Url.decode(body.ct)
            val tag = Base64Url.decode(body.tag)
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(groupKey, "AES"), GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce))
            cipher.updateAAD(aadBytes(event.gid, event.eid, event.ts, event.exp, body))
            val plaintext = cipher.doFinal(ciphertext + tag)
            val core = CanonicalJson.json.decodeFromString(EventCore.serializer(), String(plaintext, Charsets.UTF_8))
            VerifiedRelayPayload(body = body, core = core)
        }.getOrNull()
    }

    private fun aadBytes(
        gid: String,
        eid: String,
        ts: Long,
        exp: Long,
        body: RelayEncryptedBody,
    ): ByteArray {
        return listOf(
            gid,
            eid,
            ts.toString(),
            exp.toString(),
            body.v.toString(),
            body.enc,
            body.kid,
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun eventCanonical(event: RelayEvent, body: RelayEncryptedBody): String {
        return listOf(
            event.gid,
            event.eid,
            event.ts.toString(),
            event.exp.toString(),
            body.v.toString(),
            body.enc,
            body.kid,
            body.cty,
            body.nonce,
            body.ct,
            body.tag,
        ).joinToString("\n")
    }

    private fun bodyOrNull(event: RelayEvent): RelayEncryptedBody? {
        return runCatching {
            CanonicalJson.json.decodeFromJsonElement(RelayEncryptedBody.serializer(), event.body)
        }.getOrNull()
    }
}
