package app.peermesh

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPublicInvitationArchitectureGI2E6Test {
    @Test
    fun `public viewer uses identity only repository revalidation`() {
        val source = File("src/main/java/app/peermesh/NearbyPublicGroupInvitationViewerActivity.kt").readText()
        assertTrue(source.contains("EXTRA_ROOM_ID"))
        assertTrue(source.contains("EXTRA_MESSAGE_ID"))
        assertTrue(source.contains("inspectPublicGroupInvitationForTimeline"))
        assertFalse(source.contains("GroupInvitationProtocol"))
        assertFalse(source.contains("InvitationCredential"))
        assertFalse(source.contains("NearbyRoomMessageEntity"))
        assertFalse(source.contains("PrivateRecipientCrypto"))
    }

    @Test
    fun `public composer delegates to locked public pipeline without recipient state`() {
        val activity = File("src/main/java/app/peermesh/NearbyPublicInvitationComposerActivity.kt").readText()
        val authority = File("src/main/java/app/peermesh/nearby/NearbyPublicInvitationComposerAuthority.kt").readText()
        assertTrue(activity.contains("NearbyPublicGroupInvitationSendPipeline"))
        assertTrue(activity.contains("NearbyPublicInvitationComposerAuthority"))
        assertTrue(activity.contains("EXTRA_ROOM_ID"))
        assertFalse(activity.contains("TARGET_PARTICIPANT"))
        assertFalse(activity.contains("InvitationCredential"))
        assertFalse(activity.contains("GroupInvitationProtocol"))
        assertTrue(authority.contains("GroupInvitationMaterialAuthority"))
        assertTrue(authority.contains("pipeline::send"))
        assertFalse(authority.contains("GroupInvitationProtocol"))
        assertFalse(authority.contains("JcaPrivateRecipientCrypto"))
    }

    @Test
    fun `public timeline renderer receives typed item and never protocol body`() {
        val adapter = File("src/main/java/app/peermesh/NearbyRoomMessageAdapter.kt").readText()
        val activity = File("src/main/java/app/peermesh/NearbyRoomActivity.kt").readText()
        assertTrue(adapter.contains("NearbyRoomTimelineItem.PublicInvitation"))
        assertTrue(adapter.contains("PublicInvitationViewHolder"))
        assertTrue(adapter.contains("HiddenPublicInvitationViewHolder"))
        assertTrue(adapter.contains("nearby_public_invitation_unhide"))
        assertFalse(adapter.contains("GroupInvitationProtocol"))
        assertFalse(adapter.contains("InvitationCredential"))
        assertTrue(activity.contains("inspectPublicGroupInvitationForTimeline"))
        assertTrue(activity.contains("NearbyPublicGroupInvitationPresentationMapper"))
        assertFalse(activity.contains("GroupInvitationProtocol.decode(message.body)"))
    }

    @Test
    fun `public UX exposes join and hide without membership or credential authority`() {
        val layouts = listOf(
            "src/main/res/layout/item_nearby_room_public_invitation.xml",
            "src/main/res/layout/activity_nearby_public_group_invitation_viewer.xml",
            "src/main/res/layout/activity_nearby_public_invitation_composer.xml",
        ).joinToString("\n") { File(it).readText() }
        val viewer = File("src/main/java/app/peermesh/NearbyPublicGroupInvitationViewerActivity.kt").readText()
        assertFalse(layouts.contains("credential", ignoreCase = true))
        assertFalse(layouts.contains("recipient", ignoreCase = true))
        assertTrue(layouts.contains("nearby_public_invitation_join"))
        assertTrue(layouts.contains("nearby_public_invitation_hide"))
        assertFalse(layouts.contains("accept", ignoreCase = true))
        assertFalse(layouts.contains("reject", ignoreCase = true))
        assertFalse(viewer.contains("InvitationCredential"))
        assertFalse(viewer.contains("GroupLocalDataManager"))
        assertFalse(viewer.contains("GroupInvitationProtocol"))
        assertTrue(viewer.contains("setPublicGroupInvitationHiddenLocally"))
        assertTrue(viewer.contains("nearby_public_invitation_join_future_phase"))
    }

    @Test
    fun `public invitation content opens room invitation index with target identity`() {
        val activity = File("src/main/java/app/peermesh/NearbyRoomActivity.kt").readText()
        val index = File("src/main/java/app/peermesh/NearbyRoomInvitationsActivity.kt").readText()
        assertTrue(activity.contains("NearbyRoomInvitationsActivity.intent(this, roomId, messageId)"))
        assertTrue(index.contains("sortedWith(compareBy<NearbyRoomTimelineItem> { it.createdAtMs }"))
        assertTrue(index.contains("scrollToPositionWithOffset(targetIndex"))
        assertTrue(index.contains("inspectPublicGroupInvitationForTimeline"))
        assertTrue(index.contains("inspectPrivateGroupInvitationForTimeline"))
        assertFalse(index.contains("GroupInvitationProtocol"))
        assertFalse(index.contains("InvitationCredential"))
        val listLayout = File("src/main/res/layout/item_nearby_room_invitation_list.xml").readText()
        assertTrue(listLayout.contains("nearby_public_invitation_join"))
        assertFalse(listLayout.contains("nearby_public_invitation_hide"))
    }
}
