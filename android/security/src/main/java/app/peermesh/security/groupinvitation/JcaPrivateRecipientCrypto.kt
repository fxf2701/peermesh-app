package app.peermesh.security.groupinvitation

import app.peermesh.core.encoding.Base64Url
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class JcaPrivateRecipientCrypto internal constructor(
    private val identityProvider: RecipientEncryptionIdentityProvider,
    private val secureRandom: SecureRandom,
    private val ephemeralKeyPairFactory: () -> KeyPair,
) : PrivateRecipientCrypto {
    constructor(identityProvider: RecipientEncryptionIdentityProvider) : this(
        identityProvider = identityProvider,
        secureRandom = SecureRandom(),
        ephemeralKeyPairFactory = {
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        },
    )

    override fun encrypt(
        plaintext: ByteArray,
        recipientIdentity: RecipientEncryptionIdentity,
        context: PrivateRecipientCryptoContext,
    ): PrivateRecipientEncryptResult {
        when (validateContext(context)) {
            ContextValidation.UNSUPPORTED_PROTOCOL -> return PrivateRecipientEncryptResult.UnsupportedProtocolVersion
            ContextValidation.UNSUPPORTED_ALGORITHM -> return PrivateRecipientEncryptResult.UnsupportedAlgorithm
            ContextValidation.INVALID -> return PrivateRecipientEncryptResult.InvalidContext
            ContextValidation.VALID -> Unit
        }
        if (!recipientIdentityMatches(recipientIdentity, context)) {
            return PrivateRecipientEncryptResult.InvalidRecipientIdentity
        }

        val recipientPublicKey = try {
            P256Sec1PublicKeyCodec.decode(recipientIdentity.publicKeySec1)
        } catch (_: IllegalArgumentException) {
            return PrivateRecipientEncryptResult.InvalidRecipientIdentity
        } catch (_: GeneralSecurityException) {
            return PrivateRecipientEncryptResult.CryptoUnavailable
        }

        val contextBytes = try {
            PrivateRecipientContextEncoder.encode(context)
        } catch (_: IllegalArgumentException) {
            return PrivateRecipientEncryptResult.InvalidContext
        }

        var sharedSecret: ByteArray? = null
        var prk: ByteArray? = null
        var aesKey: ByteArray? = null
        var temporaryCiphertext: ByteArray? = null
        return try {
            val ephemeralKeyPair = ephemeralKeyPairFactory()
            val ephemeralPublicKey = P256Sec1PublicKeyCodec.encode(ephemeralKeyPair.public)
            val nonce = ByteArray(PrivateRecipientCryptoContract.NONCE_BYTES).also(secureRandom::nextBytes)

            val secret = performEcdh(ephemeralKeyPair.private, recipientPublicKey)
                ?: return PrivateRecipientEncryptResult.KeyAgreementFailed
            sharedSecret = secret
            if (secret.size != PrivateRecipientCryptoContract.ECDH_SECRET_BYTES) {
                return PrivateRecipientEncryptResult.KeyAgreementFailed
            }
            val derived = HkdfSha256.derive(secret, contextBytes)
            prk = derived.prk
            val encryptionKey = derived.okm
            aesKey = encryptionKey

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(encryptionKey, "AES"),
                GCMParameterSpec(128, nonce),
            )
            cipher.updateAAD(contextBytes)
            val ciphertextAndTag = cipher.doFinal(plaintext)
            temporaryCiphertext = ciphertextAndTag
            PrivateRecipientEncryptResult.Success(
                PrivateRecipientEncryptedPayload(
                    algorithmWireValue = context.algorithmWireValue,
                    targetKeyId = context.targetKeyId,
                    ephemeralPublicKeySec1 = ephemeralPublicKey,
                    nonce = nonce,
                    ciphertextAndTag = ciphertextAndTag,
                ),
            )
        } catch (_: NoSuchAlgorithmException) {
            PrivateRecipientEncryptResult.CryptoUnavailable
        } catch (_: NoSuchPaddingException) {
            PrivateRecipientEncryptResult.CryptoUnavailable
        } catch (_: GeneralSecurityException) {
            PrivateRecipientEncryptResult.CryptoUnavailable
        } catch (_: IllegalArgumentException) {
            PrivateRecipientEncryptResult.CryptoUnavailable
        } finally {
            sharedSecret?.fill(0)
            prk?.fill(0)
            aesKey?.fill(0)
            temporaryCiphertext?.fill(0)
            contextBytes.fill(0)
        }
    }

    override fun decrypt(
        encrypted: PrivateRecipientEncryptedPayload,
        context: PrivateRecipientCryptoContext,
        nowEpochMs: Long,
    ): PrivateRecipientDecryptResult {
        when (validateContext(context)) {
            ContextValidation.UNSUPPORTED_PROTOCOL -> return PrivateRecipientDecryptResult.UnsupportedProtocolVersion
            ContextValidation.UNSUPPORTED_ALGORITHM -> return PrivateRecipientDecryptResult.UnsupportedAlgorithm
            ContextValidation.INVALID -> return PrivateRecipientDecryptResult.InvalidContext
            ContextValidation.VALID -> Unit
        }
        val ephemeralPublicBytes = encrypted.ephemeralPublicKeySec1
        val nonce = encrypted.nonce
        val ciphertextAndTag = encrypted.ciphertextAndTag
        if (!validKeyId(encrypted.targetKeyId) ||
            ephemeralPublicBytes.size != P256Sec1PublicKeyCodec.ENCODED_BYTES ||
            ephemeralPublicBytes.firstOrNull() != 0x04.toByte() ||
            nonce.size != PrivateRecipientCryptoContract.NONCE_BYTES ||
            ciphertextAndTag.size < PrivateRecipientCryptoContract.GCM_TAG_BYTES
        ) {
            ephemeralPublicBytes.fill(0)
            nonce.fill(0)
            ciphertextAndTag.fill(0)
            return PrivateRecipientDecryptResult.InvalidEncryptedPayload
        }

        val ephemeralPublicKey = try {
            P256Sec1PublicKeyCodec.decode(ephemeralPublicBytes)
        } catch (_: IllegalArgumentException) {
            wipe(ephemeralPublicBytes, nonce, ciphertextAndTag)
            return PrivateRecipientDecryptResult.InvalidEncryptedPayload
        } catch (_: GeneralSecurityException) {
            wipe(ephemeralPublicBytes, nonce, ciphertextAndTag)
            return PrivateRecipientDecryptResult.CryptoUnavailable
        }

        if (nowEpochMs >= context.effectiveRoomExpiryEpochMs) {
            wipe(ephemeralPublicBytes, nonce, ciphertextAndTag)
            return PrivateRecipientDecryptResult.Expired
        }
        if (encrypted.algorithmWireValue != context.algorithmWireValue) {
            wipe(ephemeralPublicBytes, nonce, ciphertextAndTag)
            return PrivateRecipientDecryptResult.UnsupportedAlgorithm
        }
        if (encrypted.targetKeyId != context.targetKeyId) {
            wipe(ephemeralPublicBytes, nonce, ciphertextAndTag)
            return PrivateRecipientDecryptResult.InvalidContext
        }

        val localPrivateKey = when (val resolved = identityProvider.resolvePrivateKey(context.targetKeyId)) {
            is RecipientPrivateKeyResult.Available -> resolved.privateKey
            RecipientPrivateKeyResult.KeyIdMismatch,
            RecipientPrivateKeyResult.NotFound -> {
                wipe(ephemeralPublicBytes, nonce, ciphertextAndTag)
                return PrivateRecipientDecryptResult.NotForLocalRecipientKey
            }
            RecipientPrivateKeyResult.UnsupportedPlatform -> {
                wipe(ephemeralPublicBytes, nonce, ciphertextAndTag)
                return PrivateRecipientDecryptResult.UnsupportedPlatform
            }
            is RecipientPrivateKeyResult.TemporarilyUnavailable -> {
                wipe(ephemeralPublicBytes, nonce, ciphertextAndTag)
                return PrivateRecipientDecryptResult.RecipientIdentityUnavailable(resolved.reason)
            }
            is RecipientPrivateKeyResult.Invalidated -> {
                wipe(ephemeralPublicBytes, nonce, ciphertextAndTag)
                return PrivateRecipientDecryptResult.RecipientIdentityInvalidated(resolved.reason)
            }
        }

        val contextBytes = try {
            PrivateRecipientContextEncoder.encode(context)
        } catch (_: IllegalArgumentException) {
            wipe(ephemeralPublicBytes, nonce, ciphertextAndTag)
            return PrivateRecipientDecryptResult.InvalidContext
        }
        var sharedSecret: ByteArray? = null
        var prk: ByteArray? = null
        var aesKey: ByteArray? = null
        var temporaryPlaintext: ByteArray? = null
        return try {
            val secret = performEcdh(localPrivateKey, ephemeralPublicKey)
                ?: return PrivateRecipientDecryptResult.KeyAgreementFailed
            sharedSecret = secret
            if (secret.size != PrivateRecipientCryptoContract.ECDH_SECRET_BYTES) {
                return PrivateRecipientDecryptResult.KeyAgreementFailed
            }
            val derived = HkdfSha256.derive(secret, contextBytes)
            prk = derived.prk
            val decryptionKey = derived.okm
            aesKey = decryptionKey

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(decryptionKey, "AES"),
                GCMParameterSpec(128, nonce),
            )
            cipher.updateAAD(contextBytes)
            val plaintext = cipher.doFinal(ciphertextAndTag)
            temporaryPlaintext = plaintext
            PrivateRecipientDecryptResult.Success(plaintext)
        } catch (_: AEADBadTagException) {
            PrivateRecipientDecryptResult.AuthenticationFailed
        } catch (_: BadPaddingException) {
            PrivateRecipientDecryptResult.AuthenticationFailed
        } catch (_: NoSuchAlgorithmException) {
            PrivateRecipientDecryptResult.CryptoUnavailable
        } catch (_: NoSuchPaddingException) {
            PrivateRecipientDecryptResult.CryptoUnavailable
        } catch (_: GeneralSecurityException) {
            PrivateRecipientDecryptResult.CryptoUnavailable
        } finally {
            sharedSecret?.fill(0)
            prk?.fill(0)
            aesKey?.fill(0)
            temporaryPlaintext?.fill(0)
            contextBytes.fill(0)
            ephemeralPublicBytes.fill(0)
            nonce.fill(0)
            ciphertextAndTag.fill(0)
        }
    }

    private fun recipientIdentityMatches(
        identity: RecipientEncryptionIdentity,
        context: PrivateRecipientCryptoContext,
    ): Boolean =
        identity.algorithm == PrivateRecipientCryptoContract.ALGORITHM &&
            identity.algorithm.wireValue == context.algorithmWireValue &&
            identity.keyId == context.targetKeyId &&
            validKeyId(identity.keyId)

    private fun validateContext(context: PrivateRecipientCryptoContext): ContextValidation {
        if (context.protocolVersion != PrivateRecipientCryptoContract.PROTOCOL_VERSION) {
            return ContextValidation.UNSUPPORTED_PROTOCOL
        }
        if (context.algorithmWireValue != PrivateRecipientCryptoContract.ALGORITHM.wireValue) {
            return ContextValidation.UNSUPPORTED_ALGORITHM
        }
        if (context.effectiveRoomExpiryEpochMs < 0L ||
            context.roomId.isBlank() ||
            context.messageId.isBlank() ||
            context.cardId.isBlank() ||
            context.logicalSenderId.isBlank() ||
            context.targetParticipantId.isBlank() ||
            !validKeyId(context.targetKeyId)
        ) {
            return ContextValidation.INVALID
        }
        return ContextValidation.VALID
    }

    private fun validKeyId(value: String): Boolean {
        if (value.length != PrivateRecipientCryptoContract.KEY_ID_BASE64URL_CHARS ||
            value.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '-' && it != '_' }
        ) {
            return false
        }
        val decoded = try {
            Base64Url.decode(value)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            decoded.size == 32 && Base64Url.encode(decoded) == value
        } finally {
            decoded.fill(0)
        }
    }

    private fun performEcdh(privateKey: java.security.PrivateKey, publicKey: java.security.PublicKey): ByteArray? =
        try {
            KeyAgreement.getInstance("ECDH").run {
                init(privateKey)
                doPhase(publicKey, true)
                generateSecret()
            }
        } catch (error: NoSuchAlgorithmException) {
            throw error
        } catch (_: GeneralSecurityException) {
            null
        }

    private fun wipe(vararg arrays: ByteArray) = arrays.forEach { it.fill(0) }

    private enum class ContextValidation { VALID, UNSUPPORTED_PROTOCOL, UNSUPPORTED_ALGORITHM, INVALID }
}

internal object PrivateRecipientContextEncoder {
    private val MAGIC = "PMGI".toByteArray(Charsets.US_ASCII)

    fun encode(context: PrivateRecipientCryptoContext): ByteArray {
        require(context.protocolVersion in 0..0xffff) { "Protocol version is out of range" }
        require(context.effectiveRoomExpiryEpochMs >= 0L) { "Expiry must be non-negative" }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeByte(PrivateRecipientCryptoContract.CONTEXT_FORMAT_VERSION)
            data.writeShort(context.protocolVersion)
            writeText(data, context.algorithmWireValue)
            writeText(data, context.roomId)
            writeText(data, context.messageId)
            writeText(data, context.cardId)
            writeText(data, context.logicalSenderId)
            writeText(data, context.targetParticipantId)
            writeText(data, context.targetKeyId)
            data.writeLong(context.effectiveRoomExpiryEpochMs)
        }
        return output.toByteArray()
    }

    private fun writeText(output: DataOutputStream, value: String) {
        val bytes = strictUtf8(value)
        output.writeInt(bytes.size)
        output.write(bytes)
        bytes.fill(0)
    }

    private fun strictUtf8(value: String): ByteArray = try {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also(encoded::get)
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("Context contains malformed UTF-16")
    }
}

internal object HkdfSha256 {
    private val ZERO_SALT = ByteArray(32)
    private val LABEL = PrivateRecipientCryptoContract.HKDF_LABEL.toByteArray(Charsets.US_ASCII)

    data class Derived(val prk: ByteArray, val okm: ByteArray)

    fun derive(sharedSecret: ByteArray, context: ByteArray): Derived {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(ZERO_SALT, "HmacSHA256"))
        val prk = extract.doFinal(sharedSecret)

        val info = ByteArrayOutputStream().also { output ->
            output.write(LABEL)
            output.write(0)
            DataOutputStream(output).use { it.writeInt(context.size) }
            output.write(context)
        }.toByteArray()

        var completed = false
        return try {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.update(info)
            expand.update(1)
            Derived(prk = prk, okm = expand.doFinal().copyOf(PrivateRecipientCryptoContract.AES_KEY_BYTES)).also {
                completed = true
            }
        } finally {
            info.fill(0)
            if (!completed) prk.fill(0)
        }
    }
}
