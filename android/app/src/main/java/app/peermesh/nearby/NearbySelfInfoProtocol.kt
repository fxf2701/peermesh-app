package app.peermesh.nearby

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.core.json.CanonicalJson
import app.peermesh.security.groupinvitation.P256Sec1PublicKeyCodec
import app.peermesh.security.groupinvitation.RecipientEncryptionKeyId
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NearbyRecipientEncryptionPublicIdentity(
    val algorithm: PrivateInvitationEncryptionAlgorithm,
    val keyId: String,
    publicKeySec1: ByteArray,
) {
    private val publicKeyBytes = publicKeySec1.copyOf()

    val publicKeySec1: ByteArray
        get() = publicKeyBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is NearbyRecipientEncryptionPublicIdentity &&
            algorithm == other.algorithm &&
            keyId == other.keyId &&
            publicKeyBytes.contentEquals(other.publicKeyBytes)

    override fun hashCode(): Int =
        31 * (31 * algorithm.hashCode() + keyId.hashCode()) + publicKeyBytes.contentHashCode()

    override fun toString(): String =
        "NearbyRecipientEncryptionPublicIdentity(algorithm=${algorithm.wireValue}, " +
            "keyId=${keyId.take(8)}..., publicKeySec1=[REDACTED])"
}

data class NearbySelfInfoSnapshot(
    val revision: String,
    val recipientEncryptionIdentity: NearbyRecipientEncryptionPublicIdentity?,
)

object NearbySelfInfoProtocol {
    const val MAX_SELF_INFO_WIRE_BYTES = 1_024
    const val MAX_SELF_INFO_ACK_WIRE_BYTES = 512

    private const val REVISION_DOMAIN = "peermesh/nearby-self-info/revision/v1"
    private const val REVISION_VERSION = 1
    private const val SHA256_BASE64URL_CHARS = 43
    private const val P256_PUBLIC_KEY_BASE64URL_CHARS = 87
    private val BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]+$")

    fun buildSnapshot(
        ownerDeviceId: String,
        senderDisplayName: String?,
        avatarId: String?,
        profileUpdatedAtMs: Long?,
        recipientEncryptionIdentity: NearbyRecipientEncryptionPublicIdentity?,
    ): NearbySelfInfoSnapshot {
        require(ownerDeviceId.isNotBlank()) { "Self Info owner device ID is required" }
        val validatedIdentity = recipientEncryptionIdentity?.takeIf(::isValidRecipientEncryptionIdentity)
        val revision = deriveRevision(
            ownerDeviceId = ownerDeviceId,
            senderDisplayName = senderDisplayName,
            avatarId = avatarId,
            profileUpdatedAtMs = profileUpdatedAtMs,
            recipientEncryptionIdentity = validatedIdentity,
        )
        return NearbySelfInfoSnapshot(revision, validatedIdentity)
    }

    fun validateReceived(envelope: NearbyRoomTransportEnvelope): NearbySelfInfoSnapshot? {
        if (envelope.kind != NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1) return null
        val revision = envelope.self_info_revision?.takeIf(::isValidSha256Base64Url) ?: return null
        val identity = parseAdvertisedIdentity(envelope)
        val expectedRevision = deriveRevision(
            ownerDeviceId = envelope.origin_device_id?.takeIf { it.isNotBlank() } ?: envelope.sender_device_id,
            senderDisplayName = envelope.sender_display_name,
            avatarId = envelope.avatar_id,
            profileUpdatedAtMs = envelope.profile_updated_at_ms,
            recipientEncryptionIdentity = identity,
        )
        return NearbySelfInfoSnapshot(revision, identity).takeIf { revision == expectedRevision }
    }

    fun parseAdvertisedIdentity(
        envelope: NearbyRoomTransportEnvelope,
    ): NearbyRecipientEncryptionPublicIdentity? {
        if (envelope.kind != NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1) return null
        if (envelope.recipient_encryption_supported != true) return null
        val algorithm = PrivateInvitationEncryptionAlgorithm.entries.firstOrNull {
            it.wireValue == envelope.recipient_encryption_algorithm
        } ?: return null
        val keyId = envelope.recipient_encryption_key_id ?: return null
        val encodedPublicKey = envelope.recipient_encryption_public_key ?: return null
        if (!isStrictBase64Url(keyId, SHA256_BASE64URL_CHARS)) return null
        if (!isStrictBase64Url(encodedPublicKey, P256_PUBLIC_KEY_BASE64URL_CHARS)) return null
        val publicKey = runCatching { Base64Url.decode(encodedPublicKey) }.getOrNull() ?: return null
        if (runCatching { P256Sec1PublicKeyCodec.validate(publicKey) }.isFailure) return null
        if (runCatching { RecipientEncryptionKeyId.derive(algorithm, publicKey) }.getOrNull() != keyId) return null
        return NearbyRecipientEncryptionPublicIdentity(algorithm, keyId, publicKey)
    }

    fun applySnapshot(
        envelope: NearbyRoomTransportEnvelope,
        snapshot: NearbySelfInfoSnapshot,
    ): NearbyRoomTransportEnvelope {
        require(envelope.kind == NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1) {
            "Recipient identity may only be applied to Self Info"
        }
        val identity = snapshot.recipientEncryptionIdentity
        return envelope.copy(
            recipient_encryption_supported = identity?.let { true },
            recipient_encryption_algorithm = identity?.algorithm?.wireValue,
            recipient_encryption_key_id = identity?.keyId,
            recipient_encryption_public_key = identity?.let { Base64Url.encode(it.publicKeySec1) },
            self_info_revision = snapshot.revision,
        )
    }

    fun isValidRevision(value: String?): Boolean = value?.let(::isValidSha256Base64Url) == true

    internal fun isValidRecipientEncryptionIdentity(
        identity: NearbyRecipientEncryptionPublicIdentity,
    ): Boolean =
        runCatching {
            P256Sec1PublicKeyCodec.validate(identity.publicKeySec1)
            require(RecipientEncryptionKeyId.derive(identity.algorithm, identity.publicKeySec1) == identity.keyId)
        }.isSuccess

    private fun deriveRevision(
        ownerDeviceId: String,
        senderDisplayName: String?,
        avatarId: String?,
        profileUpdatedAtMs: Long?,
        recipientEncryptionIdentity: NearbyRecipientEncryptionPublicIdentity?,
    ): String {
        val publicKey = recipientEncryptionIdentity?.let { Base64Url.encode(it.publicKeySec1) }.orEmpty()
        val canonical = CanonicalJson.canonicalize(buildJsonObject {
            put("av", avatarId.orEmpty())
            put("od", ownerDeviceId)
            put("pu", profileUpdatedAtMs ?: 0L)
            put("rea", recipientEncryptionIdentity?.algorithm?.wireValue.orEmpty())
            put("rek", recipientEncryptionIdentity?.keyId.orEmpty())
            put("rep", publicKey)
            put("res", recipientEncryptionIdentity != null)
            put("sn", senderDisplayName.orEmpty())
            put("v", REVISION_VERSION)
        }).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(REVISION_DOMAIN.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(canonical)
        return Base64Url.encode(digest.digest())
    }

    private fun isValidSha256Base64Url(value: String): Boolean =
        isStrictBase64Url(value, SHA256_BASE64URL_CHARS) &&
            runCatching { Base64Url.decode(value).size == 32 }.getOrDefault(false)

    private fun isStrictBase64Url(value: String, expectedChars: Int): Boolean =
        value.length == expectedChars && BASE64URL_REGEX.matches(value)
}
