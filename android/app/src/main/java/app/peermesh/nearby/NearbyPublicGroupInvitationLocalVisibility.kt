package app.peermesh.nearby

import app.peermesh.core.groupinvitation.GroupInvitationDecodeResult
import app.peermesh.core.groupinvitation.GroupInvitationDecodedMessage
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.data.db.entity.NearbyRoomMessageEntity

/** Repository-owned local-only visibility mutation for canonical public group invitations. */
internal class NearbyPublicGroupInvitationLocalVisibilityController {
    fun setHiddenLocally(
        roomId: String,
        messageId: String,
        hidden: Boolean,
        messageLoader: (String, String) -> NearbyRoomMessageEntity?,
        hiddenStateUpdater: (String, String, Boolean) -> Int,
    ): NearbyGroupInvitationLocalVisibilityResult {
        if (roomId.isBlank() || messageId.isBlank()) {
            return NearbyGroupInvitationLocalVisibilityResult.MessageNotFound
        }
        val message = messageLoader(roomId, messageId)
            ?: return NearbyGroupInvitationLocalVisibilityResult.MessageNotFound

        if (!GroupInvitationProtocol.isExactGroupInvitationCandidate(message.body)) {
            return NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation
        }
        val decoded = when (val result = GroupInvitationProtocol.decode(message.body)) {
            GroupInvitationDecodeResult.NotGroupInvitation ->
                return NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation
            GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation ->
                return NearbyGroupInvitationLocalVisibilityResult.InvitationInvalid
            is GroupInvitationDecodeResult.Invitation -> result.message
        }
        val payload = (decoded as? GroupInvitationDecodedMessage.PublicInvitation)?.payload
            ?: return NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation
        if (
            payload.cardId != message.message_id ||
            runCatching { GroupInvitationProtocol.encodePublic(payload) }.getOrNull() != message.body
        ) {
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
}
