package app.peermesh.nearby

import app.peermesh.core.encoding.Base64Url
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object NearbyRoomMessageCrypto {
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val NONCE_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    data class EncryptedPayload(
        val ciphertext: String,
        val nonce: String,
    )

    fun encrypt(
        plaintext: String,
        roomKey: ByteArray,
        aad: ByteArray,
    ): EncryptedPayload {
        val nonce = ByteArray(NONCE_SIZE_BYTES)
        SecureRandom().nextBytes(nonce)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(roomKey, "AES"), GCMParameterSpec(TAG_SIZE_BITS, nonce))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            ciphertext = Base64Url.encode(sealed),
            nonce = Base64Url.encode(nonce),
        )
    }

    fun decrypt(
        ciphertext: String,
        nonce: String,
        roomKey: ByteArray,
        aad: ByteArray,
    ): String? {
        return runCatching {
            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(roomKey, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, Base64Url.decode(nonce)),
            )
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(Base64Url.decode(ciphertext))
            plaintext.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun messageAad(
        roomId: String,
        messageId: String,
        senderDeviceId: String,
        createdAtMs: Long,
        expiresAtMs: Long,
        keyVersion: Int,
    ): ByteArray {
        return listOf(
            NearbyRoomTransportEnvelope.KIND_MESSAGE_V2,
            roomId,
            messageId,
            senderDeviceId,
            createdAtMs.toString(),
            expiresAtMs.toString(),
            keyVersion.toString(),
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }
}
