package app.peermesh.core.groupinvitation

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.json.CanonicalJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit, narrowly scoped bridge to the existing application join-link handler. */
fun PublicGroupInvitationPayload.joinLinkForUserAction(): String = invitationCredential.rawValue

data class PublicGroupInvitationPayload(
    val cardId: String,
    val title: String,
    val shortDescription: String,
    val invitationCredential: InvitationCredential,
) {
    init {
        GroupInvitationPolicy.requireCardId(cardId)
        GroupInvitationPolicy.requirePresentation(title, shortDescription, icon = null)
    }
}

/** Plaintext sealed by GI2's recipient encryption before it enters a private outer envelope. */
data class PrivateGroupInvitationInnerPayload(
    val title: String,
    val shortDescription: String,
    val invitationCredential: InvitationCredential,
) {
    init {
        GroupInvitationPolicy.requirePresentation(title, shortDescription, icon = null)
    }
}

enum class PrivateInvitationEncryptionAlgorithm(val wireValue: String) {
    P256_HKDF_SHA256_A256GCM("p256-hkdf-sha256-a256gcm"),
}

/** Opaque recipient-confidential wrapper. GI1 defines its shape but does not perform cryptography. */
data class PrivateGroupInvitationEnvelope(
    val cardId: String,
    val targetParticipantId: String,
    val algorithm: PrivateInvitationEncryptionAlgorithm,
    val targetKeyId: String,
    val ephemeralPublicKey: String,
    val nonce: String,
    val ciphertext: String,
) {
    init {
        GroupInvitationPolicy.requireCardId(cardId)
        GroupInvitationPolicy.requireParticipantId(targetParticipantId)
        require(targetKeyId.isNotBlank() && targetKeyId.utf8Size() <= GroupInvitationLimits.MAX_TARGET_KEY_ID_UTF8_BYTES) {
            "Target key id is invalid"
        }
        requireBase64UrlLength(
            ephemeralPublicKey,
            GroupInvitationLimits.P256_UNCOMPRESSED_PUBLIC_KEY_BASE64URL_CHARS,
            "Ephemeral public key",
        )
        val decodedEphemeralPublicKey = Base64Url.decode(ephemeralPublicKey)
        require(decodedEphemeralPublicKey.size == GroupInvitationLimits.P256_UNCOMPRESSED_PUBLIC_KEY_BYTES) {
            "Ephemeral public key has invalid size"
        }
        require(decodedEphemeralPublicKey.firstOrNull() == SEC1_UNCOMPRESSED_POINT_PREFIX) {
            "Ephemeral public key has invalid SEC1 format"
        }
        requireBase64UrlLength(nonce, GroupInvitationLimits.AES_GCM_NONCE_BASE64URL_CHARS, "Nonce")
        require(Base64Url.decode(nonce).size == GroupInvitationLimits.AES_GCM_NONCE_BYTES) {
            "Nonce has invalid size"
        }
        require(ciphertext.length <= GroupInvitationLimits.MAX_PRIVATE_CIPHERTEXT_BASE64URL_CHARS) {
            "Ciphertext is too large"
        }
        requireBase64Url(ciphertext, "Ciphertext")
        require(Base64Url.decode(ciphertext).size >= GroupInvitationLimits.AES_GCM_TAG_BYTES) {
            "Ciphertext is too short"
        }
    }

    private fun requireBase64UrlLength(value: String, expectedLength: Int, label: String) {
        require(value.length == expectedLength) { "$label has invalid encoded size" }
        requireBase64Url(value, label)
    }

    private fun requireBase64Url(value: String, label: String) {
        require(value.isNotBlank() && value.all { it.isAsciiLetterOrDigit() || it == '_' || it == '-' }) {
            "$label is not valid Base64URL"
        }
        require(runCatching { Base64Url.decode(value) }.isSuccess) { "$label is not valid Base64URL" }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    override fun toString(): String =
        "PrivateGroupInvitationEnvelope(cardId=$cardId, targetParticipantId=$targetParticipantId, " +
            "algorithm=$algorithm, targetKeyId=[REDACTED], ephemeralPublicKey=[REDACTED], " +
            "nonce=[REDACTED], ciphertext=[REDACTED])"

    private companion object {
        val SEC1_UNCOMPRESSED_POINT_PREFIX: Byte = 0x04
    }
}

sealed interface GroupInvitationDecodedMessage {
    data class PublicInvitation(val payload: PublicGroupInvitationPayload) : GroupInvitationDecodedMessage
    data class PrivateEncryptedInvitation(val envelope: PrivateGroupInvitationEnvelope) : GroupInvitationDecodedMessage
}

sealed interface GroupInvitationDecodeResult {
    data class Invitation(val message: GroupInvitationDecodedMessage) : GroupInvitationDecodeResult
    data object NotGroupInvitation : GroupInvitationDecodeResult
    data object UnsupportedOrInvalidInvitation : GroupInvitationDecodeResult
}

object GroupInvitationProtocol {
    const val VERSION = 1

    /**
     * Classifies GI1 ownership without requiring a supported or otherwise valid payload.
     * This deliberately remains separate from [decode], which is the supported-payload policy.
     */
    fun isExactGroupInvitationCandidate(messageBody: String): Boolean =
        parseObject(messageBody)?.requiredString(FIELD_TYPE) == TYPE_GROUP_INVITATION

    fun encodePublic(payload: PublicGroupInvitationPayload): String =
        canonicalPayload(GroupInvitationLimits.MAX_PUBLIC_WIRE_PAYLOAD_BYTES) {
            put(FIELD_VERSION, VERSION)
            put(FIELD_TYPE, TYPE_GROUP_INVITATION)
            put(FIELD_CARD_ID, payload.cardId)
            put(FIELD_AUDIENCE, AUDIENCE_ROOM)
            put(FIELD_TITLE, payload.title)
            if (payload.shortDescription.isNotEmpty()) put(FIELD_DESCRIPTION, payload.shortDescription)
            put(FIELD_CREDENTIAL, payload.invitationCredential.rawValue)
        }

    fun encodePrivateEnvelope(envelope: PrivateGroupInvitationEnvelope): String =
        canonicalPayload(GroupInvitationLimits.MAX_PRIVATE_WIRE_PAYLOAD_BYTES) {
            put(FIELD_VERSION, VERSION)
            put(FIELD_TYPE, TYPE_GROUP_INVITATION)
            put(FIELD_CARD_ID, envelope.cardId)
            put(FIELD_AUDIENCE, AUDIENCE_PARTICIPANT)
            put(FIELD_TARGET_PARTICIPANT, envelope.targetParticipantId)
            put(FIELD_ALGORITHM, envelope.algorithm.wireValue)
            put(FIELD_TARGET_KEY_ID, envelope.targetKeyId)
            put(FIELD_EPHEMERAL_PUBLIC_KEY, envelope.ephemeralPublicKey)
            put(FIELD_NONCE, envelope.nonce)
            put(FIELD_CIPHERTEXT, envelope.ciphertext)
        }

    fun encodePrivateInnerPayload(payload: PrivateGroupInvitationInnerPayload): String =
        canonicalPayload(GroupInvitationLimits.MAX_PRIVATE_INNER_PLAINTEXT_BYTES) {
            put(FIELD_TITLE, payload.title)
            if (payload.shortDescription.isNotEmpty()) put(FIELD_DESCRIPTION, payload.shortDescription)
            put(FIELD_CREDENTIAL, payload.invitationCredential.rawValue)
        }

    fun decodePrivateInnerPayload(plaintext: String): PrivateGroupInvitationInnerPayload? {
        if (plaintext.utf8Size() > GroupInvitationLimits.MAX_PRIVATE_INNER_PLAINTEXT_BYTES) return null
        val obj = parseObject(plaintext) ?: return null
        val shortDescription = when (val field = obj.optionalStringField(FIELD_DESCRIPTION)) {
            OptionalStringField.Absent -> ""
            is OptionalStringField.Present -> field.value
            OptionalStringField.Invalid -> return null
        }
        return runCatching {
            PrivateGroupInvitationInnerPayload(
                title = obj.requiredString(FIELD_TITLE) ?: return null,
                shortDescription = shortDescription,
                invitationCredential = InvitationCredential.of(obj.requiredString(FIELD_CREDENTIAL) ?: return null),
            )
        }.getOrNull()
    }

    fun decode(messageBody: String): GroupInvitationDecodeResult {
        val obj = parseObject(messageBody) ?: return GroupInvitationDecodeResult.NotGroupInvitation
        if (obj.requiredString(FIELD_TYPE) != TYPE_GROUP_INVITATION) {
            return GroupInvitationDecodeResult.NotGroupInvitation
        }
        if (obj.version() != VERSION) return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation

        return when (obj.requiredString(FIELD_AUDIENCE)) {
            AUDIENCE_ROOM -> decodePublic(messageBody, obj)
            AUDIENCE_PARTICIPANT -> decodePrivate(messageBody, obj)
            else -> GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation
        }
    }

    private fun decodePublic(messageBody: String, obj: JsonObject): GroupInvitationDecodeResult {
        if (messageBody.utf8Size() > GroupInvitationLimits.MAX_PUBLIC_WIRE_PAYLOAD_BYTES ||
            obj.hasAny(PRIVATE_ONLY_FIELDS) || obj.containsKey(FIELD_TARGET_PARTICIPANT)
        ) return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation
        val shortDescription = when (val field = obj.optionalStringField(FIELD_DESCRIPTION)) {
            OptionalStringField.Absent -> ""
            is OptionalStringField.Present -> field.value
            OptionalStringField.Invalid -> return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation
        }
        val payload = runCatching {
            PublicGroupInvitationPayload(
                cardId = obj.requiredString(FIELD_CARD_ID) ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
                title = obj.requiredString(FIELD_TITLE) ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
                shortDescription = shortDescription,
                invitationCredential = InvitationCredential.of(
                    obj.requiredString(FIELD_CREDENTIAL) ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
                ),
            )
        }.getOrNull() ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation
        return GroupInvitationDecodeResult.Invitation(GroupInvitationDecodedMessage.PublicInvitation(payload))
    }

    private fun decodePrivate(messageBody: String, obj: JsonObject): GroupInvitationDecodeResult {
        if (messageBody.utf8Size() > GroupInvitationLimits.MAX_PRIVATE_WIRE_PAYLOAD_BYTES ||
            obj.hasAny(PUBLIC_CONTENT_FIELDS)
        ) return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation
        val algorithm = PrivateInvitationEncryptionAlgorithm.entries.firstOrNull {
            it.wireValue == obj.requiredString(FIELD_ALGORITHM)
        } ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation
        val envelope = runCatching {
            PrivateGroupInvitationEnvelope(
                cardId = obj.requiredString(FIELD_CARD_ID) ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
                targetParticipantId = obj.requiredString(FIELD_TARGET_PARTICIPANT)
                    ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
                algorithm = algorithm,
                targetKeyId = obj.requiredString(FIELD_TARGET_KEY_ID)
                    ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
                ephemeralPublicKey = obj.requiredString(FIELD_EPHEMERAL_PUBLIC_KEY)
                    ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
                nonce = obj.requiredString(FIELD_NONCE)
                    ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
                ciphertext = obj.requiredString(FIELD_CIPHERTEXT)
                    ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
            )
        }.getOrNull() ?: return GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation
        return GroupInvitationDecodeResult.Invitation(GroupInvitationDecodedMessage.PrivateEncryptedInvitation(envelope))
    }

    private fun canonicalPayload(maxBytes: Int, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String {
        val encoded = CanonicalJson.canonicalize(buildJsonObject(body))
        require(encoded.utf8Size() <= maxBytes) { "Invitation payload exceeds its wire budget" }
        return encoded
    }

    private fun parseObject(raw: String): JsonObject? = runCatching {
        CanonicalJson.json.decodeFromString(JsonObject.serializer(), raw)
    }.getOrNull()

    private fun JsonObject.requiredString(field: String): String? =
        (get(field) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content

    private fun JsonObject.optionalStringField(field: String): OptionalStringField {
        if (!containsKey(field)) return OptionalStringField.Absent
        val value = get(field) as? JsonPrimitive
        return if (value?.isString == true) {
            OptionalStringField.Present(value.content)
        } else {
            OptionalStringField.Invalid
        }
    }

    private fun JsonObject.version(): Int? {
        val value = get(FIELD_VERSION) as? JsonPrimitive ?: return null
        return VERSION.takeIf { !value.isString && value.content == VERSION.toString() }
    }

    private fun JsonObject.hasAny(fields: Set<String>): Boolean = fields.any(::containsKey)

    private const val FIELD_VERSION = "v"
    private const val FIELD_TYPE = "t"
    private const val FIELD_CARD_ID = "i"
    private const val FIELD_AUDIENCE = "a"
    private const val FIELD_TITLE = "n"
    private const val FIELD_DESCRIPTION = "d"
    private const val FIELD_CREDENTIAL = "c"
    private const val FIELD_TARGET_PARTICIPANT = "to"
    private const val FIELD_ALGORITHM = "alg"
    private const val FIELD_TARGET_KEY_ID = "kid"
    private const val FIELD_EPHEMERAL_PUBLIC_KEY = "epk"
    private const val FIELD_NONCE = "iv"
    private const val FIELD_CIPHERTEXT = "ct"

    private const val TYPE_GROUP_INVITATION = "gi"
    private const val AUDIENCE_ROOM = "r"
    private const val AUDIENCE_PARTICIPANT = "p"

    private val PUBLIC_CONTENT_FIELDS = setOf(FIELD_TITLE, FIELD_DESCRIPTION, FIELD_CREDENTIAL)
    private val PRIVATE_ONLY_FIELDS = setOf(
        FIELD_ALGORITHM,
        FIELD_TARGET_KEY_ID,
        FIELD_EPHEMERAL_PUBLIC_KEY,
        FIELD_NONCE,
        FIELD_CIPHERTEXT,
    )

    private sealed interface OptionalStringField {
        data object Absent : OptionalStringField
        data class Present(val value: String) : OptionalStringField
        data object Invalid : OptionalStringField
    }
}
