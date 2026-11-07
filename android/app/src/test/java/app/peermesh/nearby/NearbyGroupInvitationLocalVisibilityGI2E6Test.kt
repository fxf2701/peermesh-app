package app.peermesh.nearby

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.core.groupinvitation.PrivateGroupInvitationEnvelope
import app.peermesh.core.groupinvitation.PublicGroupInvitationPayload
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyGroupInvitationLocalVisibilityGI2E6Test {
    private val controller = NearbyGroupInvitationLocalVisibilityController()

    @Test
    fun logicalSenderCanHideAndOnlyLocalHiddenStateChanges() {
        val original = invitationMessage(hidden = false)
        var stored: NearbyRoomMessageEntity? = original
        var updateCalls = 0

        val result = controller.setHiddenLocally(
            roomId = ROOM_ID,
            messageId = MESSAGE_ID,
            hidden = true,
            localParticipantId = OWNER_ID,
            messageLoader = { _, _ -> stored },
            hiddenStateUpdater = { _, _, hidden ->
                updateCalls += 1
                stored = stored?.copy(is_hidden_locally = hidden)
                1
            },
        )

        assertEquals(NearbyGroupInvitationLocalVisibilityResult.Updated, result)
        assertEquals(1, updateCalls)
        assertTrue(stored!!.is_hidden_locally)
        assertEquals(original.copy(is_hidden_locally = true), stored)
    }

    @Test
    fun intendedRecipientCanUnhideWithoutDecryptOrPlaintextDependency() {
        var stored: NearbyRoomMessageEntity? = invitationMessage(
            hidden = true,
            localRenderSourceBody = null,
        )

        val result = controller.setHiddenLocally(
            roomId = ROOM_ID,
            messageId = MESSAGE_ID,
            hidden = false,
            localParticipantId = TARGET_ID,
            messageLoader = { _, _ -> stored },
            hiddenStateUpdater = { _, _, hidden ->
                stored = stored?.copy(is_hidden_locally = hidden)
                1
            },
        )

        assertEquals(NearbyGroupInvitationLocalVisibilityResult.Updated, result)
        assertFalse(stored!!.is_hidden_locally)
        assertEquals(null, stored!!.local_render_source_body)
    }

    @Test
    fun repeatedRequestReturnsAlreadyInRequestedStateWithoutUpdate() {
        val stored = invitationMessage(hidden = true)
        var updateCalls = 0

        val result = controller.setHiddenLocally(
            roomId = ROOM_ID,
            messageId = MESSAGE_ID,
            hidden = true,
            localParticipantId = TARGET_ID,
            messageLoader = { _, _ -> stored },
            hiddenStateUpdater = { _, _, _ -> updateCalls += 1; 1 },
        )

        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.AlreadyInRequestedState,
            result,
        )
        assertEquals(0, updateCalls)
    }

    @Test
    fun thirdParticipantCannotMutateLocalVisibility() {
        val stored = invitationMessage(hidden = false)
        var updateCalls = 0

        val result = controller.setHiddenLocally(
            roomId = ROOM_ID,
            messageId = MESSAGE_ID,
            hidden = true,
            localParticipantId = "third-device",
            messageLoader = { _, _ -> stored },
            hiddenStateUpdater = { _, _, _ -> updateCalls += 1; 1 },
        )

        assertEquals(NearbyGroupInvitationLocalVisibilityResult.NotAuthorizedViewer, result)
        assertEquals(0, updateCalls)
        assertFalse(stored.is_hidden_locally)
    }

    @Test
    fun relaySenderDoesNotGainAuthorityOverLogicalOwner() {
        val stored = invitationMessage(
            ownerId = OWNER_ID,
            senderId = "relay-device",
            hidden = false,
        )

        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.NotAuthorizedViewer,
            execute(stored, "relay-device", true),
        )
        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.Updated,
            execute(stored, OWNER_ID, true),
        )
    }

    @Test
    fun missingAndNormalMessagesReturnTypedFailures() {
        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.MessageNotFound,
            controller.setHiddenLocally(
                ROOM_ID,
                MESSAGE_ID,
                true,
                OWNER_ID,
                messageLoader = { _, _ -> null },
                hiddenStateUpdater = { _, _, _ -> error("must not update") },
            ),
        )

        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation,
            execute(invitationMessage(body = "ordinary message"), OWNER_ID, true),
        )
    }

    @Test
    fun publicAndMalformedInvitationsAreNotMutablePrivateInvitations() {
        val publicBody = GroupInvitationProtocol.encodePublic(
            PublicGroupInvitationPayload(
                cardId = MESSAGE_ID,
                title = "Group",
                shortDescription = "Description",
                invitationCredential = InvitationCredential.of("credential"),
            )
        )
        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation,
            execute(invitationMessage(body = publicBody), OWNER_ID, true),
        )
        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.InvitationInvalid,
            execute(invitationMessage(body = messageBody().replace(",", ", ")), OWNER_ID, true),
        )
        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.InvitationInvalid,
            execute(
                invitationMessage(messageId = "different-message", body = messageBody()),
                OWNER_ID,
                true,
            ),
        )
    }

    @Test
    fun zeroRowUpdateReReadsStateForRaceOutcome() {
        val original = invitationMessage(hidden = false)
        var stored: NearbyRoomMessageEntity? = original
        var loads = 0

        val alreadyChanged = controller.setHiddenLocally(
            ROOM_ID,
            MESSAGE_ID,
            true,
            OWNER_ID,
            messageLoader = { _, _ -> loads += 1; stored },
            hiddenStateUpdater = { _, _, _ ->
                stored = original.copy(is_hidden_locally = true)
                0
            },
        )
        assertEquals(
            NearbyGroupInvitationLocalVisibilityResult.AlreadyInRequestedState,
            alreadyChanged,
        )
        assertEquals(2, loads)

        stored = original
        loads = 0
        val removed = controller.setHiddenLocally(
            ROOM_ID,
            MESSAGE_ID,
            true,
            OWNER_ID,
            messageLoader = { _, _ -> loads += 1; stored },
            hiddenStateUpdater = { _, _, _ -> stored = null; 0 },
        )
        assertEquals(NearbyGroupInvitationLocalVisibilityResult.MessageNotFound, removed)
        assertEquals(2, loads)
    }

    private fun execute(
        message: NearbyRoomMessageEntity,
        localParticipantId: String,
        hidden: Boolean,
    ): NearbyGroupInvitationLocalVisibilityResult {
        var stored: NearbyRoomMessageEntity? = message
        return controller.setHiddenLocally(
            roomId = message.room_id,
            messageId = message.message_id,
            hidden = hidden,
            localParticipantId = localParticipantId,
            messageLoader = { _, _ -> stored },
            hiddenStateUpdater = { _, _, requested ->
                stored = stored?.copy(is_hidden_locally = requested)
                1
            },
        )
    }

    private fun messageBody(cardId: String = MESSAGE_ID) =
        GroupInvitationProtocol.encodePrivateEnvelope(
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

    private fun invitationMessage(
        messageId: String = MESSAGE_ID,
        body: String = messageBody(),
        ownerId: String? = OWNER_ID,
        senderId: String = OWNER_ID,
        hidden: Boolean = false,
        localRenderSourceBody: String? = "canonical-local-source",
    ) = NearbyRoomMessageEntity(
        room_id = ROOM_ID,
        message_id = messageId,
        owner_device_id = ownerId,
        sender_device_id = senderId,
        sender_display_name = "Sender",
        body = body,
        created_at_ms = 1_000L,
        received_at_ms = 1_001L,
        ttl_expires_at_ms = 10_000L,
        delivery_state = NearbyRoomMessageEntity.DELIVERY_RECEIVED,
        edited_at_ms = null,
        deleted_at_ms = null,
        local_render_source_body = localRenderSourceBody,
        is_hidden_locally = hidden,
    )

    private companion object {
        const val ROOM_ID = "room-1"
        const val MESSAGE_ID = "message-1"
        const val OWNER_ID = "owner-device"
        const val TARGET_ID = "target-device"
        const val KEY_ID = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
