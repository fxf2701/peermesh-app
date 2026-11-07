package app.peermesh.nearby

import app.peermesh.data.db.entity.NearbyRoomMessageEntity

sealed interface NearbyGroupInvitationAudience {
    data object Public : NearbyGroupInvitationAudience

    data class Private(val targetParticipantId: String) : NearbyGroupInvitationAudience {
        init {
            require(targetParticipantId.isNotBlank()) { "Private target participant is required" }
        }
    }
}

/**
 * Opaque repository-issued capability. Implementations are private to [NearbyRoomRepository], so
 * callers can consume frozen context but cannot mint a capability accepted by submission.
 */
interface NearbyGroupInvitationPreparation {
    val roomId: String
    val messageId: String
    val logicalSenderId: String
    val senderDisplayName: String?
    val createdAtMs: Long
    val effectiveExpiryAtMs: Long
    val audience: NearbyGroupInvitationAudience
    val recipientEncryptionIdentity: NearbyRecipientEncryptionPublicIdentity?
}

sealed interface PreparedGroupInvitationInsertResult {
    data class Inserted(val message: NearbyRoomMessageEntity) : PreparedGroupInvitationInsertResult
    data object InvalidPreparation : PreparedGroupInvitationInsertResult
    data object InvalidPreparedContent : PreparedGroupInvitationInsertResult
    data object StalePreparation : PreparedGroupInvitationInsertResult
    data object RoomUnavailable : PreparedGroupInvitationInsertResult
    data object SenderMismatch : PreparedGroupInvitationInsertResult
    data object SenderMuted : PreparedGroupInvitationInsertResult
    data object SendLimitReached : PreparedGroupInvitationInsertResult
    data object MessageIdCollision : PreparedGroupInvitationInsertResult
}
