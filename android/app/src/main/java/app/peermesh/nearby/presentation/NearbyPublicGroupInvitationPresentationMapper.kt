package app.peermesh.nearby.presentation

import app.peermesh.nearby.NearbyPublicGroupInvitationTimelineInspection

/** Pure public-invitation projection. It never receives raw protocol content or credentials. */
internal class NearbyPublicGroupInvitationPresentationMapper {
    fun map(result: NearbyPublicGroupInvitationTimelineInspection): NearbyPublicGroupInvitationTimelineProjection =
        when (result) {
            is NearbyPublicGroupInvitationTimelineInspection.Invitation -> {
                val presentation = result.presentation
                NearbyPublicGroupInvitationTimelineProjection.Item(
                    NearbyRoomTimelineItem.PublicInvitation(
                        messageId = presentation.messageId,
                        createdAtMs = presentation.createdAtMs,
                        senderId = presentation.logicalSenderId,
                        senderDisplayName = presentation.senderDisplayName,
                        title = presentation.title,
                        shortDescription = presentation.shortDescription,
                    )
                )
            }
            NearbyPublicGroupInvitationTimelineInspection.NotGroupInvitation,
            NearbyPublicGroupInvitationTimelineInspection.NotPublicInvitation ->
                NearbyPublicGroupInvitationTimelineProjection.UseNormalMessage
            is NearbyPublicGroupInvitationTimelineInspection.HiddenLocally -> {
                val presentation = result.presentation
                NearbyPublicGroupInvitationTimelineProjection.HiddenItem(
                    NearbyRoomTimelineItem.HiddenPublicInvitation(
                        messageId = presentation.messageId,
                        createdAtMs = presentation.createdAtMs,
                        senderId = presentation.logicalSenderId,
                        senderDisplayName = presentation.senderDisplayName,
                    )
                )
            }
            NearbyPublicGroupInvitationTimelineInspection.MessageNotFound,
            NearbyPublicGroupInvitationTimelineInspection.UnsupportedOrInvalidInvitation,
            NearbyPublicGroupInvitationTimelineInspection.Expired,
            NearbyPublicGroupInvitationTimelineInspection.MessageIdentityMismatch ->
                NearbyPublicGroupInvitationTimelineProjection.Omit
        }

    fun mapForRoomInvitationIndex(
        result: NearbyPublicGroupInvitationTimelineInspection,
    ): NearbyRoomTimelineItem.PublicInvitation? = when (result) {
        is NearbyPublicGroupInvitationTimelineInspection.Invitation -> result.presentation.toTimelineItem()
        is NearbyPublicGroupInvitationTimelineInspection.HiddenLocally,
        NearbyPublicGroupInvitationTimelineInspection.MessageNotFound,
        NearbyPublicGroupInvitationTimelineInspection.NotGroupInvitation,
        NearbyPublicGroupInvitationTimelineInspection.NotPublicInvitation,
        NearbyPublicGroupInvitationTimelineInspection.UnsupportedOrInvalidInvitation,
        NearbyPublicGroupInvitationTimelineInspection.Expired,
        NearbyPublicGroupInvitationTimelineInspection.MessageIdentityMismatch -> null
    }

    private fun app.peermesh.nearby.NearbyPublicGroupInvitationPresentation.toTimelineItem() =
        NearbyRoomTimelineItem.PublicInvitation(
            messageId = messageId,
            createdAtMs = createdAtMs,
            senderId = logicalSenderId,
            senderDisplayName = senderDisplayName,
            title = title,
            shortDescription = shortDescription,
        )
}

sealed interface NearbyPublicGroupInvitationTimelineProjection {
    data class Item(
        val item: NearbyRoomTimelineItem.PublicInvitation,
    ) : NearbyPublicGroupInvitationTimelineProjection

    data class HiddenItem(
        val item: NearbyRoomTimelineItem.HiddenPublicInvitation,
    ) : NearbyPublicGroupInvitationTimelineProjection

    data object UseNormalMessage : NearbyPublicGroupInvitationTimelineProjection
    data object Omit : NearbyPublicGroupInvitationTimelineProjection
}
