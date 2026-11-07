package app.peermesh.nearby

import app.peermesh.core.groupinvitation.GroupInvitationDecodeResult
import app.peermesh.core.groupinvitation.GroupInvitationDecodedMessage
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.data.db.entity.NearbyRoomMessageEntity

sealed interface NearbyGroupInvitationLocalVisibilityResult {
    data object Updated : NearbyGroupInvitationLocalVisibilityResult
    data object AlreadyInRequestedState : NearbyGroupInvitationLocalVisibilityResult
    data object MessageNotFound : NearbyGroupInvitationLocalVisibilityResult
    data object NotGroupInvitation : NearbyGroupInvitationLocalVisibilityResult
    data object NotAuthorizedViewer : NearbyGroupInvitationLocalVisibilityResult
    data object InvitationInvalid : NearbyGroupInvitationLocalVisibilityResult
}

/** Repository-owned local-only visibility mutation for private group invitations. */
internal class NearbyGroupInvitationLocalVisibilityController {
    fun setHiddenLocally(
        roomId: String,
        messageId: String,
        hidden: Boolean,
        localParticipantId: String,
        messageLoader: (String, String) -> NearbyRoomMessageEntity?,
        hiddenStateUpdater: (String, String, Boolean) -> Int,
    ): NearbyGroupInvitationLocalVisibilityResult {
        if (roomId.isBlank() || messageId.isBlank()) {
            return NearbyGroupInvitationLocalVisibilityResult.MessageNotFound
        }
        val message = messageLoader(roomId, messageId)
            ?: return NearbyGroupInvitationLocalVisibilityResult.MessageNotFound

        when (authorize(message, localParticipantId)) {
            Authorization.Authorized -> Unit
            Authorization.NotGroupInvitation ->
                return NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation
            Authorization.NotAuthorizedViewer ->
                return NearbyGroupInvitationLocalVisibilityResult.NotAuthorizedViewer
            Authorization.InvitationInvalid ->
                return NearbyGroupInvitationLocalVisibilityResult.InvitationInvalid
        }

        if (message.is_hidden_locally == hidden) {
            return NearbyGroupInvitationLocalVisibilityResult.AlreadyInRequestedState
        }

        if (hiddenStateUpdater(roomId, messageId, hidden) == 1) {
            return NearbyGroupInvitationLocalVisibilityResult.Updated
        }

        val current = messageLoader(roomId, messageId)
            ?: return NearbyGroupInvitationLocalVisibilityResult.MessageNotFound
        return if (current.is_hidden_locally == hidden) {
            NearbyGroupInvitationLocalVisibilityResult.AlreadyInRequestedState
        } else {
            NearbyGroupInvitationLocalVisibilityResult.MessageNotFound
        }
    }

    private fun authorize(
        message: NearbyRoomMessageEntity,
        localParticipantId: String,
    ): Authorization {
        if (!GroupInvitationProtocol.isExactGroupInvitationCandidate(message.body)) {
            return Authorization.NotGroupInvitation
        }
        val decoded = when (val result = GroupInvitationProtocol.decode(message.body)) {
            GroupInvitationDecodeResult.NotGroupInvitation -> return Authorization.NotGroupInvitation
            GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation ->
                return Authorization.InvitationInvalid
            is GroupInvitationDecodeResult.Invitation -> result.message
        }
        val envelope = when (decoded) {
            is GroupInvitationDecodedMessage.PublicInvitation ->
                return Authorization.NotGroupInvitation
            is GroupInvitationDecodedMessage.PrivateEncryptedInvitation -> decoded.envelope
        }
        if (GroupInvitationProtocol.encodePrivateEnvelope(envelope) != message.body) {
            return Authorization.InvitationInvalid
        }
        if (
            message.room_id.isBlank() ||
            message.message_id.isBlank() ||
            envelope.cardId != message.message_id
        ) {
            return Authorization.InvitationInvalid
        }
        val logicalSenderId = message.owner_device_id ?: message.sender_device_id
        if (
            logicalSenderId.isBlank() ||
            localParticipantId.isBlank() ||
            message.ttl_expires_at_ms <= message.created_at_ms
        ) {
            return Authorization.InvitationInvalid
        }
        return if (
            localParticipantId == logicalSenderId ||
            localParticipantId == envelope.targetParticipantId
        ) {
            Authorization.Authorized
        } else {
            Authorization.NotAuthorizedViewer
        }
    }

    private enum class Authorization {
        Authorized,
        NotGroupInvitation,
        NotAuthorizedViewer,
        InvitationInvalid,
    }
}
