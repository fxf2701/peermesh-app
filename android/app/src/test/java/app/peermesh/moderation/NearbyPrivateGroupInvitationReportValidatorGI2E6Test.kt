package app.peermesh.moderation

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.PrivateGroupInvitationEnvelope
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyPrivateGroupInvitationReportValidatorGI2E6Test {
    @Test fun `canonical intended recipient report resolves persisted owner`() {
        assertEquals(
            OWNER_ID,
            NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId(
                message = message(),
                reportedPeerId = OWNER_ID,
                localParticipantId = TARGET_ID,
            ),
        )
    }

    @Test fun `report rejects missing owner even when relay sender exists`() {
        assertNull(
            NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId(
                message = message(ownerId = null, senderId = RELAY_ID),
                reportedPeerId = RELAY_ID,
                localParticipantId = TARGET_ID,
            ),
        )
    }

    @Test fun `report rejects non canonical envelope and identity mismatches`() {
        val canonical = messageBody()
        assertNull(
            NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId(
                message = message(body = "$canonical "),
                reportedPeerId = OWNER_ID,
                localParticipantId = TARGET_ID,
            ),
        )
        assertNull(
            NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId(
                message = message(messageId = "different-message"),
                reportedPeerId = OWNER_ID,
                localParticipantId = TARGET_ID,
            ),
        )
        assertNull(
            NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId(
                message = message(),
                reportedPeerId = RELAY_ID,
                localParticipantId = TARGET_ID,
            ),
        )
    }

    @Test fun `report rejects sender self report and non target participant`() {
        assertNull(
            NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId(
                message = message(),
                reportedPeerId = OWNER_ID,
                localParticipantId = OWNER_ID,
            ),
        )
        assertNull(
            NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId(
                message = message(),
                reportedPeerId = OWNER_ID,
                localParticipantId = "third-device",
            ),
        )
    }

    @Test fun `report rejects invalid persisted context`() {
        assertNull(
            NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId(
                message = message(roomId = ""),
                reportedPeerId = OWNER_ID,
                localParticipantId = TARGET_ID,
            ),
        )
        assertNull(
            NearbyPrivateGroupInvitationReportValidator.validatedReportedOwnerId(
                message = message(expiresAtMs = CREATED_AT_MS),
                reportedPeerId = OWNER_ID,
                localParticipantId = TARGET_ID,
            ),
        )
    }

    private fun messageBody(cardId: String = MESSAGE_ID) = GroupInvitationProtocol.encodePrivateEnvelope(
        PrivateGroupInvitationEnvelope(
            cardId = cardId,
            targetParticipantId = TARGET_ID,
            algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM,
            targetKeyId = KEY_ID,
            ephemeralPublicKey = Base64Url.encode(ByteArray(65).also { it[0] = 0x04 }),
            nonce = Base64Url.encode(ByteArray(12)),
            ciphertext = Base64Url.encode(ByteArray(16)),
        )
    )

    private fun message(
        roomId: String = ROOM_ID,
        messageId: String = MESSAGE_ID,
        ownerId: String? = OWNER_ID,
        senderId: String = OWNER_ID,
        body: String = messageBody(),
        expiresAtMs: Long = EXPIRES_AT_MS,
    ) = NearbyRoomMessageEntity(
        room_id = roomId,
        message_id = messageId,
        owner_device_id = ownerId,
        sender_device_id = senderId,
        sender_display_name = "Sender",
        body = body,
        created_at_ms = CREATED_AT_MS,
        received_at_ms = CREATED_AT_MS + 1,
        ttl_expires_at_ms = expiresAtMs,
        delivery_state = NearbyRoomMessageEntity.DELIVERY_RECEIVED,
        edited_at_ms = null,
        deleted_at_ms = null,
    )

    private companion object {
        const val ROOM_ID = "room-1"
        const val MESSAGE_ID = "message-1"
        const val OWNER_ID = "owner-device"
        const val RELAY_ID = "relay-device"
        const val TARGET_ID = "target-device"
        const val KEY_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val CREATED_AT_MS = 1_000L
        const val EXPIRES_AT_MS = 10_000L
    }
}
