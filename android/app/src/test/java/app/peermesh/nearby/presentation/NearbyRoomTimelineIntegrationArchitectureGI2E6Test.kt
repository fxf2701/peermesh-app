package app.peermesh.nearby.presentation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRoomTimelineIntegrationArchitectureGI2E6Test {
    @Test fun `adapter renders immutable timeline items and does not parse invitation protocol`() {
        val source = source("app/peermesh/NearbyRoomMessageAdapter.kt")
        assertTrue(source.contains("List<NearbyRoomTimelineItem>"))
        assertTrue(source.contains("NearbyRoomTimelineItem.PrivateInvitation"))
        assertFalse(source.contains("GroupInvitationProtocol"))
        assertFalse(source.contains("local_render_source_body"))
        assertFalse(source.contains("JcaPrivateRecipientCrypto"))
    }

    @Test fun `activity obtains private invitation context only through repository timeline inspection`() {
        val source = source("app/peermesh/NearbyRoomActivity.kt")
        assertTrue(source.contains("inspectPrivateGroupInvitationForTimeline"))
        assertTrue(source.contains("NearbyPrivateGroupInvitationPresentationMapper"))
        assertFalse(source.contains("GroupInvitationProtocol.decode(message.body)"))
        assertFalse(source.contains("local_render_source_body"))
    }

    @Test fun `private invitation report seam accepts identity but not entity or content`() {
        val source = source("app/peermesh/moderation/ModerationRepository.kt")
        val start = source.indexOf("fun createNearbyRoomPrivateInvitationReport(")
        val end = source.indexOf("fun createNearbyRoomMessageReport(", start)
        val method = source.substring(start, end)
        assertTrue(method.contains("messageId: String"))
        assertTrue(method.contains("reportedPeerId: String"))
        assertFalse(method.contains("message: NearbyRoomMessageEntity"))
        assertFalse(method.contains("includePlaintext: Boolean"))
        assertFalse(method.contains("plaintextExcerpt = message.body"))
    }

    @Test fun `private invitation layouts do not expose expiry or credential fields`() {
        val invitation = File("src/main/res/layout/item_nearby_room_private_invitation.xml").readText()
        val placeholder = File("src/main/res/layout/item_nearby_room_invitation_placeholder.xml").readText()
        val combined = invitation + placeholder
        assertFalse(combined.contains("credential", ignoreCase = true))
        assertFalse(combined.contains("expiry", ignoreCase = true))
        assertTrue(invitation.contains("private_invitation_title"))
        assertTrue(placeholder.contains("private_invitation_placeholder_text"))
    }

    @Test fun `timeline and report authority use persisted owner without relay fallback`() {
        val repository = source("app/peermesh/nearby/NearbyRoomRepository.kt")
        val moderation = source("app/peermesh/moderation/ModerationRepository.kt")
        assertTrue(repository.contains("authoritativeOwnerId(message)"))
        assertFalse(repository.contains("message.owner_device_id ?: message.sender_device_id"))
        assertFalse(repository.contains("?: message.sender_display_name"))
        assertTrue(moderation.contains("NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId"))
    }

    @Test fun `private invitation report validator requires canonical envelope`() {
        val source = source("app/peermesh/moderation/NearbyPrivateGroupInvitationReportValidator.kt")
        assertTrue(source.contains("encodePrivateEnvelope(envelope) != message.body"))
        assertTrue(source.contains("message.owner_device_id"))
        assertFalse(source.contains("message.sender_device_id"))
    }

    private fun source(relative: String): String = File("src/main/java", relative).readText()
}
