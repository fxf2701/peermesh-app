package app.peermesh.security.groupinvitation

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.GroupInvitationDecodeResult
import app.peermesh.core.groupinvitation.GroupInvitationDecodedMessage
import app.peermesh.core.groupinvitation.GroupInvitationLimits
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.core.groupinvitation.InvitationCredential
import app.peermesh.core.groupinvitation.PrivateGroupInvitationEnvelope
import app.peermesh.core.groupinvitation.PrivateGroupInvitationInnerPayload
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECPoint
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

class PrivateRecipientCryptoTest {
    @Test
    fun `canonical context matches locked golden vector`() {
        val encoded = PrivateRecipientContextEncoder.encode(
            context(
                roomId = "room-α",
                targetParticipantId = "participant-β",
                targetKeyId = "A".repeat(43),
                expiry = 1_735_689_600_000L,
            ),
        )

        assertEquals(
            "504d474901000100000018703235362d686b64662d7368613235362d6132353667636d" +
                "00000007726f6f6d2dceb1000000076d73672d30303100000008636172642d303031" +
                "0000000d73656e6465722d6465766963650000000e7061727469636970616e742dceb2" +
                "0000002b414141414141414141414141414141414141414141414141414141414141" +
                "41414141414141414141414141000001941f297c00",
            encoded.toHex(),
        )
    }


    @Test
    fun `hkdf matches locked exact output vector`() {
        val contextBytes = PrivateRecipientContextEncoder.encode(
            context(
                roomId = "room-α",
                targetParticipantId = "participant-β",
                targetKeyId = "A".repeat(43),
                expiry = 1_735_689_600_000L,
            ),
        )
        val derived = HkdfSha256.derive(ByteArray(32) { it.toByte() }, contextBytes)

        assertEquals("46bd320605c5a6b6163ab70bc6345b92a5f908e79fe58979c23ebb47d1a5e307", derived.prk.toHex())
        assertEquals("4a0b4fc4b917c2a288a7ed64f356fac78f66b8600afe6ffb06cae40b803f7517", derived.okm.toHex())
    }

    @Test
    fun `hkdf changes OKM when only authenticated context changes`() {
        val sharedSecret = ByteArray(32) { it.toByte() }
        val first = HkdfSha256.derive(sharedSecret, PrivateRecipientContextEncoder.encode(context(roomId = "room-a")))
        val second = HkdfSha256.derive(sharedSecret, PrivateRecipientContextEncoder.encode(context(roomId = "room-b")))

        try {
            assertFalse(first.okm.contentEquals(second.okm))
        } finally {
            sharedSecret.fill(0)
            first.prk.fill(0)
            first.okm.fill(0)
            second.prk.fill(0)
            second.okm.fill(0)
        }
    }

    @Test
    fun `fixed P-256 keys and nonce match locked AES-GCM vector`() {
        val recipient = fixedRecipientKeyPair()
        val ephemeral = fixedEphemeralKeyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId)
        val payload = (
            deterministicCrypto(provider(recipient, identity.keyId), ephemeral, 7)
                .encrypt("secret".toByteArray(), identity, context) as PrivateRecipientEncryptResult.Success
            ).payload

        assertEquals("vFNA5CDoZwCf4IyJwITbeUwhZWy9Inq_MjnahOhCR1M", identity.keyId)
        assertEquals(
            "047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978" +
                "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1",
            payload.ephemeralPublicKeySec1.toHex(),
        )
        assertEquals("070707070707070707070707", payload.nonce.toHex())
        assertEquals("35f2e661190ee91a76ac77fa4602fc026718517f9d49", payload.ciphertextAndTag.toHex())
        val decrypted = deterministicCrypto(provider(recipient, identity.keyId), ephemeral, 7)
            .decrypt(payload, context, context.effectiveRoomExpiryEpochMs - 1)
        assertTrue(decrypted is PrivateRecipientDecryptResult.Success)
        assertArrayEquals("secret".toByteArray(), (decrypted as PrivateRecipientDecryptResult.Success).plaintext)
    }

    @Test
    fun `intended recipient decrypts and wrong recipient cannot`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId)
        val crypto = crypto(recipient)
        val plaintext = "canonical-private-invitation".toByteArray()

        val encrypted = crypto.encrypt(plaintext, identity, context)
        assertTrue(encrypted is PrivateRecipientEncryptResult.Success)
        val payload = (encrypted as PrivateRecipientEncryptResult.Success).payload

        val decrypted = crypto.decrypt(payload, context, nowEpochMs = context.effectiveRoomExpiryEpochMs - 1)
        assertTrue(decrypted is PrivateRecipientDecryptResult.Success)
        assertArrayEquals(plaintext, (decrypted as PrivateRecipientDecryptResult.Success).plaintext)

        val otherRecipientCrypto = crypto(keyPair(), resolvedKeyId = identity.keyId)
        assertEquals(
            PrivateRecipientDecryptResult.AuthenticationFailed,
            otherRecipientCrypto.decrypt(payload, context, context.effectiveRoomExpiryEpochMs - 1),
        )
    }

    @Test
    fun `decrypt success defensively copies plaintext for every caller read`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId)
        val crypto = crypto(recipient)
        val expected = "defensive plaintext".toByteArray()
        val payload = (crypto.encrypt(expected, identity, context) as PrivateRecipientEncryptResult.Success).payload
        val result = crypto.decrypt(payload, context, context.effectiveRoomExpiryEpochMs - 1)
            as PrivateRecipientDecryptResult.Success

        val callerCopy = result.plaintext
        callerCopy.fill(0)
        assertArrayEquals(expected, result.plaintext)
    }

    @Test
    fun `each authenticated context mutation prevents plaintext return`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val original = context(targetKeyId = identity.keyId)
        val crypto = crypto(recipient)
        val payload = (crypto.encrypt("secret".toByteArray(), identity, original) as PrivateRecipientEncryptResult.Success).payload
        val mutations = listOf(
            original.copy(protocolVersion = 2),
            original.copy(algorithmWireValue = "other"),
            original.copy(roomId = "room-002"),
            original.copy(messageId = "msg-002"),
            original.copy(cardId = "card-002"),
            original.copy(logicalSenderId = "sender-other"),
            original.copy(targetParticipantId = "participant-other"),
            original.copy(targetKeyId = "B".repeat(43)),
            original.copy(effectiveRoomExpiryEpochMs = original.effectiveRoomExpiryEpochMs + 1),
        )

        mutations.forEach { changed ->
            val result = crypto.decrypt(payload, changed, nowEpochMs = 1)
            assertFalse("mutation unexpectedly decrypted: $changed", result is PrivateRecipientDecryptResult.Success)
        }
    }

    @Test
    fun `expiry is rejected at exact boundary before private key lookup`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId, expiry = 1000L)
        var lookups = 0
        val provider = object : RecipientEncryptionIdentityProvider {
            override fun getOrCreateIdentity() = RecipientEncryptionIdentityStatus.Available(identity)
            override fun resolvePrivateKey(expectedKeyId: String): RecipientPrivateKeyResult {
                lookups++
                return RecipientPrivateKeyResult.Available(recipient.private)
            }
            override fun resetIdentity(activeNearbyRoomCount: Int) = RecipientIdentityResetResult.Success
        }
        val crypto = deterministicCrypto(provider)
        val payload = (crypto.encrypt("secret".toByteArray(), identity, context) as PrivateRecipientEncryptResult.Success).payload

        assertTrue(crypto.decrypt(payload, context, 999L) is PrivateRecipientDecryptResult.Success)
        assertEquals(1, lookups)
        assertEquals(PrivateRecipientDecryptResult.Expired, crypto.decrypt(payload, context, 1000L))
        assertEquals(PrivateRecipientDecryptResult.Expired, crypto.decrypt(payload, context, 1001L))
        assertEquals(1, lookups)
    }

    @Test
    fun `tampering nonce ciphertext or ephemeral public key fails closed`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId)
        val crypto = crypto(recipient)
        val payload = (crypto.encrypt("secret".toByteArray(), identity, context) as PrivateRecipientEncryptResult.Success).payload

        val changedNonce = payload.nonce.also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(
            PrivateRecipientDecryptResult.AuthenticationFailed,
            crypto.decrypt(payload.copyWith(nonce = changedNonce), context, 1L),
        )
        val changedCiphertext = payload.ciphertextAndTag.also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(
            PrivateRecipientDecryptResult.AuthenticationFailed,
            crypto.decrypt(payload.copyWith(ciphertextAndTag = changedCiphertext), context, 1L),
        )
        val changedTag = payload.ciphertextAndTag.also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(
            PrivateRecipientDecryptResult.AuthenticationFailed,
            crypto.decrypt(payload.copyWith(ciphertextAndTag = changedTag), context, 1L),
        )
        val malformedKey = payload.ephemeralPublicKeySec1.also { it[0] = 0x02 }
        assertEquals(
            PrivateRecipientDecryptResult.InvalidEncryptedPayload,
            crypto.decrypt(payload.copyWith(ephemeralPublicKeySec1 = malformedKey), context, 1L),
        )
    }

    @Test
    fun `same test seam inputs produce exact stable payload while production inputs remain copied`() {
        val recipient = keyPair()
        val ephemeral = keyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId)
        val provider = provider(recipient, identity.keyId)
        val first = deterministicCrypto(provider, ephemeral, 7)
        val second = deterministicCrypto(provider, ephemeral, 7)
        val plaintext = "secret".toByteArray()

        val one = (first.encrypt(plaintext, identity, context) as PrivateRecipientEncryptResult.Success).payload
        val two = (second.encrypt(plaintext, identity, context) as PrivateRecipientEncryptResult.Success).payload
        assertEquals(one, two)
        assertEquals("070707070707070707070707", one.nonce.toHex())

        val exposed = one.nonce
        exposed.fill(0)
        assertNotEquals(exposed.toHex(), one.nonce.toHex())
        assertArrayEquals("secret".toByteArray(), plaintext)
    }

    @Test
    fun `production encryption generates fresh ephemeral key and nonce`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId)
        val crypto = crypto(recipient)

        val first = (crypto.encrypt("secret".toByteArray(), identity, context) as PrivateRecipientEncryptResult.Success).payload
        val second = (crypto.encrypt("secret".toByteArray(), identity, context) as PrivateRecipientEncryptResult.Success).payload

        assertFalse(first.ephemeralPublicKeySec1.contentEquals(second.ephemeralPublicKeySec1))
        assertFalse(first.nonce.contentEquals(second.nonce))
    }

    @Test
    fun `malformed unicode and noncanonical key id fail before cryptography`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val crypto = crypto(recipient)

        assertEquals(
            PrivateRecipientEncryptResult.InvalidContext,
            crypto.encrypt(
                "secret".toByteArray(),
                identity,
                context(targetKeyId = identity.keyId).copy(roomId = "room-\uD800"),
            ),
        )
        assertEquals(
            PrivateRecipientEncryptResult.InvalidContext,
            crypto.encrypt(
                "secret".toByteArray(),
                identity,
                context(targetKeyId = "A".repeat(42) + "B"),
            ),
        )
    }

    @Test
    fun `malformed encrypted structure fails before private key lookup`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId)
        var lookups = 0
        val rejectingProvider = object : RecipientEncryptionIdentityProvider {
            override fun getOrCreateIdentity() = RecipientEncryptionIdentityStatus.Available(identity)
            override fun resolvePrivateKey(expectedKeyId: String): RecipientPrivateKeyResult {
                lookups++
                return RecipientPrivateKeyResult.Available(recipient.private)
            }
            override fun resetIdentity(activeNearbyRoomCount: Int) = RecipientIdentityResetResult.Success
        }
        val valid = (
            deterministicCrypto(provider(recipient, identity.keyId))
                .encrypt("secret".toByteArray(), identity, context) as PrivateRecipientEncryptResult.Success
            ).payload
        val malformed = listOf(
            valid.copyWith(nonce = ByteArray(0)),
            valid.copyWith(nonce = ByteArray(11)),
            valid.copyWith(nonce = ByteArray(13)),
            valid.copyWith(ciphertextAndTag = ByteArray(15)),
            valid.copyWith(ephemeralPublicKeySec1 = ByteArray(65).also { it[0] = 0x02 }),
            valid.copyWith(ephemeralPublicKeySec1 = ByteArray(64)),
        )

        malformed.forEach {
            assertEquals(
                PrivateRecipientDecryptResult.InvalidEncryptedPayload,
                JcaPrivateRecipientCrypto(rejectingProvider).decrypt(it, context, 1L),
            )
        }
        assertEquals(0, lookups)
    }

    @Test
    fun `real compressed and off-curve SEC1 points fail before expiry or private key lookup`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId, expiry = 1000L)
        var lookups = 0
        val lookupProvider = object : RecipientEncryptionIdentityProvider {
            override fun getOrCreateIdentity() = RecipientEncryptionIdentityStatus.Available(identity)
            override fun resolvePrivateKey(expectedKeyId: String): RecipientPrivateKeyResult {
                lookups++
                return RecipientPrivateKeyResult.Available(recipient.private)
            }
            override fun resetIdentity(activeNearbyRoomCount: Int) = RecipientIdentityResetResult.Success
        }
        val crypto = deterministicCrypto(provider(recipient, identity.keyId))
        val valid = (crypto.encrypt("secret".toByteArray(), identity, context) as PrivateRecipientEncryptResult.Success).payload
        val uncompressed = P256Sec1PublicKeyCodec.encode(keyPair().public)
        val compressed = byteArrayOf(if (uncompressed.last().toInt() and 1 == 0) 0x02 else 0x03) +
            uncompressed.copyOfRange(1, 33)
        val offCurve = ByteArray(P256Sec1PublicKeyCodec.ENCODED_BYTES).apply {
            this[0] = 0x04
            this[32] = 0x01
            this[64] = 0x01
        }

        assertThrows(IllegalArgumentException::class.java) { P256Sec1PublicKeyCodec.decode(compressed) }
        assertThrows(IllegalArgumentException::class.java) { P256Sec1PublicKeyCodec.decode(offCurve) }
        assertEquals(
            PrivateRecipientDecryptResult.InvalidEncryptedPayload,
            JcaPrivateRecipientCrypto(lookupProvider).decrypt(
                valid.copyWith(ephemeralPublicKeySec1 = offCurve),
                context,
                nowEpochMs = context.effectiveRoomExpiryEpochMs,
            ),
        )
        assertEquals(0, lookups)
    }

    @Test
    fun `recipient identity rejects a key ID derived from another public key`() {
        val recipientPublicKey = P256Sec1PublicKeyCodec.encode(keyPair().public)
        val anotherPublicKey = P256Sec1PublicKeyCodec.encode(keyPair().public)
        val algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM

        assertThrows(IllegalArgumentException::class.java) {
            RecipientEncryptionIdentity(
                algorithm = algorithm,
                keyId = RecipientEncryptionKeyId.derive(algorithm, anotherPublicKey),
                publicKeySec1 = recipientPublicKey,
            )
        }
    }

    @Test
    fun `recipient private key provider states map exactly`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId)
        val payload = (
            deterministicCrypto(provider(recipient, identity.keyId))
                .encrypt("secret".toByteArray(), identity, context) as PrivateRecipientEncryptResult.Success
            ).payload
        val reason = RecipientEncryptionIdentityStatus.Reason.KEYSTORE_UNAVAILABLE

        assertEquals(PrivateRecipientDecryptResult.NotForLocalRecipientKey, decryptWith(RecipientPrivateKeyResult.KeyIdMismatch, payload, context))
        assertEquals(PrivateRecipientDecryptResult.NotForLocalRecipientKey, decryptWith(RecipientPrivateKeyResult.NotFound, payload, context))
        assertEquals(PrivateRecipientDecryptResult.UnsupportedPlatform, decryptWith(RecipientPrivateKeyResult.UnsupportedPlatform, payload, context))
        assertEquals(
            PrivateRecipientDecryptResult.RecipientIdentityUnavailable(reason),
            decryptWith(RecipientPrivateKeyResult.TemporarilyUnavailable(reason), payload, context),
        )
        assertEquals(
            PrivateRecipientDecryptResult.RecipientIdentityInvalidated(reason),
            decryptWith(RecipientPrivateKeyResult.Invalidated(reason), payload, context),
        )
    }

    @Test
    fun `existing invitation protocol is the only wire adapter`() {
        val recipient = keyPair()
        val identity = identity(recipient)
        val context = context(targetKeyId = identity.keyId)
        val crypto = crypto(recipient)
        val inner = PrivateGroupInvitationInnerPayload(
            title = "Private group",
            shortDescription = "Meet here",
            invitationCredential = InvitationCredential.of("credential"),
        )
        val canonicalPlaintext = GroupInvitationProtocol.encodePrivateInnerPayload(inner).toByteArray(Charsets.UTF_8)
        val encrypted = (crypto.encrypt(canonicalPlaintext, identity, context) as PrivateRecipientEncryptResult.Success).payload
        val envelope = PrivateGroupInvitationEnvelope(
            cardId = context.cardId,
            targetParticipantId = context.targetParticipantId,
            algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM,
            targetKeyId = encrypted.targetKeyId,
            ephemeralPublicKey = Base64Url.encode(encrypted.ephemeralPublicKeySec1),
            nonce = Base64Url.encode(encrypted.nonce),
            ciphertext = Base64Url.encode(encrypted.ciphertextAndTag),
        )
        val wire = GroupInvitationProtocol.encodePrivateEnvelope(envelope)
        assertTrue(wire.toByteArray(Charsets.UTF_8).size <= GroupInvitationLimits.MAX_PRIVATE_WIRE_PAYLOAD_BYTES)
        val decoded = GroupInvitationProtocol.decode(wire) as GroupInvitationDecodeResult.Invitation
        val decodedEnvelope = (decoded.message as GroupInvitationDecodedMessage.PrivateEncryptedInvitation).envelope
        val primitive = PrivateRecipientEncryptedPayload(
            decodedEnvelope.algorithm.wireValue,
            decodedEnvelope.targetKeyId,
            Base64Url.decode(decodedEnvelope.ephemeralPublicKey),
            Base64Url.decode(decodedEnvelope.nonce),
            Base64Url.decode(decodedEnvelope.ciphertext),
        )
        val plaintext = (
            crypto.decrypt(primitive, context, context.effectiveRoomExpiryEpochMs - 1) as
                PrivateRecipientDecryptResult.Success
            ).plaintext.toString(Charsets.UTF_8)

        assertEquals(inner, GroupInvitationProtocol.decodePrivateInnerPayload(plaintext))
        val malformed = (
            crypto.encrypt("not-json".toByteArray(), identity, context) as PrivateRecipientEncryptResult.Success
            ).payload
        val malformedPlaintext = (
            crypto.decrypt(malformed, context, context.effectiveRoomExpiryEpochMs - 1) as
                PrivateRecipientDecryptResult.Success
            ).plaintext.toString(Charsets.UTF_8)
        assertEquals(null, GroupInvitationProtocol.decodePrivateInnerPayload(malformedPlaintext))
    }

    private fun decryptWith(
        result: RecipientPrivateKeyResult,
        payload: PrivateRecipientEncryptedPayload,
        context: PrivateRecipientCryptoContext,
    ): PrivateRecipientDecryptResult {
        val provider = object : RecipientEncryptionIdentityProvider {
            override fun getOrCreateIdentity() = RecipientEncryptionIdentityStatus.UnsupportedPlatform
            override fun resolvePrivateKey(expectedKeyId: String) = result
            override fun resetIdentity(activeNearbyRoomCount: Int) = RecipientIdentityResetResult.UnsupportedPlatform
        }
        return JcaPrivateRecipientCrypto(provider).decrypt(payload, context, 1L)
    }

    private fun crypto(recipient: KeyPair, resolvedKeyId: String = identity(recipient).keyId): PrivateRecipientCrypto =
        JcaPrivateRecipientCrypto(provider(recipient, resolvedKeyId))

    private fun deterministicCrypto(
        provider: RecipientEncryptionIdentityProvider,
        ephemeral: KeyPair = keyPair(),
        nonceByte: Int = 3,
    ): PrivateRecipientCrypto = JcaPrivateRecipientCrypto(
        identityProvider = provider,
        secureRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(nonceByte.toByte())
        },
        ephemeralKeyPairFactory = { ephemeral },
    )

    private fun provider(pair: KeyPair, resolvedKeyId: String): RecipientEncryptionIdentityProvider =
        object : RecipientEncryptionIdentityProvider {
            override fun getOrCreateIdentity(): RecipientEncryptionIdentityStatus =
                RecipientEncryptionIdentityStatus.Available(identity(pair))
            override fun resolvePrivateKey(expectedKeyId: String): RecipientPrivateKeyResult =
                if (expectedKeyId == resolvedKeyId) RecipientPrivateKeyResult.Available(pair.private)
                else RecipientPrivateKeyResult.KeyIdMismatch
            override fun resetIdentity(activeNearbyRoomCount: Int): RecipientIdentityResetResult =
                RecipientIdentityResetResult.Success
        }

    private fun identity(pair: KeyPair): RecipientEncryptionIdentity {
        val publicKey = P256Sec1PublicKeyCodec.encode(pair.public)
        val algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM
        return RecipientEncryptionIdentity(
            algorithm = algorithm,
            keyId = RecipientEncryptionKeyId.derive(algorithm, publicKey),
            publicKeySec1 = publicKey,
        )
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun fixedRecipientKeyPair(): KeyPair = fixedKeyPair(
        privateScalarHex = "1",
        publicXHex = "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
        publicYHex = "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
    )

    private fun fixedEphemeralKeyPair(): KeyPair = fixedKeyPair(
        privateScalarHex = "2",
        publicXHex = "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978",
        publicYHex = "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1",
    )

    private fun fixedKeyPair(privateScalarHex: String, publicXHex: String, publicYHex: String): KeyPair {
        val spec = P256Sec1PublicKeyCodec.p256Spec()
        val factory = KeyFactory.getInstance("EC")
        return KeyPair(
            factory.generatePublic(
                ECPublicKeySpec(ECPoint(BigInteger(publicXHex, 16), BigInteger(publicYHex, 16)), spec),
            ),
            factory.generatePrivate(ECPrivateKeySpec(BigInteger(privateScalarHex, 16), spec)),
        )
    }

    private fun context(
        roomId: String = "room-001",
        targetParticipantId: String = "participant-001",
        targetKeyId: String = "A".repeat(43),
        expiry: Long = 2_000_000_000_000L,
    ) = PrivateRecipientCryptoContext(
        protocolVersion = 1,
        algorithmWireValue = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM.wireValue,
        roomId = roomId,
        messageId = "msg-001",
        cardId = "card-001",
        logicalSenderId = "sender-device",
        targetParticipantId = targetParticipantId,
        targetKeyId = targetKeyId,
        effectiveRoomExpiryEpochMs = expiry,
    )

    private fun PrivateRecipientEncryptedPayload.copyWith(
        ephemeralPublicKeySec1: ByteArray = this.ephemeralPublicKeySec1,
        nonce: ByteArray = this.nonce,
        ciphertextAndTag: ByteArray = this.ciphertextAndTag,
    ) = PrivateRecipientEncryptedPayload(
        algorithmWireValue = algorithmWireValue,
        targetKeyId = targetKeyId,
        ephemeralPublicKeySec1 = ephemeralPublicKeySec1,
        nonce = nonce,
        ciphertextAndTag = ciphertextAndTag,
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
