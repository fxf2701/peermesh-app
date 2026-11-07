package app.peermesh.nearby.presentation

import app.peermesh.data.db.entity.NearbyRoomMessageEntity

/** Immutable presentation items consumed by the Nearby Room timeline renderer. */
sealed interface NearbyRoomTimelineItem {
    val messageId: String
    val createdAtMs: Long

    /** Compatibility wrapper for existing non-private-invitation message rendering. */
    data class NormalMessage(
        val source: NearbyRoomMessageEntity,
    ) : NearbyRoomTimelineItem {
        override val messageId: String = source.message_id
        override val createdAtMs: Long = source.created_at_ms
    }

    data class PublicInvitation(
        override val messageId: String,
        override val createdAtMs: Long,
        val senderId: String,
        val senderDisplayName: String,
        val title: String,
        val shortDescription: String,
    ) : NearbyRoomTimelineItem

    data class HiddenPublicInvitation(
        override val messageId: String,
        override val createdAtMs: Long,
        val senderId: String,
        val senderDisplayName: String,
    ) : NearbyRoomTimelineItem

    data class PrivateInvitation(
        override val messageId: String,
        override val createdAtMs: Long,
        val senderId: String,
        val senderDisplayName: String,
        val title: String,
        val shortDescription: String,
        val audienceLabel: PrivateInvitationAudienceLabel,
        val actions: Set<PrivateInvitationAction>,
    ) : NearbyRoomTimelineItem

    data class HiddenPrivateInvitation(
        override val messageId: String,
        override val createdAtMs: Long,
        val senderId: String,
        val senderDisplayName: String,
        val actions: Set<PrivateInvitationAction>,
    ) : NearbyRoomTimelineItem

    data class PrivateInvitationPlaceholder(
        override val messageId: String,
        override val createdAtMs: Long,
        val senderId: String,
        val senderDisplayName: String,
        val kind: PrivateInvitationPlaceholderKind,
        val actions: Set<PrivateInvitationAction>,
    ) : NearbyRoomTimelineItem
}

enum class PrivateInvitationAudienceLabel {
    SENT_PRIVATELY,
    RECEIVED_PRIVATELY,
}

enum class PrivateInvitationAction {
    OPEN,
    HIDE,
    UNHIDE,
    REPORT_INVITATION,
    REPORT_PEER,
    BLOCK_PEER,
    MUTE_PEER,
}

enum class PrivateInvitationPlaceholderKind {
    UNAVAILABLE,
    UNSUPPORTED,
    INVALID,
}

enum class PrivateInvitationAuthorizedAudience {
    LOCAL_SENDER,
    INTENDED_RECIPIENT,
}

/**
 * Non-sensitive, Repository-authoritative context supplied to the pure presentation mapper.
 *
 * The presentation layer must not reconstruct this context from protocol bodies.
 */
data class NearbyPrivateGroupInvitationPresentationContext(
    val messageId: String,
    val createdAtMs: Long,
    val isHiddenLocally: Boolean,
    val audience: PrivateInvitationAuthorizedAudience,
    val senderId: String,
    val senderDisplayName: String,
    val canMutePeer: Boolean,
) {
    init {
        require(messageId.isNotBlank())
        require(senderId.isNotBlank())
        require(senderDisplayName.isNotBlank())
    }
}

/** Explicit projection outcome; expected omission is not represented by an exception. */
sealed interface NearbyPrivateGroupInvitationTimelineProjection {
    data class Item(
        val item: NearbyRoomTimelineItem,
    ) : NearbyPrivateGroupInvitationTimelineProjection

    data object UseNormalMessage : NearbyPrivateGroupInvitationTimelineProjection
    data object Omit : NearbyPrivateGroupInvitationTimelineProjection
}
