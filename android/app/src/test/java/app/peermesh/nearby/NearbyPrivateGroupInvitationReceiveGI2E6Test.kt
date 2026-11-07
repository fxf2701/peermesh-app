package app.peermesh.nearby

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.core.groupinvitation.PrivateGroupInvitationEnvelope
import app.peermesh.core.groupinvitation.PrivateGroupInvitationInnerPayload
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.security.groupinvitation.PrivateRecipientCryptoContext
import app.peermesh.security.groupinvitation.PrivateRecipientDecryptResult
import app.peermesh.security.groupinvitation.PrivateRecipientEncryptedPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPrivateGroupInvitationReceiveGI2E6Test {
    @Test
    fun localLogicalSenderUsesCanonicalLocalSourceWithoutDecrypt() {
        var decryptCalls = 0
        val inspector = inspector {
            decryptCalls += 1
            PrivateRecipientDecryptResult.CryptoUnavailable
        }

        val result = inspector.inspect(
            message = message(localRenderSourceBody = canonicalInner()),
            localParticipantId = OWNER_ID,
            nowMs = NOW_MS,
        )

        assertTrue(result is NearbyPrivateGroupInvitationReceiveResult.Invitation)
        val invitation = (result as NearbyPrivateGroupInvitationReceiveResult.Invitation).invitation
        assertEquals(ROOM_ID, invitation.roomId)
        assertEquals(MESSAGE_ID, invitation.messageId)
        assertEquals(MESSAGE_ID, invitation.cardId)
        assertEquals(OWNER_ID, invitation.logicalSenderId)
        assertEquals(TARGET_ID, invitation.targetParticipantId)
        assertEquals("Rescue Group", invitation.title)
        assertEquals("Nearby coordination", invitation.shortDescription)
        assertEquals(InvitationCredential.of("join-secret"), invitation.invitationCredential)
        assertEquals(0, decryptCalls)
    }

    @Test
    fun relaySenderDoesNotOverrideLogicalOwnerForSenderLocalAuthority() {
        var decryptCalls = 0
        val inspector = inspector {
            decryptCalls += 1
            PrivateRecipientDecryptResult.CryptoUnavailable
        }
        val message = message(
            ownerId = OWNER_ID,
            senderId = "relay-device",
            localRenderSourceBody = canonicalInner(),
        )

        assertTrue(
            inspector.inspect(message, OWNER_ID, NOW_MS) is
                NearbyPrivateGroupInvitationReceiveResult.Invitation
        )
        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.NotForLocalParticipant,
            inspector.inspect(message, "relay-device", NOW_MS),
        )
        assertEquals(0, decryptCalls)
    }

    @Test
    fun senderLocalMissingOrNonCanonicalSourceIsRejectedWithoutDecrypt() {
        var decryptCalls = 0
        val inspector = inspector {
            decryptCalls += 1
            PrivateRecipientDecryptResult.CryptoUnavailable
        }
        val nonCanonical = canonicalInner().replace(",", ", ")

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload,
            inspector.inspect(message(localRenderSourceBody = null), OWNER_ID, NOW_MS),
        )
        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload,
            inspector.inspect(message(localRenderSourceBody = nonCanonical), OWNER_ID, NOW_MS),
        )
        assertEquals(0, decryptCalls)
    }

    @Test
    fun senderLocalExpiredInvitationIsRejectedWithoutDecrypt() {
        var decryptCalls = 0
        val inspector = inspector {
            decryptCalls += 1
            PrivateRecipientDecryptResult.CryptoUnavailable
        }

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.Expired,
            inspector.inspect(
                message = message(localRenderSourceBody = canonicalInner()),
                localParticipantId = OWNER_ID,
                nowMs = EXPIRES_AT_MS,
            ),
        )
        assertEquals(0, decryptCalls)
    }

    @Test
    fun recipientPathStillDecryptsExactlyOnceAndIgnoresSenderLocalSource() {
        var decryptCalls = 0
        val inspector = NearbyPrivateGroupInvitationInspector { _, _, _ ->
            decryptCalls += 1
            PrivateRecipientDecryptResult.Success(canonicalInner().toByteArray())
        }

        val result = inspector.inspect(
            message = message(localRenderSourceBody = "not trusted for recipient"),
            localParticipantId = TARGET_ID,
            nowMs = NOW_MS,
        )

        assertTrue(result is NearbyPrivateGroupInvitationReceiveResult.Invitation)
        assertEquals(1, decryptCalls)
        assertEquals(
            "Rescue Group",
            (result as NearbyPrivateGroupInvitationReceiveResult.Invitation).invitation.title,
        )
    }

    @Test
    fun thirdParticipantIsRejectedBeforeDecryptEvenWhenSenderSourceExists() {
        var decryptCalls = 0
        val inspector = inspector {
            decryptCalls += 1
            PrivateRecipientDecryptResult.CryptoUnavailable
        }

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.NotForLocalParticipant,
            inspector.inspect(
                message = message(localRenderSourceBody = canonicalInner()),
                localParticipantId = "third-device",
                nowMs = NOW_MS,
            ),
        )
        assertEquals(0, decryptCalls)
    }

    @Test
    fun outerIdentityAndCanonicalEnvelopeRemainAuthoritativeForSender() {
        var decryptCalls = 0
        val inspector = inspector {
            decryptCalls += 1
            PrivateRecipientDecryptResult.CryptoUnavailable
        }

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.MessageIdentityMismatch,
            inspector.inspect(
                message = message(
                    messageId = "different-message",
                    localRenderSourceBody = canonicalInner(),
                ),
                localParticipantId = OWNER_ID,
                nowMs = NOW_MS,
            ),
        )
        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.UnsupportedOrInvalidEnvelope,
            inspector.inspect(
                message = message(
                    body = messageBody().replace(",", ", "),
                    localRenderSourceBody = canonicalInner(),
                ),
                localParticipantId = OWNER_ID,
                nowMs = NOW_MS,
            ),
        )
        assertEquals(0, decryptCalls)
    }

    private fun inspector(
        result: () -> PrivateRecipientDecryptResult,
    ) = NearbyPrivateGroupInvitationInspector { _, _, _ -> result() }

    private fun canonicalInner() = GroupInvitationProtocol.encodePrivateInnerPayload(
        PrivateGroupInvitationInnerPayload(
            title = "Rescue Group",
            shortDescription = "Nearby coordination",
            invitationCredential = InvitationCredential.of("join-secret"),
        )
    )

    private fun messageBody(cardId: String = MESSAGE_ID) = GroupInvitationProtocol.encodePrivateEnvelope(
        PrivateGroupInvitationEnvelope(
            cardId = cardId,
            targetParticipantId = TARGET_ID,
            algorithm = ALGORITHM,
            targetKeyId = KEY_ID,
            ephemeralPublicKey = Base64Url.encode(ByteArray(65).also { it[0] = 0x04 }),
            nonce = Base64Url.encode(ByteArray(12)),
            ciphertext = Base64Url.encode(ByteArray(16)),
        )
    )

    private fun message(
        messageId: String = MESSAGE_ID,
        body: String = messageBody(),
        ownerId: String? = OWNER_ID,
        senderId: String = OWNER_ID,
        localRenderSourceBody: String?,
    ) = NearbyRoomMessageEntity(
        room_id = ROOM_ID,
        message_id = messageId,
        owner_device_id = ownerId,
        sender_device_id = senderId,
        sender_display_name = "Sender",
        body = body,
        created_at_ms = CREATED_AT_MS,
        received_at_ms = CREATED_AT_MS + 1,
        ttl_expires_at_ms = EXPIRES_AT_MS,
        delivery_state = NearbyRoomMessageEntity.DELIVERY_RECEIVED,
        edited_at_ms = null,
        deleted_at_ms = null,
        local_render_source_body = localRenderSourceBody,
        is_hidden_locally = false,
    )

    private companion object {
        const val ROOM_ID = "room-1"
        const val MESSAGE_ID = "message-1"
        const val OWNER_ID = "owner-device"
        const val TARGET_ID = "local-device"
        const val KEY_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val CREATED_AT_MS = 1_000L
        const val NOW_MS = 2_000L
        const val EXPIRES_AT_MS = 10_000L
        val ALGORITHM = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM
    }
}
