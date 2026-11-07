package app.peermesh.moderation

import app.peermesh.core.groupinvitation.GroupInvitationDecodeResult
import app.peermesh.core.groupinvitation.GroupInvitationDecodedMessage
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.data.db.entity.NearbyRoomMessageEntity

internal object NearbyPrivateGroupInvitationReportValidator {
    fun validatedReportedOwnerId(
        message: NearbyRoomMessageEntity,
        reportedPeerId: String,
        localParticipantId: String,
    ): String? {
        if (message.room_id.isBlank() || message.message_id.isBlank()) return null
        if (message.ttl_expires_at_ms <= message.created_at_ms) return null
        if (!GroupInvitationProtocol.isExactGroupInvitationCandidate(message.body)) return null
        val envelope = ((GroupInvitationProtocol.decode(message.body) as? GroupInvitationDecodeResult.Invitation)
            ?.message as? GroupInvitationDecodedMessage.PrivateEncryptedInvitation)
            ?.envelope ?: return null
        if (GroupInvitationProtocol.encodePrivateEnvelope(envelope) != message.body) return null
        if (envelope.cardId != message.message_id) return null
        val ownerId = message.owner_device_id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (ownerId != reportedPeerId) return null
        if (localParticipantId.isBlank() || localParticipantId == ownerId) return null
        if (localParticipantId != envelope.targetParticipantId) return null
        return ownerId
    }
}
