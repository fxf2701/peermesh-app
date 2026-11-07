package app.peermesh.nearby.presentation

sealed interface NearbyPublicGroupInvitationViewerState {
    data object Loading : NearbyPublicGroupInvitationViewerState

    data class Content(
        val messageId: String,
        val createdAtMs: Long,
        val senderId: String,
        val senderDisplayName: String,
        val title: String,
        val description: String,
    ) : NearbyPublicGroupInvitationViewerState {
        init {
            require(senderDisplayName.isNotBlank())
            require(title.isNotBlank())
        }
    }

    data object Unavailable : NearbyPublicGroupInvitationViewerState
}

internal class NearbyPublicGroupInvitationViewerMapper {
    fun map(projection: NearbyPublicGroupInvitationTimelineProjection): NearbyPublicGroupInvitationViewerState =
        when (projection) {
            is NearbyPublicGroupInvitationTimelineProjection.Item -> {
                val item = projection.item
                NearbyPublicGroupInvitationViewerState.Content(
                    messageId = item.messageId,
                    createdAtMs = item.createdAtMs,
                    senderId = item.senderId,
                    senderDisplayName = item.senderDisplayName,
                    title = item.title,
                    description = item.shortDescription,
                )
            }
            is NearbyPublicGroupInvitationTimelineProjection.HiddenItem ->
                NearbyPublicGroupInvitationViewerState.Unavailable
            NearbyPublicGroupInvitationTimelineProjection.Omit,
            NearbyPublicGroupInvitationTimelineProjection.UseNormalMessage ->
                NearbyPublicGroupInvitationViewerState.Unavailable
        }
}
