package app.peermesh.nearby

import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRoomLocalBlockPolicyTest {
    @Test
    fun `visibleMessages hides blocked participant messages`() {
        val messages = listOf(
            message(messageId = "m1", senderDeviceId = "device-a"),
            message(messageId = "m2", senderDeviceId = "device-b"),
            message(messageId = "m3", senderDeviceId = "device-c"),
        )

        val visible = NearbyRoomLocalBlockPolicy.visibleMessages(
            messages = messages,
            blockedDeviceIds = setOf("device-b"),
        )

        assertEquals(listOf("m1", "m3"), visible.map { it.message_id })
    }

    @Test
    fun `shouldHideMessage does not hide system messages`() {
        val message = message(
            messageId = "system-1",
            senderDeviceId = NearbyRoomMessageEntity.SYSTEM_SENDER_DEVICE_ID,
            deliveryState = NearbyRoomMessageEntity.DELIVERY_SYSTEM,
        )

        assertFalse(
            NearbyRoomLocalBlockPolicy.shouldHideMessage(
                message = message,
                blockedDeviceIds = setOf(NearbyRoomMessageEntity.SYSTEM_SENDER_DEVICE_ID),
            )
        )
    }

    @Test
    fun `shouldHideMessage uses owner device id when present`() {
        val message = message(
            ownerDeviceId = "device-owner",
            senderDeviceId = "device-hop",
        )

        assertTrue(
            NearbyRoomLocalBlockPolicy.shouldHideMessage(
                message = message,
                blockedDeviceIds = setOf("device-owner"),
            )
        )
    }

    private fun message(
        roomId: String = "room-1",
        messageId: String = "message-1",
        ownerDeviceId: String? = null,
        senderDeviceId: String = "device-a",
        deliveryState: String = NearbyRoomMessageEntity.DELIVERY_RECEIVED,
    ): NearbyRoomMessageEntity {
        return NearbyRoomMessageEntity(
            room_id = roomId,
            message_id = messageId,
            reply_to = null,
            owner_device_id = ownerDeviceId,
            sender_device_id = senderDeviceId,
            sender_display_name = "Peer",
            body = "hello",
            created_at_ms = 100L,
            received_at_ms = 100L,
            ttl_expires_at_ms = 1_000L,
            delivery_state = deliveryState,
            edited_at_ms = null,
            deleted_at_ms = null,
        )
    }
}
