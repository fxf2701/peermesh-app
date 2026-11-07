package app.peermesh.nearby.presentation

import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.nearby.NearbyPrivateGroupInvitationSendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPrivateInvitationComposerGI2E6Test {
    @Test
    fun validationAndDraftAreBounded() {
        val valid = NearbyPrivateInvitationComposerState("g", "  Title  ", "  Description  ")
        assertTrue(valid.canSend)
        assertEquals(
            NearbyPrivateInvitationDraft("g", "Title", "Description"),
            valid.toDraft(),
        )

        assertTrue(valid.copy(title = "x".repeat(40)).canSend)
        assertTrue(valid.copy(description = "x".repeat(60)).canSend)
        assertFalse(valid.copy(selectedGroupId = null).canSend)
        assertFalse(valid.copy(title = "   ").canSend)
        assertFalse(valid.copy(title = "x".repeat(41)).canSend)
        assertFalse(valid.copy(description = "x".repeat(61)).canSend)
        assertFalse(valid.copy(sending = true).canSend)
        assertFalse(valid.copy(alreadySent = true).canSend)
        assertNull(valid.copy(title = "   ").toDraft())
    }

    @Test
    fun optionalDescriptionProducesImmutableSemanticDraft() {
        val state = NearbyPrivateInvitationComposerState("group-id", "Title", "")
        val draft = state.toDraft()
        assertEquals(NearbyPrivateInvitationDraft("group-id", "Title", ""), draft)
        assertEquals("Title", draft?.title)
        assertEquals("", draft?.description)
    }

    @Test
    fun sendOutcomesPreserveInsertBoundary() {
        val message = NearbyRoomMessageEntity(
            "r", "m", null, "o", "o", null, "b", 1, 1, 2, "local", null, null,
        )
        assertEquals(
            NearbyPrivateInvitationComposerOutcome.Sent,
            NearbyPrivateInvitationComposerOutcome.from(
                NearbyPrivateGroupInvitationSendResult.Sent(message)
            ),
        )
        assertEquals(
            NearbyPrivateInvitationComposerOutcome.PersistedForRetry,
            NearbyPrivateInvitationComposerOutcome.from(
                NearbyPrivateGroupInvitationSendResult.PersistedForRetry(message)
            ),
        )
        assertEquals(
            NearbyPrivateInvitationComposerOutcome.SendLimitReached,
            NearbyPrivateInvitationComposerOutcome.from(
                NearbyPrivateGroupInvitationSendResult.SendLimitReached
            ),
        )

        val preInsertFailures = listOf(
            NearbyPrivateGroupInvitationSendResult.PreparationUnavailable,
            NearbyPrivateGroupInvitationSendResult.InvalidInvitationContent,
            NearbyPrivateGroupInvitationSendResult.InvalidRecipientIdentity,
            NearbyPrivateGroupInvitationSendResult.UnsupportedCryptoProtocol,
            NearbyPrivateGroupInvitationSendResult.UnsupportedCryptoAlgorithm,
            NearbyPrivateGroupInvitationSendResult.InvalidCryptoContext,
            NearbyPrivateGroupInvitationSendResult.KeyAgreementFailed,
            NearbyPrivateGroupInvitationSendResult.CryptoUnavailable,
            NearbyPrivateGroupInvitationSendResult.InvalidPreparation,
            NearbyPrivateGroupInvitationSendResult.StalePreparation,
            NearbyPrivateGroupInvitationSendResult.RoomUnavailable,
            NearbyPrivateGroupInvitationSendResult.SenderMismatch,
            NearbyPrivateGroupInvitationSendResult.SenderMuted,
            NearbyPrivateGroupInvitationSendResult.MessageIdCollision,
        )
        preInsertFailures.forEach { result ->
            assertEquals(
                NearbyPrivateInvitationComposerOutcome.RetryableCreationFailure,
                NearbyPrivateInvitationComposerOutcome.from(result),
            )
        }
    }
    @Test
    fun authorityPassesSemanticDraftExactlyOnceToExistingSendPipeline() {
        val credential = app.peermesh.core.groupinvitation.InvitationCredential.of("credential")
        val captured = mutableListOf<app.peermesh.nearby.NearbyPrivateGroupInvitationSendRequest>()
        val authority = app.peermesh.nearby.NearbyPrivateInvitationComposerAuthority(
            localDeviceIdProvider = { "local-actor" },
            loadEligibleGroupOptions = { actor ->
                assertEquals("local-actor", actor)
                listOf(app.peermesh.nearby.NearbyPrivateInvitationGroupOption("group-id", "Group"))
            },
            resolveInvitationCredential = { actor, groupId ->
                assertEquals("local-actor", actor)
                assertEquals("group-id", groupId)
                credential
            },
            sendPrivateInvitation = { request ->
                captured += request
                app.peermesh.nearby.NearbyPrivateGroupInvitationSendResult.PreparationUnavailable
            },
        )

        assertEquals(
            listOf(app.peermesh.nearby.NearbyPrivateInvitationGroupOption("group-id", "Group")),
            authority.loadEligibleGroups(),
        )
        val result = authority.send(
            roomId = "room-id",
            targetParticipantId = "target-id",
            draft = NearbyPrivateInvitationDraft("group-id", "Title", "Description"),
        )

        assertEquals(app.peermesh.nearby.NearbyPrivateGroupInvitationSendResult.PreparationUnavailable, result)
        assertEquals(1, captured.size)
        val request = captured.single()
        assertEquals("room-id", request.roomId)
        assertEquals("target-id", request.targetParticipantId)
        assertEquals("Title", request.title)
        assertEquals("Description", request.shortDescription)
        assertEquals(credential, request.invitationCredential)
    }

    @Test
    fun authorityRejectsUnavailableInviteMaterialBeforePipelineSend() {
        var sendCalls = 0
        val authority = app.peermesh.nearby.NearbyPrivateInvitationComposerAuthority(
            localDeviceIdProvider = { "local-actor" },
            loadEligibleGroupOptions = { emptyList() },
            resolveInvitationCredential = { _, _ -> null },
            sendPrivateInvitation = {
                sendCalls += 1
                app.peermesh.nearby.NearbyPrivateGroupInvitationSendResult.PreparationUnavailable
            },
        )

        val result = authority.send(
            roomId = "room-id",
            targetParticipantId = "target-id",
            draft = NearbyPrivateInvitationDraft("stale-group", "Title", ""),
        )

        assertEquals(app.peermesh.nearby.NearbyPrivateGroupInvitationSendResult.PreparationUnavailable, result)
        assertEquals(0, sendCalls)
    }

}
