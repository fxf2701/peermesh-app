package app.peermesh.core.groupinvitation

/** Reusable local presentation data owned by exactly one private group. */
class InvitationTemplate(
    val groupId: String,
    val title: String,
    val shortDescription: String,
    val icon: String? = null,
    presentationOptions: Set<String> = emptySet(),
) {
    /** Snapshot the caller-owned collection so later mutations cannot alter this template. */
    val presentationOptions: Set<String> = presentationOptions.toSet()

    init {
        GroupInvitationPolicy.requireIdentifier(groupId, "Group id")
        GroupInvitationPolicy.requirePresentation(title, shortDescription, icon)
        require(this.presentationOptions.size <= GroupInvitationLimits.MAX_PRESENTATION_OPTIONS) {
            "Too many invitation presentation options"
        }
        require(this.presentationOptions.all(GroupInvitationPolicy::isValidPresentationOption)) {
            "Invalid invitation presentation option"
        }
    }
}

sealed interface InvitationAudience {
    data object Room : InvitationAudience

    data class Participant(val participantId: String) : InvitationAudience {
        init {
            GroupInvitationPolicy.requireParticipantId(participantId)
        }
    }
}

/**
 * Join material. It is intentionally redacted from diagnostic string output and only exposes its
 * raw value to code in this module at controlled protocol boundaries.
 */
class InvitationCredential private constructor(
    internal val rawValue: String,
) {
    override fun equals(other: Any?): Boolean =
        other is InvitationCredential && rawValue == other.rawValue

    override fun hashCode(): Int = rawValue.hashCode()

    override fun toString(): String = "[REDACTED]"

    companion object {
        fun of(rawValue: String): InvitationCredential {
            require(rawValue.isNotBlank()) { "Invitation credential must not be blank" }
            require(rawValue.utf8Size() <= GroupInvitationLimits.MAX_CREDENTIAL_UTF8_BYTES) {
                "Invitation credential is too large"
            }
            return InvitationCredential(rawValue)
        }
    }
}

/** An immutable snapshot created for every share and bounded by its Nearby Room expiry. */
data class InvitationCardInstance(
    val cardId: String,
    val groupId: String,
    val roomId: String,
    val title: String,
    val shortDescription: String,
    val icon: String?,
    val audience: InvitationAudience,
    val invitationCredential: InvitationCredential,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    init {
        GroupInvitationPolicy.requireCardId(cardId)
        GroupInvitationPolicy.requireIdentifier(groupId, "Group id")
        GroupInvitationPolicy.requireIdentifier(roomId, "Room id")
        GroupInvitationPolicy.requirePresentation(title, shortDescription, icon)
        require(createdAtMs >= 0L) { "Creation time must not be negative" }
        require(expiresAtMs > createdAtMs) { "Card must expire after it is created" }
    }

    fun isActive(roomIsActive: Boolean, nowMs: Long): Boolean = roomIsActive && nowMs < expiresAtMs

    companion object {
        fun fromTemplate(
            cardId: String,
            template: InvitationTemplate,
            roomId: String,
            audience: InvitationAudience,
            invitationCredential: InvitationCredential,
            createdAtMs: Long,
            roomExpiresAtMs: Long,
        ): InvitationCardInstance = InvitationCardInstance(
            cardId = cardId,
            groupId = template.groupId,
            roomId = roomId,
            title = template.title,
            shortDescription = template.shortDescription,
            icon = template.icon,
            audience = audience,
            invitationCredential = invitationCredential,
            createdAtMs = createdAtMs,
            expiresAtMs = roomExpiresAtMs,
        )
    }
}

object GroupInvitationPolicy {
    fun isVisibleTo(
        audience: InvitationAudience,
        senderParticipantId: String,
        viewerParticipantId: String,
    ): Boolean = when (audience) {
        InvitationAudience.Room -> true
        is InvitationAudience.Participant ->
            viewerParticipantId == senderParticipantId || viewerParticipantId == audience.participantId
    }

    internal fun requireCardId(value: String) = requireBoundedUtf8(value, "Card id", GroupInvitationLimits.MAX_CARD_ID_UTF8_BYTES)

    internal fun requireParticipantId(value: String) =
        requireBoundedUtf8(value, "Participant id", GroupInvitationLimits.MAX_PARTICIPANT_ID_UTF8_BYTES)

    internal fun requireIdentifier(value: String, label: String) =
        requireBoundedUtf8(value, label, GroupInvitationLimits.MAX_IDENTIFIER_UTF8_BYTES)

    internal fun requirePresentation(title: String, description: String, icon: String?) {
        require(title.isNotBlank() && title.length <= GroupInvitationLimits.MAX_TITLE_CHARS) {
            "Invalid invitation title"
        }
        require(title.utf8Size() <= GroupInvitationLimits.MAX_TITLE_UTF8_BYTES) {
            "Invitation title is too large"
        }
        require(description.length <= GroupInvitationLimits.MAX_DESCRIPTION_CHARS) {
            "Invitation description is too long"
        }
        require(description.utf8Size() <= GroupInvitationLimits.MAX_DESCRIPTION_UTF8_BYTES) {
            "Invitation description is too large"
        }
        require(icon == null || (icon.isNotBlank() && icon.utf8Size() <= GroupInvitationLimits.MAX_ICON_UTF8_BYTES)) {
            "Invalid invitation icon"
        }
    }

    internal fun isValidPresentationOption(value: String): Boolean =
        value.isNotBlank() &&
            value.utf8Size() <= GroupInvitationLimits.MAX_PRESENTATION_OPTION_UTF8_BYTES &&
            value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun requireBoundedUtf8(value: String, label: String, maxBytes: Int) {
        require(value.isNotBlank()) { "$label must not be blank" }
        require(value.utf8Size() <= maxBytes) { "$label is too large" }
    }
}

object GroupInvitationLimits {
    const val MAX_IDENTIFIER_UTF8_BYTES = 128
    const val MAX_CARD_ID_UTF8_BYTES = 128
    const val MAX_PARTICIPANT_ID_UTF8_BYTES = 128
    const val MAX_TARGET_KEY_ID_UTF8_BYTES = 128
    const val MAX_TITLE_CHARS = 80
    const val MAX_DESCRIPTION_CHARS = 240
    const val MAX_TITLE_UTF8_BYTES = 240
    const val MAX_DESCRIPTION_UTF8_BYTES = 480
    const val MAX_CREDENTIAL_UTF8_BYTES = 512
    const val MAX_ICON_UTF8_BYTES = 64
    const val MAX_PRESENTATION_OPTIONS = 8
    const val MAX_PRESENTATION_OPTION_UTF8_BYTES = 32

    /** P-256 uncompressed SEC1 point: 65 bytes encoded as unpadded Base64URL (87 chars). */
    const val P256_UNCOMPRESSED_PUBLIC_KEY_BYTES = 65
    const val P256_UNCOMPRESSED_PUBLIC_KEY_BASE64URL_CHARS = 87
    /** AES-GCM's 96-bit nonce: 12 bytes encoded as unpadded Base64URL (16 chars). */
    const val AES_GCM_NONCE_BYTES = 12
    const val AES_GCM_NONCE_BASE64URL_CHARS = 16
    const val AES_GCM_TAG_BYTES = 16
    const val MAX_PRIVATE_CIPHERTEXT_BASE64URL_CHARS = 1_728

    const val MAX_PUBLIC_WIRE_PAYLOAD_BYTES = 1_536
    const val MAX_PRIVATE_INNER_PLAINTEXT_BYTES = 1_280
    const val MAX_PRIVATE_WIRE_PAYLOAD_BYTES = 2_400
}

internal fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
