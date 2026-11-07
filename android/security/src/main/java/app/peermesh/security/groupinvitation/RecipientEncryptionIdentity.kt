package app.peermesh.security.groupinvitation

import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm

/** Public, immutable snapshot of this installation's recipient-encryption identity. */
class RecipientEncryptionIdentity(
    val algorithm: PrivateInvitationEncryptionAlgorithm,
    val keyId: String,
    publicKeySec1: ByteArray,
) {
    private val publicKeyBytes = publicKeySec1.copyOf()

    val publicKeySec1: ByteArray
        get() = publicKeyBytes.copyOf()

    init {
        require(keyId.isNotBlank()) { "Recipient key id must not be blank" }
        P256Sec1PublicKeyCodec.validate(publicKeyBytes)
        require(
            RecipientEncryptionKeyId.derive(algorithm, publicKeyBytes) == keyId,
        ) { "Recipient key id does not match the public key" }
    }

    override fun equals(other: Any?): Boolean =
        other is RecipientEncryptionIdentity &&
            algorithm == other.algorithm &&
            keyId == other.keyId &&
            publicKeyBytes.contentEquals(other.publicKeyBytes)

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + keyId.hashCode()
        result = 31 * result + publicKeyBytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RecipientEncryptionIdentity(algorithm=${algorithm.wireValue}, keyId=${keyId.take(8)}..., publicKeySec1=[REDACTED])"
}

sealed interface RecipientEncryptionIdentityStatus {
    data class Available(val identity: RecipientEncryptionIdentity) : RecipientEncryptionIdentityStatus

    data object UnsupportedPlatform : RecipientEncryptionIdentityStatus

    data class TemporarilyUnavailable(val reason: Reason) : RecipientEncryptionIdentityStatus

    data class Invalidated(val reason: Reason) : RecipientEncryptionIdentityStatus

    enum class Reason {
        KEYSTORE_UNAVAILABLE,
        KEY_GENERATION_FAILED,
        KEY_LOAD_FAILED,
        INVALID_KEY_MATERIAL,
        METADATA_INCONSISTENT,
        METADATA_UNAVAILABLE,
        METADATA_WRITE_FAILED,
        KEY_AGREEMENT_PROBE_FAILED,
        KEY_INVALIDATED,
        ALIAS_MISSING,
        RESET_RECOVERY_REQUIRED,
    }
}

sealed interface RecipientPrivateKeyResult {
    class Available(val privateKey: java.security.PrivateKey) : RecipientPrivateKeyResult {
        override fun toString(): String = "RecipientPrivateKeyResult.Available(privateKey=[REDACTED])"
    }
    data object KeyIdMismatch : RecipientPrivateKeyResult
    data object UnsupportedPlatform : RecipientPrivateKeyResult
    data object NotFound : RecipientPrivateKeyResult
    data class TemporarilyUnavailable(
        val reason: RecipientEncryptionIdentityStatus.Reason,
    ) : RecipientPrivateKeyResult
    data class Invalidated(
        val reason: RecipientEncryptionIdentityStatus.Reason,
    ) : RecipientPrivateKeyResult
}

sealed interface RecipientIdentityResetResult {
    data object Success : RecipientIdentityResetResult
    data object UnsupportedPlatform : RecipientIdentityResetResult
    data class BlockedByActiveNearbyRooms(val activeNearbyRoomCount: Int) : RecipientIdentityResetResult
    data object InvalidActiveNearbyRoomCount : RecipientIdentityResetResult
    data class TemporarilyUnavailable(
        val reason: RecipientEncryptionIdentityStatus.Reason,
    ) : RecipientIdentityResetResult
}

interface RecipientEncryptionIdentityProvider {
    fun getOrCreateIdentity(): RecipientEncryptionIdentityStatus

    fun resolvePrivateKey(expectedKeyId: String): RecipientPrivateKeyResult

    fun resetIdentity(activeNearbyRoomCount: Int): RecipientIdentityResetResult
}
