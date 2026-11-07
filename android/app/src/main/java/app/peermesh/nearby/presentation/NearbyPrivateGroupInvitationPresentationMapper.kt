package app.peermesh.nearby.presentation

import app.peermesh.nearby.NearbyPrivateGroupInvitationReceiveResult

/** Pure, side-effect-free mapping from typed Repository results to immutable timeline state. */
internal class NearbyPrivateGroupInvitationPresentationMapper {
    fun map(
        context: NearbyPrivateGroupInvitationPresentationContext?,
        result: NearbyPrivateGroupInvitationReceiveResult,
    ): NearbyPrivateGroupInvitationTimelineProjection {
        return when (result) {
        NearbyPrivateGroupInvitationReceiveResult.MessageNotFound,
        NearbyPrivateGroupInvitationReceiveResult.NotForLocalParticipant,
        NearbyPrivateGroupInvitationReceiveResult.Expired -> NearbyPrivateGroupInvitationTimelineProjection.Omit

        NearbyPrivateGroupInvitationReceiveResult.NotGroupInvitation,
        NearbyPrivateGroupInvitationReceiveResult.NotPrivateInvitation -> NearbyPrivateGroupInvitationTimelineProjection.UseNormalMessage

        is NearbyPrivateGroupInvitationReceiveResult.Invitation -> {
            val authoritativeContext = context
                ?: return NearbyPrivateGroupInvitationTimelineProjection.Omit
            mapInvitation(authoritativeContext, result)
        }

        NearbyPrivateGroupInvitationReceiveResult.RecipientIdentityUnavailable,
        NearbyPrivateGroupInvitationReceiveResult.RecipientIdentityInvalidated,
        NearbyPrivateGroupInvitationReceiveResult.UnsupportedPlatform,
        NearbyPrivateGroupInvitationReceiveResult.CryptoUnavailable,
        NearbyPrivateGroupInvitationReceiveResult.KeyAgreementFailed -> mapPlaceholder(context, PrivateInvitationPlaceholderKind.UNAVAILABLE)

        NearbyPrivateGroupInvitationReceiveResult.UnsupportedCryptoProtocol,
        NearbyPrivateGroupInvitationReceiveResult.UnsupportedCryptoAlgorithm -> mapPlaceholder(context, PrivateInvitationPlaceholderKind.UNSUPPORTED)

        NearbyPrivateGroupInvitationReceiveResult.UnsupportedOrInvalidEnvelope,
        NearbyPrivateGroupInvitationReceiveResult.MessageIdentityMismatch,
        NearbyPrivateGroupInvitationReceiveResult.WrongRecipientKey,
        NearbyPrivateGroupInvitationReceiveResult.AuthenticationFailed,
        NearbyPrivateGroupInvitationReceiveResult.InvalidEncryptedPayload,
        NearbyPrivateGroupInvitationReceiveResult.InvalidCryptoContext,
        NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload -> mapPlaceholder(context, PrivateInvitationPlaceholderKind.INVALID)
        }
    }

    private fun mapInvitation(
        context: NearbyPrivateGroupInvitationPresentationContext,
        result: NearbyPrivateGroupInvitationReceiveResult.Invitation,
    ): NearbyPrivateGroupInvitationTimelineProjection {
        val invitation = result.invitation
        if (
            context.messageId != invitation.messageId ||
            context.createdAtMs != invitation.createdAtMs ||
            context.senderId != invitation.logicalSenderId
        ) {
            return mapPlaceholder(context, PrivateInvitationPlaceholderKind.INVALID)
        }
        if (
            invitation.title.isBlank() ||
            invitation.title.length > MAX_TITLE_CHARS ||
            invitation.shortDescription.length > MAX_DESCRIPTION_CHARS
        ) {
            return mapPlaceholder(context, PrivateInvitationPlaceholderKind.INVALID)
        }

        val actions = actionsFor(
            audience = context.audience,
            hidden = context.isHiddenLocally,
            canMutePeer = context.canMutePeer,
        )
        val item = if (context.isHiddenLocally) {
            NearbyRoomTimelineItem.HiddenPrivateInvitation(
                messageId = invitation.messageId,
                createdAtMs = invitation.createdAtMs,
                senderId = invitation.logicalSenderId,
                senderDisplayName = context.senderDisplayName,
                actions = actions,
            )
        } else {
            NearbyRoomTimelineItem.PrivateInvitation(
                messageId = invitation.messageId,
                createdAtMs = invitation.createdAtMs,
                senderId = invitation.logicalSenderId,
                senderDisplayName = context.senderDisplayName,
                title = invitation.title,
                shortDescription = invitation.shortDescription,
                audienceLabel = when (context.audience) {
                    PrivateInvitationAuthorizedAudience.LOCAL_SENDER ->
                        PrivateInvitationAudienceLabel.SENT_PRIVATELY
                    PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT ->
                        PrivateInvitationAudienceLabel.RECEIVED_PRIVATELY
                },
                actions = actions,
            )
        }
        return NearbyPrivateGroupInvitationTimelineProjection.Item(item)
    }

    private fun mapPlaceholder(
        context: NearbyPrivateGroupInvitationPresentationContext?,
        kind: PrivateInvitationPlaceholderKind,
    ): NearbyPrivateGroupInvitationTimelineProjection {
        val authoritativeContext = context
            ?: return NearbyPrivateGroupInvitationTimelineProjection.Omit
        return NearbyPrivateGroupInvitationTimelineProjection.Item(
            NearbyRoomTimelineItem.PrivateInvitationPlaceholder(
                messageId = authoritativeContext.messageId,
                createdAtMs = authoritativeContext.createdAtMs,
                senderId = authoritativeContext.senderId,
                senderDisplayName = authoritativeContext.senderDisplayName,
                kind = kind,
                actions = placeholderActionsFor(
                    audience = authoritativeContext.audience,
                    canMutePeer = authoritativeContext.canMutePeer,
                ),
            )
        )
    }

    private fun actionsFor(
        audience: PrivateInvitationAuthorizedAudience,
        hidden: Boolean,
        canMutePeer: Boolean,
    ): Set<PrivateInvitationAction> = when (audience) {
        PrivateInvitationAuthorizedAudience.LOCAL_SENDER -> if (hidden) {
            setOf(PrivateInvitationAction.UNHIDE)
        } else {
            setOf(
                PrivateInvitationAction.OPEN,
                PrivateInvitationAction.HIDE,
            )
        }

        PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT -> buildSet {
            add(if (hidden) PrivateInvitationAction.UNHIDE else PrivateInvitationAction.OPEN)
            if (!hidden) add(PrivateInvitationAction.HIDE)
            add(PrivateInvitationAction.REPORT_INVITATION)
            add(PrivateInvitationAction.REPORT_PEER)
            add(PrivateInvitationAction.BLOCK_PEER)
            if (canMutePeer) add(PrivateInvitationAction.MUTE_PEER)
        }
    }

    private fun placeholderActionsFor(
        audience: PrivateInvitationAuthorizedAudience,
        canMutePeer: Boolean,
    ): Set<PrivateInvitationAction> = when (audience) {
        PrivateInvitationAuthorizedAudience.LOCAL_SENDER -> emptySet()
        PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT -> buildSet {
            add(PrivateInvitationAction.REPORT_INVITATION)
            add(PrivateInvitationAction.REPORT_PEER)
            add(PrivateInvitationAction.BLOCK_PEER)
            if (canMutePeer) add(PrivateInvitationAction.MUTE_PEER)
        }
    }

    private companion object {
        const val MAX_TITLE_CHARS = 40
        const val MAX_DESCRIPTION_CHARS = 60
    }
}
