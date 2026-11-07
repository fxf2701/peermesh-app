package app.peermesh.nearby

import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.core.groupinvitation.PublicGroupInvitationPayload
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPublicGroupInvitationSendPipelineGI2E3Test {
    @Test
    fun `public send uses repository preparation canonical body null source and persisted entity`() {
        val preparation = preparation()
        val persisted = message(body = "persisted-body")
        var preparedAudience: NearbyGroupInvitationAudience? = null
        var insertedPreparation: NearbyGroupInvitationPreparation? = null
        var insertedBody: String? = null
        var insertedSource: String? = "not-null"
        var submitted: NearbyRoomMessageEntity? = null

        val pipeline = NearbyPublicGroupInvitationSendPipeline(
            prepare = { roomId, audience ->
                assertEquals(ROOM_ID, roomId)
                preparedAudience = audience
                preparation
            },
            insert = { claimed, body, source ->
                insertedPreparation = claimed
                insertedBody = body
                insertedSource = source
                PreparedGroupInvitationInsertResult.Inserted(persisted)
            },
            submitPersistedMessage = {
                submitted = it
                true
            },
        )

        val result = pipeline.send(request())

        assertEquals(NearbyGroupInvitationAudience.Public, preparedAudience)
        assertSame(preparation, insertedPreparation)
        assertEquals(
            GroupInvitationProtocol.encodePublic(
                PublicGroupInvitationPayload(MESSAGE_ID, TITLE, DESCRIPTION, CREDENTIAL)
            ),
            insertedBody,
        )
        assertNull(insertedSource)
        assertSame(persisted, submitted)
        assertEquals(NearbyPublicGroupInvitationSendResult.Sent(persisted), result)
    }

    @Test
    fun `transport failure retains inserted message for existing retry flow`() {
        val persisted = message()
        var prepareCount = 0
        var insertCount = 0
        var submitCount = 0
        val pipeline = NearbyPublicGroupInvitationSendPipeline(
            prepare = { _, _ -> prepareCount += 1; preparation() },
            insert = { _, _, _ -> insertCount += 1; PreparedGroupInvitationInsertResult.Inserted(persisted) },
            submitPersistedMessage = { submitCount += 1; false },
        )

        val result = pipeline.send(request())

        assertEquals(NearbyPublicGroupInvitationSendResult.PersistedForRetry(persisted), result)
        assertEquals(1, prepareCount)
        assertEquals(1, insertCount)
        assertEquals(1, submitCount)
    }

    @Test
    fun `preparation unavailable stops before insertion and transport`() {
        var inserted = false
        var submitted = false
        val pipeline = NearbyPublicGroupInvitationSendPipeline(
            prepare = { _, _ -> null },
            insert = { _, _, _ -> inserted = true; error("must not insert") },
            submitPersistedMessage = { submitted = true; error("must not submit") },
        )

        assertEquals(NearbyPublicGroupInvitationSendResult.PreparationUnavailable, pipeline.send(request()))
        assertFalse(inserted)
        assertFalse(submitted)
    }

    @Test
    fun `invalid semantic content stops before repository authority is consumed`() {
        var prepared = false
        val pipeline = NearbyPublicGroupInvitationSendPipeline(
            prepare = { _, _ -> prepared = true; preparation() },
            insert = { _, _, _ -> error("must not insert") },
            submitPersistedMessage = { error("must not submit") },
        )

        val result = pipeline.send(request().copy(title = ""))

        assertEquals(NearbyPublicGroupInvitationSendResult.InvalidInvitationContent, result)
        assertFalse(prepared)
    }

    @Test
    fun `all non inserted repository outcomes stop before transport and map exactly`() {
        val mappings = listOf(
            PreparedGroupInvitationInsertResult.InvalidPreparation to NearbyPublicGroupInvitationSendResult.InvalidPreparation,
            PreparedGroupInvitationInsertResult.InvalidPreparedContent to NearbyPublicGroupInvitationSendResult.InvalidInvitationContent,
            PreparedGroupInvitationInsertResult.StalePreparation to NearbyPublicGroupInvitationSendResult.StalePreparation,
            PreparedGroupInvitationInsertResult.RoomUnavailable to NearbyPublicGroupInvitationSendResult.RoomUnavailable,
            PreparedGroupInvitationInsertResult.SenderMismatch to NearbyPublicGroupInvitationSendResult.SenderMismatch,
            PreparedGroupInvitationInsertResult.SenderMuted to NearbyPublicGroupInvitationSendResult.SenderMuted,
            PreparedGroupInvitationInsertResult.SendLimitReached to NearbyPublicGroupInvitationSendResult.SendLimitReached,
            PreparedGroupInvitationInsertResult.MessageIdCollision to NearbyPublicGroupInvitationSendResult.MessageIdCollision,
        )

        mappings.forEach { (repositoryResult, expected) ->
            var submitted = false
            val pipeline = NearbyPublicGroupInvitationSendPipeline(
                prepare = { _, _ -> preparation() },
                insert = { _, _, _ -> repositoryResult },
                submitPersistedMessage = { submitted = true; true },
            )

            assertEquals(expected, pipeline.send(request()))
            assertFalse(submitted)
        }
    }

    @Test
    fun `request exposes no authoritative message sender or expiry fields`() {
        val propertyNames = NearbyPublicGroupInvitationSendRequest::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("roomId", "title", "shortDescription", "invitationCredential"),
            propertyNames,
        )
        assertTrue(propertyNames.none { it.contains("card", ignoreCase = true) })
        assertTrue(propertyNames.none { it.contains("message", ignoreCase = true) })
        assertTrue(propertyNames.none { it.contains("sender", ignoreCase = true) })
        assertTrue(propertyNames.none { it.contains("expiry", ignoreCase = true) })
    }

    private fun request() = NearbyPublicGroupInvitationSendRequest(
        roomId = ROOM_ID,
        title = TITLE,
        shortDescription = DESCRIPTION,
        invitationCredential = CREDENTIAL,
    )

    private fun preparation() = object : NearbyGroupInvitationPreparation {
        override val roomId = ROOM_ID
        override val messageId = MESSAGE_ID
        override val logicalSenderId = SENDER_ID
        override val senderDisplayName: String? = "Sender"
        override val createdAtMs = 100L
        override val effectiveExpiryAtMs = 1_000L
        override val audience: NearbyGroupInvitationAudience = NearbyGroupInvitationAudience.Public
        override val recipientEncryptionIdentity: NearbyRecipientEncryptionPublicIdentity? = null
    }

    private fun message(body: String = "canonical-body") = NearbyRoomMessageEntity(
        room_id = ROOM_ID,
        message_id = MESSAGE_ID,
        reply_to = null,
        owner_device_id = SENDER_ID,
        sender_device_id = SENDER_ID,
        sender_display_name = "Sender",
        body = body,
        created_at_ms = 100L,
        received_at_ms = 101L,
        ttl_expires_at_ms = 1_000L,
        delivery_state = NearbyRoomMessageEntity.DELIVERY_LOCAL,
        edited_at_ms = null,
        deleted_at_ms = null,
        local_render_source_body = null,
        is_hidden_locally = false,
    )

    private companion object {
        const val ROOM_ID = "room-1"
        const val MESSAGE_ID = "message-1"
        const val SENDER_ID = "sender-1"
        const val TITLE = "Join our group"
        const val DESCRIPTION = "Public invitation"
        val CREDENTIAL = InvitationCredential.of("opaque-credential")
    }
}
