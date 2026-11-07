package app.peermesh.security.groupinvitation

import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm

/** Immutable authenticated metadata for one logical private Nearby invitation message. */
data class PrivateRecipientCryptoContext(
    val protocolVersion: Int,
    val algorithmWireValue: String,
    val roomId: String,
    val messageId: String,
    val cardId: String,
    val logicalSenderId: String,
    val targetParticipantId: String,
    val targetKeyId: String,
    val effectiveRoomExpiryEpochMs: Long,
)

/** Opaque encrypted primitive fields. Mutable byte arrays are never exposed by reference. */
class PrivateRecipientEncryptedPayload(
    val algorithmWireValue: String,
    val targetKeyId: String,
    ephemeralPublicKeySec1: ByteArray,
    nonce: ByteArray,
    ciphertextAndTag: ByteArray,
) {
    private val ephemeralPublicKeyBytes = ephemeralPublicKeySec1.copyOf()
    private val nonceBytes = nonce.copyOf()
    private val ciphertextBytes = ciphertextAndTag.copyOf()

    val ephemeralPublicKeySec1: ByteArray get() = ephemeralPublicKeyBytes.copyOf()
    val nonce: ByteArray get() = nonceBytes.copyOf()
    val ciphertextAndTag: ByteArray get() = ciphertextBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is PrivateRecipientEncryptedPayload &&
            algorithmWireValue == other.algorithmWireValue &&
            targetKeyId == other.targetKeyId &&
            ephemeralPublicKeyBytes.contentEquals(other.ephemeralPublicKeyBytes) &&
            nonceBytes.contentEquals(other.nonceBytes) &&
            ciphertextBytes.contentEquals(other.ciphertextBytes)

    override fun hashCode(): Int {
        var result = algorithmWireValue.hashCode()
        result = 31 * result + targetKeyId.hashCode()
        result = 31 * result + ephemeralPublicKeyBytes.contentHashCode()
        result = 31 * result + nonceBytes.contentHashCode()
        result = 31 * result + ciphertextBytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "PrivateRecipientEncryptedPayload(algorithm=$algorithmWireValue, targetKeyId=[REDACTED], " +
            "ephemeralPublicKeySec1=[REDACTED], nonce=[REDACTED], ciphertextAndTag=[REDACTED])"
}

sealed interface PrivateRecipientEncryptResult {
    data class Success(val payload: PrivateRecipientEncryptedPayload) : PrivateRecipientEncryptResult
    data object UnsupportedProtocolVersion : PrivateRecipientEncryptResult
    data object UnsupportedAlgorithm : PrivateRecipientEncryptResult
    data object InvalidContext : PrivateRecipientEncryptResult
    data object InvalidRecipientIdentity : PrivateRecipientEncryptResult
    data object KeyAgreementFailed : PrivateRecipientEncryptResult
    data object CryptoUnavailable : PrivateRecipientEncryptResult
}

sealed interface PrivateRecipientDecryptResult {
    class Success(plaintext: ByteArray) : PrivateRecipientDecryptResult {
        private val plaintextBytes = plaintext.copyOf()
        val plaintext: ByteArray get() = plaintextBytes.copyOf()
        override fun toString(): String = "PrivateRecipientDecryptResult.Success(plaintext=[REDACTED])"
    }

    data object Expired : PrivateRecipientDecryptResult
    data object UnsupportedProtocolVersion : PrivateRecipientDecryptResult
    data object UnsupportedAlgorithm : PrivateRecipientDecryptResult
    data object InvalidContext : PrivateRecipientDecryptResult
    data object InvalidEncryptedPayload : PrivateRecipientDecryptResult
    data object NotForLocalRecipientKey : PrivateRecipientDecryptResult
    data class RecipientIdentityUnavailable(
        val reason: RecipientEncryptionIdentityStatus.Reason,
    ) : PrivateRecipientDecryptResult
    data class RecipientIdentityInvalidated(
        val reason: RecipientEncryptionIdentityStatus.Reason,
    ) : PrivateRecipientDecryptResult
    data object UnsupportedPlatform : PrivateRecipientDecryptResult
    data object AuthenticationFailed : PrivateRecipientDecryptResult
    data object KeyAgreementFailed : PrivateRecipientDecryptResult
    data object CryptoUnavailable : PrivateRecipientDecryptResult
}

interface PrivateRecipientCrypto {
    fun encrypt(
        plaintext: ByteArray,
        recipientIdentity: RecipientEncryptionIdentity,
        context: PrivateRecipientCryptoContext,
    ): PrivateRecipientEncryptResult

    fun decrypt(
        encrypted: PrivateRecipientEncryptedPayload,
        context: PrivateRecipientCryptoContext,
        nowEpochMs: Long,
    ): PrivateRecipientDecryptResult
}

internal object PrivateRecipientCryptoContract {
    const val PROTOCOL_VERSION = 1
    const val CONTEXT_FORMAT_VERSION: Int = 1
    const val NONCE_BYTES = 12
    const val GCM_TAG_BYTES = 16
    const val AES_KEY_BYTES = 32
    const val ECDH_SECRET_BYTES = 32
    const val KEY_ID_BASE64URL_CHARS = 43
    val ALGORITHM: PrivateInvitationEncryptionAlgorithm =
        PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM
    const val HKDF_LABEL = "peermesh/group-invitation/private/v1"
}
