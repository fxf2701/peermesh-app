package app.peermesh.nearby

import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyPrivateGroupInvitationTimelineAuthorityGI2E6Test {
    @Test fun `persisted invitation owner is authoritative and relay sender is never fallback`() {
        assertEquals(
            OWNER_ID,
            NearbyPrivateGroupInvitationTimelineAuthority.authoritativeOwnerId(
                message(ownerId = OWNER_ID, senderId = RELAY_ID),
            ),
        )
        assertNull(
            NearbyPrivateGroupInvitationTimelineAuthority.authoritativeOwnerId(
                message(ownerId = null, senderId = RELAY_ID),
            ),
        )
    }

    @Test fun `missing owner profile uses neutral fallback instead of relay display name`() {
        assertEquals(
            UNKNOWN_SENDER,
            NearbyPrivateGroupInvitationTimelineAuthority.resolveSenderDisplayName(
                logicalSenderId = OWNER_ID,
                localParticipantId = LOCAL_ID,
                localDisplayName = "Local",
                participantDisplayName = null,
                unknownSenderLabel = UNKNOWN_SENDER,
            ),
        )
    }

    @Test fun `local sender uses local profile and remote owner uses owner participant profile`() {
        assertEquals(
            "Local owner",
            NearbyPrivateGroupInvitationTimelineAuthority.resolveSenderDisplayName(
                logicalSenderId = LOCAL_ID,
                localParticipantId = LOCAL_ID,
                localDisplayName = " Local owner ",
                participantDisplayName = "Relay",
                unknownSenderLabel = UNKNOWN_SENDER,
            ),
        )
        assertEquals(
            "Original owner",
            NearbyPrivateGroupInvitationTimelineAuthority.resolveSenderDisplayName(
                logicalSenderId = OWNER_ID,
                localParticipantId = LOCAL_ID,
                localDisplayName = "Local",
                participantDisplayName = " Original owner ",
                unknownSenderLabel = UNKNOWN_SENDER,
            ),
        )
    }

    private fun message(ownerId: String?, senderId: String) = NearbyRoomMessageEntity(
        room_id = "room-1",
        message_id = "message-1",
        owner_device_id = ownerId,
        sender_device_id = senderId,
        sender_display_name = "Relay display",
        body = "body",
        created_at_ms = 1_000L,
        received_at_ms = 1_001L,
        ttl_expires_at_ms = 10_000L,
        delivery_state = NearbyRoomMessageEntity.DELIVERY_RECEIVED,
        edited_at_ms = null,
        deleted_at_ms = null,
    )

    private companion object {
        const val OWNER_ID = "owner-device"
        const val RELAY_ID = "relay-device"
        const val LOCAL_ID = "local-device"
        const val UNKNOWN_SENDER = "Unknown sender"
    }
}
