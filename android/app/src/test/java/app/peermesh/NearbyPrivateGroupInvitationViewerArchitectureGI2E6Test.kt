package app.peermesh

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPrivateGroupInvitationViewerArchitectureGI2E6Test {
    @Test
    fun viewerUsesIdentityOnlyAndDoesNotParseOrDecrypt() {
        val source = File("src/main/java/app/peermesh/NearbyPrivateGroupInvitationViewerActivity.kt").readText()
        assertTrue(source.contains("EXTRA_ROOM_ID"))
        assertTrue(source.contains("EXTRA_MESSAGE_ID"))
        assertTrue(source.contains("inspectPrivateGroupInvitationForTimeline"))
        assertFalse(source.contains("GroupInvitationProtocol"))
        assertFalse(source.contains("GI2D"))
        assertFalse(source.contains("NearbyRoomMessageEntity"))
        assertFalse(source.contains("InvitationCredential"))
        assertFalse(source.contains("expiresAt"))
        assertTrue(source.contains("!isFinishing && !isDestroyed"))
        assertFalse(source.contains("content.contentDescription ="))
    }

    @Test
    fun viewerLayoutContainsSenderRecipientHintTitleDescriptionOnly() {
        val layout = File("src/main/res/layout/activity_nearby_private_group_invitation_viewer.xml").readText()
        assertTrue(layout.contains("private_invitation_viewer_sender"))
        assertTrue(layout.contains("private_invitation_viewer_recipient_only_hint"))
        assertTrue(layout.contains("private_invitation_viewer_invitation_title"))
        assertTrue(layout.contains("private_invitation_viewer_description"))
        assertFalse(layout.contains("accept"))
        assertFalse(layout.contains("reject"))
        assertFalse(layout.contains("join"))
        assertFalse(layout.contains("expiry"))
        assertFalse(layout.contains("@string/nearby_private_invitation_label"))
    }
}
