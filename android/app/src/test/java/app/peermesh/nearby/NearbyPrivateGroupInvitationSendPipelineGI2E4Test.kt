package app.peermesh.nearby

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.GroupInvitationDecodedMessage
import app.peermesh.core.groupinvitation.GroupInvitationDecodeResult
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.core.groupinvitation.PrivateGroupInvitationInnerPayload
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.security.groupinvitation.PrivateRecipientCryptoContext
import app.peermesh.security.groupinvitation.PrivateRecipientEncryptedPayload
import app.peermesh.security.groupinvitation.PrivateRecipientEncryptResult
import app.peermesh.security.groupinvitation.RecipientEncryptionIdentity
import app.peermesh.security.groupinvitation.RecipientEncryptionKeyId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPrivateGroupInvitationSendPipelineGI2E4Test {
    @Test
    fun `private send freezes repository context encrypts once persists canonical bodies and submits persisted entity`() {
        val preparation = preparation()
        val persisted = message(body = "persisted-envelope", source = "persisted-inner")
        var preparedAudience: NearbyGroupInvitationAudience? = null
        var encryptCount = 0
        var plaintextReference: ByteArray? = null
        var cryptoIdentity: RecipientEncryptionIdentity? = null
        var cryptoContext: PrivateRecipientCryptoContext? = null
        var insertedPreparation: NearbyGroupInvitationPreparation? = null
        var insertedBody: String? = null
        var insertedSource: String? = null
        var submitted: NearbyRoomMessageEntity? = null

        val pipeline = NearbyPrivateGroupInvitationSendPipeline(
            prepare = { roomId, audience ->
                assertEquals(ROOM_ID, roomId)
                preparedAudience = audience
                preparation
            },
            encrypt = { plaintext, identity, context ->
                encryptCount += 1
                plaintextReference = plaintext
                cryptoIdentity = identity
                cryptoContext = context
                assertEquals(canonicalInner(), plaintext.toString(Charsets.UTF_8))
                PrivateRecipientEncryptResult.Success(encryptedPayload())
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

        assertEquals(NearbyGroupInvitationAudience.Private(TARGET_ID), preparedAudience)
        assertEquals(1, encryptCount)
        val capturedIdentity = requireNotNull(cryptoIdentity)
        assertEquals(ALGORITHM, capturedIdentity.algorithm)
        assertEquals(TARGET_KEY_ID, capturedIdentity.keyId)
        assertArrayEquals(RECIPIENT_PUBLIC_KEY, capturedIdentity.publicKeySec1)
        assertEquals(
            PrivateRecipientCryptoContext(
                protocolVersion = GroupInvitationProtocol.VERSION,
                algorithmWireValue = ALGORITHM.wireValue,
                roomId = ROOM_ID,
                messageId = MESSAGE_ID,
                cardId = MESSAGE_ID,
                logicalSenderId = SENDER_ID,
                targetParticipantId = TARGET_ID,
                targetKeyId = TARGET_KEY_ID,
                effectiveRoomExpiryEpochMs = EXPIRY,
            ),
            cryptoContext,
        )
        val clearedPlaintext = requireNotNull(plaintextReference)
        assertTrue(clearedPlaintext.all { it == 0.toByte() })
        assertSame(preparation, insertedPreparation)
        assertEquals(canonicalInner(), insertedSource)
        val decoded = GroupInvitationProtocol.decode(checkNotNull(insertedBody))
        val envelope = ((decoded as GroupInvitationDecodeResult.Invitation).message as
            GroupInvitationDecodedMessage.PrivateEncryptedInvitation).envelope
        assertEquals(MESSAGE_ID, envelope.cardId)
        assertEquals(TARGET_ID, envelope.targetParticipantId)
        assertEquals(ALGORITHM, envelope.algorithm)
        assertEquals(TARGET_KEY_ID, envelope.targetKeyId)
        assertEquals(Base64Url.encode(EPHEMERAL_PUBLIC_KEY), envelope.ephemeralPublicKey)
        assertEquals(Base64Url.encode(NONCE), envelope.nonce)
        assertEquals(Base64Url.encode(CIPHERTEXT), envelope.ciphertext)
        assertSame(persisted, submitted)
        assertEquals(NearbyPrivateGroupInvitationSendResult.Sent(persisted), result)
    }

    @Test
    fun `transport failure retains inserted ciphertext for retry without second encryption`() {
        val persisted = message()
        var encryptCount = 0
        var insertCount = 0
        var submitCount = 0
        val pipeline = NearbyPrivateGroupInvitationSendPipeline(
            prepare = { _, _ -> preparation() },
            encrypt = { _, _, _ ->
                encryptCount += 1
                PrivateRecipientEncryptResult.Success(encryptedPayload())
            },
            insert = { _, _, _ ->
                insertCount += 1
                PreparedGroupInvitationInsertResult.Inserted(persisted)
            },
            submitPersistedMessage = {
                submitCount += 1
                false
            },
        )

        assertEquals(
            NearbyPrivateGroupInvitationSendResult.PersistedForRetry(persisted),
            pipeline.send(request()),
        )
        assertEquals(1, encryptCount)
        assertEquals(1, insertCount)
        assertEquals(1, submitCount)
    }

    @Test
    fun `crypto failure stops before insertion and transport`() {
        val failures = listOf(
            PrivateRecipientEncryptResult.UnsupportedProtocolVersion to
                NearbyPrivateGroupInvitationSendResult.UnsupportedCryptoProtocol,
            PrivateRecipientEncryptResult.UnsupportedAlgorithm to
                NearbyPrivateGroupInvitationSendResult.UnsupportedCryptoAlgorithm,
            PrivateRecipientEncryptResult.InvalidContext to
                NearbyPrivateGroupInvitationSendResult.InvalidCryptoContext,
            PrivateRecipientEncryptResult.InvalidRecipientIdentity to
                NearbyPrivateGroupInvitationSendResult.InvalidRecipientIdentity,
            PrivateRecipientEncryptResult.KeyAgreementFailed to
                NearbyPrivateGroupInvitationSendResult.KeyAgreementFailed,
            PrivateRecipientEncryptResult.CryptoUnavailable to
                NearbyPrivateGroupInvitationSendResult.CryptoUnavailable,
        )

        failures.forEach { (failure, expected) ->
            var inserted = false
            var submitted = false
            val pipeline = NearbyPrivateGroupInvitationSendPipeline(
                prepare = { _, _ -> preparation() },
                encrypt = { _, _, _ -> failure },
                insert = { _, _, _ -> inserted = true; error("must not insert") },
                submitPersistedMessage = { submitted = true; error("must not submit") },
            )

            assertEquals(expected, pipeline.send(request()))
            assertFalse(inserted)
            assertFalse(submitted)
        }
    }

    @Test
    fun `invalid prepared recipient binding stops before encryption insertion and transport`() {
        val invalidPreparations = listOf(
            preparation(audience = NearbyGroupInvitationAudience.Public),
            preparation(audience = NearbyGroupInvitationAudience.Private("different-target")),
            preparation(recipientEncryptionIdentity = null),
        )

        invalidPreparations.forEach { invalidPreparation ->
            var encryptCount = 0
            var insertCount = 0
            var submitCount = 0
            val pipeline = NearbyPrivateGroupInvitationSendPipeline(
                prepare = { _, _ -> invalidPreparation },
                encrypt = { _, _, _ ->
                    encryptCount += 1
                    PrivateRecipientEncryptResult.Success(encryptedPayload())
                },
                insert = { _, _, _ ->
                    insertCount += 1
                    error("must not insert")
                },
                submitPersistedMessage = {
                    submitCount += 1
                    error("must not submit")
                },
            )

            val expected = if (invalidPreparation.recipientEncryptionIdentity == null) {
                NearbyPrivateGroupInvitationSendResult.InvalidRecipientIdentity
            } else {
                NearbyPrivateGroupInvitationSendResult.InvalidPreparation
            }
            assertEquals(expected, pipeline.send(request()))
            assertEquals(0, encryptCount)
            assertEquals(0, insertCount)
            assertEquals(0, submitCount)
        }
    }

    @Test
    fun `inconsistent GI2D success metadata is rejected before insertion and transport`() {
        val mismatches = listOf(
            encryptedPayload(algorithmWireValue = "unexpected-algorithm"),
            encryptedPayload(targetKeyId = RecipientEncryptionKeyId.derive(ALGORITHM, EPHEMERAL_PUBLIC_KEY)),
        )

        mismatches.forEach { encryptedPayload ->
            var inserted = false
            var submitted = false
            val pipeline = NearbyPrivateGroupInvitationSendPipeline(
                prepare = { _, _ -> preparation() },
                encrypt = { _, _, _ -> PrivateRecipientEncryptResult.Success(encryptedPayload) },
                insert = { _, _, _ -> inserted = true; error("must not insert") },
                submitPersistedMessage = { submitted = true; error("must not submit") },
            )

            assertEquals(
                NearbyPrivateGroupInvitationSendResult.InvalidCryptoContext,
                pipeline.send(request()),
            )
            assertFalse(inserted)
            assertFalse(submitted)
        }
    }

    @Test
    fun `invalid semantic input stops before preparation and encryption`() {
        var prepared = false
        var encrypted = false
        val pipeline = NearbyPrivateGroupInvitationSendPipeline(
            prepare = { _, _ -> prepared = true; preparation() },
            encrypt = { _, _, _ -> encrypted = true; PrivateRecipientEncryptResult.Success(encryptedPayload()) },
            insert = { _, _, _ -> error("must not insert") },
            submitPersistedMessage = { error("must not submit") },
        )

        assertEquals(
            NearbyPrivateGroupInvitationSendResult.InvalidInvitationContent,
            pipeline.send(request().copy(targetParticipantId = "")),
        )
        assertFalse(prepared)
        assertFalse(encrypted)
    }

    @Test
    fun `request exposes only semantic caller owned fields`() {
        val propertyNames = NearbyPrivateGroupInvitationSendRequest::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf("roomId", "targetParticipantId", "title", "shortDescription", "invitationCredential"),
            propertyNames,
        )
        assertTrue(propertyNames.none { it.contains("card", ignoreCase = true) })
        assertTrue(propertyNames.none { it.contains("message", ignoreCase = true) })
        assertTrue(propertyNames.none { it.contains("sender", ignoreCase = true) })
        assertTrue(propertyNames.none { it.contains("expiry", ignoreCase = true) })
        assertTrue(propertyNames.none { it.contains("nonce", ignoreCase = true) })
        assertTrue(propertyNames.none { it.contains("key", ignoreCase = true) })
    }

    private fun request() = NearbyPrivateGroupInvitationSendRequest(
        roomId = ROOM_ID,
        targetParticipantId = TARGET_ID,
        title = TITLE,
        shortDescription = DESCRIPTION,
        invitationCredential = CREDENTIAL,
    )

    private fun preparation(
        audience: NearbyGroupInvitationAudience = NearbyGroupInvitationAudience.Private(TARGET_ID),
        recipientEncryptionIdentity: NearbyRecipientEncryptionPublicIdentity? =
            NearbyRecipientEncryptionPublicIdentity(
                algorithm = ALGORITHM,
                keyId = TARGET_KEY_ID,
                publicKeySec1 = RECIPIENT_PUBLIC_KEY,
            ),
    ) = object : NearbyGroupInvitationPreparation {
        override val roomId = ROOM_ID
        override val messageId = MESSAGE_ID
        override val logicalSenderId = SENDER_ID
        override val senderDisplayName: String? = "Sender"
        override val createdAtMs = 100L
        override val effectiveExpiryAtMs = EXPIRY
        override val audience: NearbyGroupInvitationAudience = audience
        override val recipientEncryptionIdentity = recipientEncryptionIdentity
    }

    private fun encryptedPayload(
        algorithmWireValue: String = ALGORITHM.wireValue,
        targetKeyId: String = TARGET_KEY_ID,
    ) = PrivateRecipientEncryptedPayload(
        algorithmWireValue = algorithmWireValue,
        targetKeyId = targetKeyId,
        ephemeralPublicKeySec1 = EPHEMERAL_PUBLIC_KEY,
        nonce = NONCE,
        ciphertextAndTag = CIPHERTEXT,
    )

    private fun canonicalInner() = GroupInvitationProtocol.encodePrivateInnerPayload(
        PrivateGroupInvitationInnerPayload(TITLE, DESCRIPTION, CREDENTIAL)
    )

    private fun message(
        body: String = "canonical-private-envelope",
        source: String = canonicalInner(),
    ) = NearbyRoomMessageEntity(
        room_id = ROOM_ID,
        message_id = MESSAGE_ID,
        reply_to = null,
        owner_device_id = SENDER_ID,
        sender_device_id = SENDER_ID,
        sender_display_name = "Sender",
        body = body,
        created_at_ms = 100L,
        received_at_ms = 101L,
        ttl_expires_at_ms = EXPIRY,
        delivery_state = NearbyRoomMessageEntity.DELIVERY_LOCAL,
        edited_at_ms = null,
        deleted_at_ms = null,
        local_render_source_body = source,
        is_hidden_locally = false,
    )

    private companion object {
        const val ROOM_ID = "room-1"
        const val MESSAGE_ID = "message-1"
        const val SENDER_ID = "sender-1"
        const val TARGET_ID = "participant-2"
        const val TITLE = "Join our private group"
        const val DESCRIPTION = "Private invitation"
        const val EXPIRY = 10_000L
        val CREDENTIAL = InvitationCredential.of("opaque-credential")
        val ALGORITHM = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM
        val RECIPIENT_PUBLIC_KEY = hex(
            "04" +
                "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
                "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
        )
        val TARGET_KEY_ID = RecipientEncryptionKeyId.derive(ALGORITHM, RECIPIENT_PUBLIC_KEY)
        val EPHEMERAL_PUBLIC_KEY = hex(
            "04" +
                "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978" +
                "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"
        )
        val NONCE = ByteArray(12) { it.toByte() }
        val CIPHERTEXT = ByteArray(16) { (it + 1).toByte() }

        fun hex(value: String): ByteArray =
            value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
