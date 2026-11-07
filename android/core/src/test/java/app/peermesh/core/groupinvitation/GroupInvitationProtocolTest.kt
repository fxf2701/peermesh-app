package app.peermesh.core.groupinvitation

import app.peermesh.core.encoding.Base64Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupInvitationProtocolTest {
    @Test
    fun `public payload has exact canonical shape and round trips`() {
        val payload = publicPayload(description = "Short description")
        val encoded = GroupInvitationProtocol.encodePublic(payload)

        assertEquals(
            "{\"a\":\"r\",\"c\":\"opaque-credential\",\"d\":\"Short description\",\"i\":\"card-123\",\"n\":\"Group title\",\"t\":\"gi\",\"v\":1}",
            encoded,
        )
        assertEquals(
            GroupInvitationDecodeResult.Invitation(GroupInvitationDecodedMessage.PublicInvitation(payload)),
            GroupInvitationProtocol.decode(encoded),
        )
    }

    @Test
    fun `public empty optional description is omitted and round trips`() {
        val payload = publicPayload(description = "")
        val encoded = GroupInvitationProtocol.encodePublic(payload)

        assertFalse(encoded.contains("\"d\""))
        assertEquals(
            GroupInvitationDecodeResult.Invitation(GroupInvitationDecodedMessage.PublicInvitation(payload)),
            GroupInvitationProtocol.decode(encoded),
        )
    }

    @Test
    fun `public optional description accepts absent or string and rejects every wrong json type`() {
        val withoutDescription = "{\"a\":\"r\",\"c\":\"opaque-credential\",\"i\":\"card-123\",\"n\":\"Group title\",\"t\":\"gi\",\"v\":1}"
        val withEmptyDescription = withoutDescription.dropLast(1) + ",\"d\":\"\"}"
        val withDescription = withoutDescription.dropLast(1) + ",\"d\":\"Description\"}"

        assertTrue(GroupInvitationProtocol.decode(withoutDescription) is GroupInvitationDecodeResult.Invitation)
        assertTrue(GroupInvitationProtocol.decode(withEmptyDescription) is GroupInvitationDecodeResult.Invitation)
        assertTrue(GroupInvitationProtocol.decode(withDescription) is GroupInvitationDecodeResult.Invitation)
        listOf("123", "true", "null", "{}", "[]").forEach { invalidValue ->
            assertEquals(
                GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
                GroupInvitationProtocol.decode(withoutDescription.dropLast(1) + ",\"d\":$invalidValue}"),
            )
        }
    }

    @Test
    fun `public shape rejects malformed forbidden and private fields`() {
        listOf(
            "{\"a\":\"r\",\"c\":\"secret\",\"i\":\"card\",\"t\":\"gi\",\"v\":1}",
            "{\"a\":\"r\",\"c\":4,\"i\":\"card\",\"n\":\"Group\",\"t\":\"gi\",\"v\":1}",
            "{\"a\":\"r\",\"c\":\"secret\",\"i\":\"card\",\"n\":\"Group\",\"t\":\"gi\",\"to\":\"target\",\"v\":1}",
            "{\"a\":\"r\",\"c\":\"secret\",\"ct\":\"opaque\",\"i\":\"card\",\"n\":\"Group\",\"t\":\"gi\",\"v\":1}",
        ).forEach { raw ->
            assertEquals(GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation, GroupInvitationProtocol.decode(raw))
        }
    }

    @Test
    fun `private envelope has exact canonical shape and never contains plaintext`() {
        val envelope = privateEnvelope()
        val encoded = GroupInvitationProtocol.encodePrivateEnvelope(envelope)

        assertEquals(
            "{\"a\":\"p\",\"alg\":\"p256-hkdf-sha256-a256gcm\",\"ct\":\"${envelope.ciphertext}\",\"epk\":\"${envelope.ephemeralPublicKey}\",\"i\":\"card-123\",\"iv\":\"${envelope.nonce}\",\"kid\":\"target-key-1\",\"t\":\"gi\",\"to\":\"participant-b\",\"v\":1}",
            encoded,
        )
        assertFalse(encoded.contains("Group title"))
        assertFalse(encoded.contains("Short description"))
        assertFalse(encoded.contains("opaque-credential"))
        assertEquals(
            GroupInvitationDecodeResult.Invitation(GroupInvitationDecodedMessage.PrivateEncryptedInvitation(envelope)),
            GroupInvitationProtocol.decode(encoded),
        )
    }

    @Test
    fun `private inner payload is compact and remains codec-local plaintext`() {
        val inner = PrivateGroupInvitationInnerPayload("Group title", "Short description", credential())
        val encoded = GroupInvitationProtocol.encodePrivateInnerPayload(inner)

        assertEquals("{\"c\":\"opaque-credential\",\"d\":\"Short description\",\"n\":\"Group title\"}", encoded)
        assertEquals(inner, GroupInvitationProtocol.decodePrivateInnerPayload(encoded))
    }

    @Test
    fun `private inner optional description is strict`() {
        val withoutDescription = "{\"c\":\"opaque-credential\",\"n\":\"Group title\"}"

        assertEquals(
            PrivateGroupInvitationInnerPayload("Group title", "", credential()),
            GroupInvitationProtocol.decodePrivateInnerPayload(withoutDescription),
        )
        assertEquals(
            PrivateGroupInvitationInnerPayload("Group title", "", credential()),
            GroupInvitationProtocol.decodePrivateInnerPayload(withoutDescription.dropLast(1) + ",\"d\":\"\"}"),
        )
        assertEquals(
            PrivateGroupInvitationInnerPayload("Group title", "Description", credential()),
            GroupInvitationProtocol.decodePrivateInnerPayload(withoutDescription.dropLast(1) + ",\"d\":\"Description\"}"),
        )
        listOf("123", "true", "null", "{}", "[]").forEach { invalidValue ->
            assertEquals(null, GroupInvitationProtocol.decodePrivateInnerPayload(withoutDescription.dropLast(1) + ",\"d\":$invalidValue}"))
        }
    }

    @Test
    fun `private shape requires every crypto field and rejects public content`() {
        val valid = GroupInvitationProtocol.encodePrivateEnvelope(privateEnvelope())
        listOf("to", "alg", "kid", "epk", "iv", "ct").forEach { field ->
            val withoutField = valid.replace(Regex("\\\"$field\\\":(\\\"[^\\\"]*\\\"|[0-9]+),?"), "")
            assertEquals(GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation, GroupInvitationProtocol.decode(withoutField))
        }
        listOf("n", "d", "c").forEach { field ->
            val withPlaintext = valid.dropLast(1) + ",\"$field\":\"plaintext\"}"
            assertEquals(GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation, GroupInvitationProtocol.decode(withPlaintext))
        }
    }

    @Test
    fun `public shape rejects each private only field`() {
        val valid = GroupInvitationProtocol.encodePublic(publicPayload())

        listOf("to", "alg", "kid", "epk", "iv", "ct").forEach { field ->
            val malformed = valid.dropLast(1) + ",\"$field\":\"opaque\"}"
            assertEquals(GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation, GroupInvitationProtocol.decode(malformed))
        }
    }

    @Test
    fun `private rejects malformed and oversized cryptographic fields`() {
        val valid = GroupInvitationProtocol.encodePrivateEnvelope(privateEnvelope())
        val malformed = valid.replace("\"epk\":\"", "\"epk\":\"*")
        assertEquals(GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation, GroupInvitationProtocol.decode(malformed))
        assertEquals(
            GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
            GroupInvitationProtocol.decode(valid.replace("\"ct\":\"", "\"ct\":\"${"a".repeat(GroupInvitationLimits.MAX_PRIVATE_CIPHERTEXT_BASE64URL_CHARS + 1)}")),
        )
    }

    @Test
    fun `private crypto boundaries enforce SEC1 point and GCM tag shape`() {
        assertTrue(privateEnvelope().ephemeralPublicKey.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            privateEnvelope(ephemeralPublicKey = Base64Url.encode(ByteArray(GroupInvitationLimits.P256_UNCOMPRESSED_PUBLIC_KEY_BYTES) { 1 }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            privateEnvelope(ephemeralPublicKey = Base64Url.encode(ByteArray(64).also { it[0] = 0x04 }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            privateEnvelope(ephemeralPublicKey = Base64Url.encode(ByteArray(66).also { it[0] = 0x04 }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            privateEnvelope(ephemeralPublicKey = "*".repeat(GroupInvitationLimits.P256_UNCOMPRESSED_PUBLIC_KEY_BASE64URL_CHARS))
        }
        assertThrows(IllegalArgumentException::class.java) {
            privateEnvelope(ciphertext = Base64Url.encode(ByteArray(15)))
        }
        assertTrue(privateEnvelope(ciphertext = Base64Url.encode(ByteArray(16))).ciphertext.isNotBlank())
        assertThrows(IllegalArgumentException::class.java) { privateEnvelope(ciphertext = "") }
        assertThrows(IllegalArgumentException::class.java) { privateEnvelope(ciphertext = "YWJjZA==") }
    }

    @Test
    fun `compatibility retains text unrelated json and additive fields`() {
        assertEquals(GroupInvitationDecodeResult.NotGroupInvitation, GroupInvitationProtocol.decode("hello nearby room"))
        assertEquals(GroupInvitationDecodeResult.NotGroupInvitation, GroupInvitationProtocol.decode("{\"body\":\"still text\"}"))
        val additive = GroupInvitationProtocol.encodePublic(publicPayload()).dropLast(1) + ",\"future\":true}"
        assertTrue(GroupInvitationProtocol.decode(additive) is GroupInvitationDecodeResult.Invitation)
        assertEquals(
            GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
            GroupInvitationProtocol.decode("{\"a\":\"r\",\"c\":\"secret\",\"i\":\"card\",\"n\":\"Group\",\"t\":\"gi\",\"v\":2}"),
        )
    }

    @Test
    fun `version must be the exact integer json value one`() {
        listOf("\"1\"", "1.0", "true", "null").forEach { invalidVersion ->
            assertEquals(
                GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation,
                GroupInvitationProtocol.decode(
                    "{\"a\":\"r\",\"c\":\"opaque-credential\",\"i\":\"card\",\"n\":\"Group\",\"t\":\"gi\",\"v\":$invalidVersion}",
                ),
            )
        }
    }

    @Test
    fun `public and private wire budgets reject oversized recognized envelopes`() {
        val oversizedPublic =
            "{\"a\":\"r\",\"c\":\"secret\",\"future\":\"${"x".repeat(GroupInvitationLimits.MAX_PUBLIC_WIRE_PAYLOAD_BYTES)}\",\"i\":\"card\",\"n\":\"Group\",\"t\":\"gi\",\"v\":1}"
        val oversizedPrivate =
            "{\"a\":\"p\",\"alg\":\"p256-hkdf-sha256-a256gcm\",\"ct\":\"a\",\"epk\":\"a\",\"future\":\"${"x".repeat(GroupInvitationLimits.MAX_PRIVATE_WIRE_PAYLOAD_BYTES)}\",\"i\":\"card\",\"iv\":\"a\",\"kid\":\"key\",\"t\":\"gi\",\"to\":\"target\",\"v\":1}"

        assertEquals(GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation, GroupInvitationProtocol.decode(oversizedPublic))
        assertEquals(GroupInvitationDecodeResult.UnsupportedOrInvalidInvitation, GroupInvitationProtocol.decode(oversizedPrivate))
    }

    @Test
    fun `representative maximum private crypto wrapper remains inside its wire budget`() {
        val envelope = PrivateGroupInvitationEnvelope(
            cardId = "i".repeat(GroupInvitationLimits.MAX_CARD_ID_UTF8_BYTES),
            targetParticipantId = "p".repeat(GroupInvitationLimits.MAX_PARTICIPANT_ID_UTF8_BYTES),
            algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM,
            targetKeyId = "k".repeat(GroupInvitationLimits.MAX_TARGET_KEY_ID_UTF8_BYTES),
            ephemeralPublicKey = Base64Url.encode(validUncompressedP256Point()),
            nonce = Base64Url.encode(ByteArray(GroupInvitationLimits.AES_GCM_NONCE_BYTES)),
            ciphertext = Base64Url.encode(ByteArray(1_296)),
        )

        assertTrue(
            GroupInvitationProtocol.encodePrivateEnvelope(envelope).toByteArray().size <=
                GroupInvitationLimits.MAX_PRIVATE_WIRE_PAYLOAD_BYTES,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `final canonical payload budget rejects valid fields whose escaping expands wire size`() {
        val escapingCredential = InvitationCredential.of("\\".repeat(GroupInvitationLimits.MAX_CREDENTIAL_UTF8_BYTES))
        GroupInvitationProtocol.encodePublic(
            PublicGroupInvitationPayload(
                cardId = "id".repeat(64),
                title = "€".repeat(80),
                shortDescription = "€".repeat(160),
                invitationCredential = escapingCredential,
            ),
        )
    }

    @Test
    fun `exact ownership classifier protects supported malformed and unsupported gi`() {
        assertTrue(GroupInvitationProtocol.isExactGroupInvitationCandidate(GroupInvitationProtocol.encodePublic(publicPayload())))
        assertTrue(GroupInvitationProtocol.isExactGroupInvitationCandidate("{\"t\":\"gi\"}"))
        assertTrue(GroupInvitationProtocol.isExactGroupInvitationCandidate("{\"t\":\"gi\",\"v\":99}"))
    }

    @Test
    fun `exact ownership classifier excludes non gi and unparsable bodies`() {
        assertFalse(GroupInvitationProtocol.isExactGroupInvitationCandidate("plain text"))
        assertFalse(GroupInvitationProtocol.isExactGroupInvitationCandidate("{\"t\":\"other\"}"))
        assertFalse(GroupInvitationProtocol.isExactGroupInvitationCandidate("{\"t\":\"gi\""))
        assertFalse(GroupInvitationProtocol.isExactGroupInvitationCandidate("{\"t\":1}"))
    }

    private fun publicPayload(description: String = "Short description") = PublicGroupInvitationPayload(
        cardId = "card-123", title = "Group title", shortDescription = description, invitationCredential = credential(),
    )

    private fun privateEnvelope(
        ciphertext: String = Base64Url.encode(ByteArray(32) { 7 }),
        ephemeralPublicKey: String = Base64Url.encode(validUncompressedP256Point()),
    ) = PrivateGroupInvitationEnvelope(
        cardId = "card-123", targetParticipantId = "participant-b",
        algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM,
        targetKeyId = "target-key-1",
        ephemeralPublicKey = ephemeralPublicKey,
        nonce = Base64Url.encode(ByteArray(GroupInvitationLimits.AES_GCM_NONCE_BYTES) { 2 }),
        ciphertext = ciphertext,
    )

    private fun credential() = InvitationCredential.of("opaque-credential")

    private fun validUncompressedP256Point(): ByteArray =
        ByteArray(GroupInvitationLimits.P256_UNCOMPRESSED_PUBLIC_KEY_BYTES).also { it[0] = 0x04 }
}
