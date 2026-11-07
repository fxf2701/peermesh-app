package app.peermesh.nearby.presentation

import app.peermesh.nearby.NearbyPublicGroupInvitationPresentation
import app.peermesh.nearby.NearbyPublicGroupInvitationTimelineInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPublicGroupInvitationPresentationMapperGI2E6Test {
    private val mapper = NearbyPublicGroupInvitationPresentationMapper()

    @Test
    fun `valid public result maps to immutable public timeline item`() {
        val projection = mapper.map(
            NearbyPublicGroupInvitationTimelineInspection.Invitation(
                NearbyPublicGroupInvitationPresentation(
                    messageId = "message-1",
                    createdAtMs = 10L,
                    logicalSenderId = "sender-1",
                    senderDisplayName = "Sender",
                    title = "Group title",
                    shortDescription = "Description",
                )
            )
        )
        assertTrue(projection is NearbyPublicGroupInvitationTimelineProjection.Item)
        val item = (projection as NearbyPublicGroupInvitationTimelineProjection.Item).item
        assertEquals("message-1", item.messageId)
        assertEquals("sender-1", item.senderId)
        assertEquals("Sender", item.senderDisplayName)
        assertEquals("Group title", item.title)
        assertEquals("Description", item.shortDescription)
    }

    @Test
    fun `non public messages may continue normal path`() {
        assertEquals(
            NearbyPublicGroupInvitationTimelineProjection.UseNormalMessage,
            mapper.map(NearbyPublicGroupInvitationTimelineInspection.NotGroupInvitation),
        )
        assertEquals(
            NearbyPublicGroupInvitationTimelineProjection.UseNormalMessage,
            mapper.map(NearbyPublicGroupInvitationTimelineInspection.NotPublicInvitation),
        )
    }

    @Test
    fun `hidden public invitation remains as recoverable timeline placeholder`() {
        val projection = mapper.map(
            NearbyPublicGroupInvitationTimelineInspection.HiddenLocally(
                NearbyPublicGroupInvitationPresentation(
                    messageId = "message-hidden",
                    createdAtMs = 20L,
                    logicalSenderId = "sender-hidden",
                    senderDisplayName = "Hidden sender",
                    title = "Hidden title",
                    shortDescription = "Hidden description",
                )
            )
        )
        assertTrue(projection is NearbyPublicGroupInvitationTimelineProjection.HiddenItem)
        val item = (projection as NearbyPublicGroupInvitationTimelineProjection.HiddenItem).item
        assertEquals("message-hidden", item.messageId)
        assertEquals("sender-hidden", item.senderId)
        assertEquals("Hidden sender", item.senderDisplayName)
    }

    @Test
    fun `hidden public invitation stays out of visible room invitation index`() {
        val result = NearbyPublicGroupInvitationTimelineInspection.HiddenLocally(
            NearbyPublicGroupInvitationPresentation(
                messageId = "message-hidden",
                createdAtMs = 20L,
                logicalSenderId = "sender-hidden",
                senderDisplayName = "Hidden sender",
                title = "Hidden title",
                shortDescription = "Hidden description",
            )
        )
        assertEquals(null, mapper.mapForRoomInvitationIndex(result))
    }

    @Test
    fun `invalid structured public content never falls back to normal message`() {
        assertEquals(
            NearbyPublicGroupInvitationTimelineProjection.Omit,
            mapper.map(NearbyPublicGroupInvitationTimelineInspection.UnsupportedOrInvalidInvitation),
        )
        assertEquals(
            NearbyPublicGroupInvitationTimelineProjection.Omit,
            mapper.map(NearbyPublicGroupInvitationTimelineInspection.MessageIdentityMismatch),
        )
    }
}
