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
import app.peermesh.security.groupinvitation.RecipientEncryptionIdentityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPrivateGroupInvitationReceiveGI2E5Test {
    @Test
    fun validPrivateInvitationDecryptsExactlyOnceAndReturnsBoundView() {
        var decryptCalls = 0
        var capturedContext: PrivateRecipientCryptoContext? = null
        val inspector = NearbyPrivateGroupInvitationInspector { _, context, _ ->
            decryptCalls += 1
            capturedContext = context
            PrivateRecipientDecryptResult.Success(canonicalInner().toByteArray())
        }
        val message = message()

        val result = inspector.inspect(message, TARGET_ID, NOW_MS)

        assertTrue(result is NearbyPrivateGroupInvitationReceiveResult.Invitation)
        val invitation = (result as NearbyPrivateGroupInvitationReceiveResult.Invitation).invitation
        assertEquals(MESSAGE_ID, invitation.messageId)
        assertEquals(MESSAGE_ID, invitation.cardId)
        assertEquals(OWNER_ID, invitation.logicalSenderId)
        assertEquals(TARGET_ID, invitation.targetParticipantId)
        assertEquals("Rescue Group", invitation.title)
        assertEquals("Nearby coordination", invitation.shortDescription)
        assertEquals(InvitationCredential.of("join-secret"), invitation.invitationCredential)
        assertEquals(1, decryptCalls)
        assertEquals(
            PrivateRecipientCryptoContext(
                protocolVersion = GroupInvitationProtocol.VERSION,
                algorithmWireValue = ALGORITHM.wireValue,
                roomId = ROOM_ID,
                messageId = MESSAGE_ID,
                cardId = MESSAGE_ID,
                logicalSenderId = OWNER_ID,
                targetParticipantId = TARGET_ID,
                targetKeyId = KEY_ID,
                effectiveRoomExpiryEpochMs = EXPIRES_AT_MS,
            ),
            capturedContext,
        )
        assertEquals(messageBody(), message.body)
        assertEquals(null, message.local_render_source_body)
    }

    @Test
    fun persistedInspectionLoadsAuthoritativeRowByIdentity() {
        var loadedRoomId: String? = null
        var loadedMessageId: String? = null
        var capturedContext: PrivateRecipientCryptoContext? = null
        val inspector = NearbyPrivateGroupInvitationInspector { _, context, _ ->
            capturedContext = context
            PrivateRecipientDecryptResult.Success(canonicalInner().toByteArray())
        }
        val persisted = message(ownerId = OWNER_ID, senderId = "relay-device")

        val result = inspector.inspectPersisted(
            roomId = ROOM_ID,
            messageId = MESSAGE_ID,
            messageLoader = { roomId, messageId ->
                loadedRoomId = roomId
                loadedMessageId = messageId
                persisted
            },
            localParticipantId = TARGET_ID,
            nowMs = NOW_MS,
        )

        assertTrue(result is NearbyPrivateGroupInvitationReceiveResult.Invitation)
        assertEquals(ROOM_ID, loadedRoomId)
        assertEquals(MESSAGE_ID, loadedMessageId)
        assertEquals(ROOM_ID, capturedContext?.roomId)
        assertEquals(MESSAGE_ID, capturedContext?.messageId)
        assertEquals(OWNER_ID, capturedContext?.logicalSenderId)
        assertEquals(EXPIRES_AT_MS, capturedContext?.effectiveRoomExpiryEpochMs)
    }

    @Test
    fun missingPersistedMessageReturnsMessageNotFoundWithoutDecrypt() {
        var decryptCalls = 0
        val inspector = inspector {
            decryptCalls += 1
            PrivateRecipientDecryptResult.CryptoUnavailable
        }

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.MessageNotFound,
            inspector.inspectPersisted(
                roomId = ROOM_ID,
                messageId = MESSAGE_ID,
                messageLoader = { _, _ -> null },
                localParticipantId = TARGET_ID,
                nowMs = NOW_MS,
            ),
        )
        assertEquals(0, decryptCalls)
    }

    @Test
    fun wrongParticipantRejectsBeforeDecrypt() {
        var decryptCalls = 0
        val inspector = inspector { decryptCalls += 1; PrivateRecipientDecryptResult.CryptoUnavailable }

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.NotForLocalParticipant,
            inspector.inspect(message(), "other-device", NOW_MS),
        )
        assertEquals(0, decryptCalls)
    }

    @Test
    fun messageIdCardIdMismatchRejectsBeforeDecrypt() {
        var decryptCalls = 0
        val inspector = inspector { decryptCalls += 1; PrivateRecipientDecryptResult.CryptoUnavailable }

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.MessageIdentityMismatch,
            inspector.inspect(message(messageId = "different-message"), TARGET_ID, NOW_MS),
        )
        assertEquals(0, decryptCalls)
    }

    @Test
    fun senderBindingUsesLogicalOwnerAndForwardsAuthenticationFailure() {
        var context: PrivateRecipientCryptoContext? = null
        val inspector = NearbyPrivateGroupInvitationInspector { _, supplied, _ ->
            context = supplied
            PrivateRecipientDecryptResult.AuthenticationFailed
        }

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.AuthenticationFailed,
            inspector.inspect(message(ownerId = OWNER_ID, senderId = "relay-device"), TARGET_ID, NOW_MS),
        )
        assertEquals(OWNER_ID, context?.logicalSenderId)
    }

    @Test
    fun wrongKeyExpiredInvalidCiphertextAndCryptoFailuresAreTyped() {
        val cases = listOf(
            PrivateRecipientDecryptResult.NotForLocalRecipientKey to NearbyPrivateGroupInvitationReceiveResult.WrongRecipientKey,
            PrivateRecipientDecryptResult.Expired to NearbyPrivateGroupInvitationReceiveResult.Expired,
            PrivateRecipientDecryptResult.InvalidEncryptedPayload to NearbyPrivateGroupInvitationReceiveResult.InvalidEncryptedPayload,
            PrivateRecipientDecryptResult.InvalidContext to NearbyPrivateGroupInvitationReceiveResult.InvalidCryptoContext,
            PrivateRecipientDecryptResult.UnsupportedProtocolVersion to NearbyPrivateGroupInvitationReceiveResult.UnsupportedCryptoProtocol,
            PrivateRecipientDecryptResult.UnsupportedAlgorithm to NearbyPrivateGroupInvitationReceiveResult.UnsupportedCryptoAlgorithm,
            PrivateRecipientDecryptResult.KeyAgreementFailed to NearbyPrivateGroupInvitationReceiveResult.KeyAgreementFailed,
            PrivateRecipientDecryptResult.CryptoUnavailable to NearbyPrivateGroupInvitationReceiveResult.CryptoUnavailable,
            PrivateRecipientDecryptResult.RecipientIdentityUnavailable(
                RecipientEncryptionIdentityStatus.Reason.KEYSTORE_UNAVAILABLE,
            ) to NearbyPrivateGroupInvitationReceiveResult.RecipientIdentityUnavailable,
            PrivateRecipientDecryptResult.RecipientIdentityInvalidated(
                RecipientEncryptionIdentityStatus.Reason.KEY_INVALIDATED,
            ) to NearbyPrivateGroupInvitationReceiveResult.RecipientIdentityInvalidated,
            PrivateRecipientDecryptResult.UnsupportedPlatform to NearbyPrivateGroupInvitationReceiveResult.UnsupportedPlatform,
        )
        cases.forEach { (crypto, expected) ->
            assertEquals(expected, inspector { crypto }.inspect(message(), TARGET_ID, NOW_MS))
        }
    }

    @Test
    fun nonCanonicalPrivateEnvelopeNeverDecrypts() {
        var decryptCalls = 0
        val inspector = inspector {
            decryptCalls += 1
            PrivateRecipientDecryptResult.CryptoUnavailable
        }
        val nonCanonicalBody = messageBody().replace(",", ", ")

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.UnsupportedOrInvalidEnvelope,
            inspector.inspect(message(body = nonCanonicalBody), TARGET_ID, NOW_MS),
        )
        assertEquals(0, decryptCalls)
    }

    @Test
    fun nonCanonicalPrivateInnerPayloadIsRejected() {
        val canonical = canonicalInner()
        val nonCanonical = canonical.replace(",", ", ")

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload,
            inspector { PrivateRecipientDecryptResult.Success(nonCanonical.toByteArray()) }
                .inspect(message(), TARGET_ID, NOW_MS),
        )
    }

    @Test
    fun malformedEnvelopeAndInvalidNonceNeverDecrypt() {
        var decryptCalls = 0
        val inspector = inspector { decryptCalls += 1; PrivateRecipientDecryptResult.CryptoUnavailable }
        val malformed = message(body = "{\"t\":\"gi\",\"v\":1,\"a\":\"p\"}")
        val invalidNonce = message(
            body = messageBody().replace(
                "\"iv\":\"${Base64Url.encode(ByteArray(12))}\"",
                "\"iv\":\"AA\"",
            ),
        )
        val invalidCiphertext = message(
            body = messageBody().replace(
                "\"ct\":\"${Base64Url.encode(ByteArray(16))}\"",
                "\"ct\":\"${Base64Url.encode(ByteArray(15))}\"",
            )
        )

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.UnsupportedOrInvalidEnvelope,
            inspector.inspect(malformed, TARGET_ID, NOW_MS),
        )
        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.UnsupportedOrInvalidEnvelope,
            inspector.inspect(invalidNonce, TARGET_ID, NOW_MS),
        )
        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.UnsupportedOrInvalidEnvelope,
            inspector.inspect(invalidCiphertext, TARGET_ID, NOW_MS),
        )
        assertEquals(0, decryptCalls)
    }

    @Test
    fun malformedInnerPayloadAndInvalidUtf8AreRejectedWithoutPartialResult() {
        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload,
            inspector { PrivateRecipientDecryptResult.Success("{bad".toByteArray()) }
                .inspect(message(), TARGET_ID, NOW_MS),
        )
        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.MalformedInnerPayload,
            inspector { PrivateRecipientDecryptResult.Success(byteArrayOf(0xC3.toByte(), 0x28)) }
                .inspect(message(), TARGET_ID, NOW_MS),
        )
    }

    @Test
    fun nonInvitationAndPublicInvitationNeverDecrypt() {
        var decryptCalls = 0
        val inspector = inspector { decryptCalls += 1; PrivateRecipientDecryptResult.CryptoUnavailable }
        val publicBody = GroupInvitationProtocol.encodePublic(
            app.peermesh.core.groupinvitation.PublicGroupInvitationPayload(
                cardId = MESSAGE_ID,
                title = "Public",
                shortDescription = "",
                invitationCredential = InvitationCredential.of("public-secret"),
            )
        )

        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.NotGroupInvitation,
            inspector.inspect(message(body = "hello"), TARGET_ID, NOW_MS),
        )
        assertEquals(
            NearbyPrivateGroupInvitationReceiveResult.NotPrivateInvitation,
            inspector.inspect(message(body = publicBody), TARGET_ID, NOW_MS),
        )
        assertEquals(0, decryptCalls)
    }

    private fun inspector(
        result: () -> PrivateRecipientDecryptResult,
    ) = NearbyPrivateGroupInvitationInspector { _: PrivateRecipientEncryptedPayload, _: PrivateRecipientCryptoContext, _: Long -> result() }

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
        local_render_source_body = null,
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
