package app.peermesh.nearby.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NearbyPrivateGroupInvitationViewerMapperGI2E6Test {
    private val mapper = NearbyPrivateGroupInvitationViewerMapper()

    @Test
    fun recipientInvitation_mapsSenderHintTitleAndDescription() {
        val state = mapper.map(
            NearbyPrivateGroupInvitationTimelineProjection.Item(
                NearbyRoomTimelineItem.PrivateInvitation(
                    messageId = "message-1",
                    createdAtMs = 10L,
                    senderId = "owner-1",
                    senderDisplayName = "Minh",
                    title = "Rescue Team",
                    shortDescription = "Meet at the north gate",
                    audienceLabel = PrivateInvitationAudienceLabel.RECEIVED_PRIVATELY,
                    actions = setOf(PrivateInvitationAction.OPEN),
                )
            )
        )
        assertEquals(
            NearbyPrivateGroupInvitationViewerState.Content(
                senderDisplayName = "Minh",
                title = "Rescue Team",
                description = "Meet at the north gate",
                showRecipientOnlyHint = true,
            ),
            state,
        )
    }

    @Test
    fun senderInvitation_doesNotShowRecipientOnlyHint() {
        val state = mapper.map(
            NearbyPrivateGroupInvitationTimelineProjection.Item(
                NearbyRoomTimelineItem.PrivateInvitation(
                    messageId = "message-2",
                    createdAtMs = 11L,
                    senderId = "owner-1",
                    senderDisplayName = "Minh",
                    title = "Rescue Team",
                    shortDescription = "Meet at the north gate",
                    audienceLabel = PrivateInvitationAudienceLabel.SENT_PRIVATELY,
                    actions = setOf(PrivateInvitationAction.OPEN),
                )
            )
        )
        assertEquals(
            NearbyPrivateGroupInvitationViewerState.Content(
                senderDisplayName = "Minh",
                title = "Rescue Team",
                description = "Meet at the north gate",
                showRecipientOnlyHint = false,
            ),
            state,
        )
    }

    @Test fun hiddenInvitation_isUnavailable() = assertSame(
        NearbyPrivateGroupInvitationViewerState.Unavailable,
        mapper.map(NearbyPrivateGroupInvitationTimelineProjection.Item(
            NearbyRoomTimelineItem.HiddenPrivateInvitation("m", 1L, "s", "Sender", emptySet())
        ))
    )

    @Test fun failurePlaceholder_isUnavailable() = assertSame(
        NearbyPrivateGroupInvitationViewerState.Unavailable,
        mapper.map(NearbyPrivateGroupInvitationTimelineProjection.Item(
            NearbyRoomTimelineItem.PrivateInvitationPlaceholder("m", 1L, "s", "Sender", PrivateInvitationPlaceholderKind.INVALID, emptySet())
        ))
    )

    @Test fun omission_isUnavailable() = assertSame(
        NearbyPrivateGroupInvitationViewerState.Unavailable,
        mapper.map(NearbyPrivateGroupInvitationTimelineProjection.Omit)
    )
}
