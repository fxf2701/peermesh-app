package app.peermesh.nearby

import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.core.groupinvitation.PublicGroupInvitationPayload
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPublicGroupInvitationLocalVisibilityGI2E6Test {
    private val controller = NearbyPublicGroupInvitationLocalVisibilityController()

    @Test
    fun `canonical public invitation can be hidden locally without changing body`() {
        val original = message()
        var stored: NearbyRoomMessageEntity? = original

        val result = controller.setHiddenLocally(
            roomId = ROOM_ID,
            messageId = MESSAGE_ID,
            hidden = true,
            messageLoader = { _, _ -> stored },
            hiddenStateUpdater = { _, _, hidden ->
                stored = stored?.copy(is_hidden_locally = hidden)
                1
            },
        )

        assertEquals(NearbyGroupInvitationLocalVisibilityResult.Updated, result)
        assertTrue(stored!!.is_hidden_locally)
        assertEquals(original.body, stored!!.body)
        assertEquals(original.local_render_source_body, stored!!.local_render_source_body)
    }

    @Test
    fun `normal private malformed and identity mismatched messages are rejected`() {
        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation,
            execute(message(body = "hello")),
        )
        val privateBody = GroupInvitationProtocol.encodePublic(publicPayload()).replace("\"a\":\"r\"", "\"a\":\"p\"")
        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.InvitationInvalid,
            execute(message(body = privateBody)),
        )
        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.InvitationInvalid,
            execute(message(messageId = "different-id")),
        )
    }

    private fun execute(value: NearbyRoomMessageEntity): NearbyGroupInvitationLocalVisibilityResult {
        var stored: NearbyRoomMessageEntity? = value
        return controller.setHiddenLocally(
            roomId = value.room_id,
            messageId = value.message_id,
            hidden = true,
            messageLoader = { _, _ -> stored },
            hiddenStateUpdater = { _, _, hidden -> stored = stored?.copy(is_hidden_locally = hidden); 1 },
        )
    }

    private fun publicPayload() = PublicGroupInvitationPayload(
        cardId = MESSAGE_ID,
        title = "Group",
        shortDescription = "Description",
        invitationCredential = InvitationCredential.of("credential"),
    )

    private fun message(
        messageId: String = MESSAGE_ID,
        body: String = GroupInvitationProtocol.encodePublic(publicPayload()),
    ) = NearbyRoomMessageEntity(
        room_id = ROOM_ID,
        message_id = messageId,
        owner_device_id = "sender",
        sender_device_id = "sender",
        sender_display_name = "Sender",
        body = body,
        created_at_ms = 1_000L,
        received_at_ms = 1_001L,
        ttl_expires_at_ms = 10_000L,
        delivery_state = NearbyRoomMessageEntity.DELIVERY_RECEIVED,
        edited_at_ms = null,
        deleted_at_ms = null,
        local_render_source_body = null,
        is_hidden_locally = false,
    )

    private companion object {
        const val ROOM_ID = "room-1"
        const val MESSAGE_ID = "message-1"
    }
}
