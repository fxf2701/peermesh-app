package app.peermesh.core.groupinvitation

import app.peermesh.core.encoding.Base64Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInvitationDomainTest {
    @Test
    fun `template defensively snapshots presentation options`() {
        val mutableOptions = mutableSetOf("compact")
        val template = template(presentationOptions = mutableOptions)
        mutableOptions += "mutated"

        assertEquals(setOf("compact"), template.presentationOptions)
    }

    @Test
    fun `card lifetime is bounded by room expiry`() {
        val card = InvitationCardInstance.fromTemplate(
            cardId = "card-1",
            template = template(),
            roomId = "room-1",
            audience = InvitationAudience.Room,
            invitationCredential = credential(),
            createdAtMs = 1_000L,
            roomExpiresAtMs = 2_000L,
        )

        assertTrue(card.isActive(roomIsActive = true, nowMs = 1_999L))
        assertFalse(card.isActive(roomIsActive = true, nowMs = 2_000L))
        assertFalse(card.isActive(roomIsActive = false, nowMs = 1_500L))
    }

    @Test
    fun `visibility permits room and only sender or target for participant`() {
        val privateAudience = InvitationAudience.Participant("participant-b")

        assertTrue(GroupInvitationPolicy.isVisibleTo(InvitationAudience.Room, "a", "c"))
        assertTrue(GroupInvitationPolicy.isVisibleTo(privateAudience, "participant-a", "participant-a"))
        assertTrue(GroupInvitationPolicy.isVisibleTo(privateAudience, "participant-a", "participant-b"))
        assertFalse(GroupInvitationPolicy.isVisibleTo(privateAudience, "participant-a", "participant-c"))
    }

    @Test
    fun `bounded identifiers and timestamps reject invalid values early`() {
        assertThrows(IllegalArgumentException::class.java) {
            InvitationCardInstance.fromTemplate(
                cardId = "x".repeat(GroupInvitationLimits.MAX_CARD_ID_UTF8_BYTES + 1),
                template = template(), roomId = "room", audience = InvitationAudience.Room,
                invitationCredential = credential(), createdAtMs = 1L, roomExpiresAtMs = 2L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            InvitationAudience.Participant("x".repeat(GroupInvitationLimits.MAX_PARTICIPANT_ID_UTF8_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            envelope(targetKeyId = "x".repeat(GroupInvitationLimits.MAX_TARGET_KEY_ID_UTF8_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InvitationCardInstance.fromTemplate(
                cardId = "card", template = template(), roomId = "room", audience = InvitationAudience.Room,
                invitationCredential = credential(), createdAtMs = 2L, roomExpiresAtMs = 2L,
            )
        }
    }

    @Test
    fun `presentation byte limits are enforced independently of character limits`() {
        assertEquals(
            "€".repeat(160),
            PublicGroupInvitationPayload("card", "Title", "€".repeat(160), credential()).shortDescription,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PublicGroupInvitationPayload("card", "Title", "€".repeat(161), credential())
        }
        assertThrows(IllegalArgumentException::class.java) {
            InvitationCredential.of("😀".repeat(129))
        }
    }

    @Test
    fun `credential equality is stable and every diagnostic string redacts it`() {
        val secret = "credential-not-for-logs"
        val credential = InvitationCredential.of(secret)
        val card = InvitationCardInstance.fromTemplate(
            cardId = "card", template = template(), roomId = "room", audience = InvitationAudience.Room,
            invitationCredential = credential, createdAtMs = 1L, roomExpiresAtMs = 2L,
        )
        val publicPayload = PublicGroupInvitationPayload("card", "Title", "", credential)
        val innerPayload = PrivateGroupInvitationInnerPayload("Title", "", credential)
        val encryptedEnvelope = envelope(ciphertext = "ciphertext-not-for-logs")

        assertEquals(credential, InvitationCredential.of(secret))
        assertEquals(credential.hashCode(), InvitationCredential.of(secret).hashCode())
        assertNotEquals(secret, credential.toString())
        listOf(credential, card, publicPayload, innerPayload, encryptedEnvelope).forEach {
            assertFalse(it.toString().contains(secret))
        }
        assertFalse(encryptedEnvelope.toString().contains("ciphertext-not-for-logs"))
    }

    private fun template(presentationOptions: Set<String> = setOf("compact")) = InvitationTemplate(
        groupId = "group-1", title = "Emergency volunteers", shortDescription = "Coordinate supplies",
        icon = "group_default", presentationOptions = presentationOptions,
    )

    private fun credential() = InvitationCredential.of("opaque-credential")

    private fun envelope(
        targetKeyId: String = "target-key-1",
        ciphertext: String = Base64Url.encode(ByteArray(32) { 7 }),
    ) = PrivateGroupInvitationEnvelope(
        cardId = "card-1",
        targetParticipantId = "participant-b",
        algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM,
        targetKeyId = targetKeyId,
        ephemeralPublicKey = Base64Url.encode(validUncompressedP256Point()),
        nonce = Base64Url.encode(ByteArray(GroupInvitationLimits.AES_GCM_NONCE_BYTES) { 2 }),
        ciphertext = ciphertext,
    )

    private fun validUncompressedP256Point(): ByteArray =
        ByteArray(GroupInvitationLimits.P256_UNCOMPRESSED_PUBLIC_KEY_BYTES).also { it[0] = 0x04 }
}
