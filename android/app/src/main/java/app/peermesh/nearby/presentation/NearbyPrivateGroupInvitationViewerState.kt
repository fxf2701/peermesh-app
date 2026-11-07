package app.peermesh.nearby.presentation

sealed interface NearbyPrivateGroupInvitationViewerState {
    data object Loading : NearbyPrivateGroupInvitationViewerState

    data class Content(
        val senderDisplayName: String,
        val title: String,
        val description: String,
        val showRecipientOnlyHint: Boolean,
    ) : NearbyPrivateGroupInvitationViewerState {
        init {
            require(senderDisplayName.isNotBlank())
            require(title.isNotBlank())
        }
    }

    data object Unavailable : NearbyPrivateGroupInvitationViewerState
}

internal class NearbyPrivateGroupInvitationViewerMapper {
    fun map(projection: NearbyPrivateGroupInvitationTimelineProjection): NearbyPrivateGroupInvitationViewerState =
        when (projection) {
            is NearbyPrivateGroupInvitationTimelineProjection.Item -> when (val item = projection.item) {
                is NearbyRoomTimelineItem.PrivateInvitation -> NearbyPrivateGroupInvitationViewerState.Content(
                    senderDisplayName = item.senderDisplayName,
                    title = item.title,
                    description = item.shortDescription,
                    showRecipientOnlyHint =
                        item.audienceLabel == PrivateInvitationAudienceLabel.RECEIVED_PRIVATELY,
                )
                is NearbyRoomTimelineItem.HiddenPrivateInvitation,
                is NearbyRoomTimelineItem.PrivateInvitationPlaceholder,
                is NearbyRoomTimelineItem.PublicInvitation,
                is NearbyRoomTimelineItem.HiddenPublicInvitation,
                is NearbyRoomTimelineItem.NormalMessage -> NearbyPrivateGroupInvitationViewerState.Unavailable
            }
            NearbyPrivateGroupInvitationTimelineProjection.Omit,
            NearbyPrivateGroupInvitationTimelineProjection.UseNormalMessage -> NearbyPrivateGroupInvitationViewerState.Unavailable
        }
}
