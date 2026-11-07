package app.peermesh.nearby

import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.security.groupinvitation.P256Sec1PublicKeyCodec
import app.peermesh.security.groupinvitation.RecipientEncryptionKeyId
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyRecipientKeyPinResultTest {
    @Test
    fun exactStoredTupleMatches() {
        val identity = validIdentity()

        assertEquals(
            NearbyRecipientKeyPinResult.Matched,
            classify(identity, identity),
        )
    }

    @Test
    fun differentValidTupleConflicts() {
        val stored = validIdentity()

        assertEquals(
            NearbyRecipientKeyPinResult.Conflict,
            classify(stored, validIdentity()),
        )
    }

    @Test
    fun sameKeyIdWithDifferentPublicKeyConflictsByteForByte() {
        val stored = validIdentity()
        val other = validIdentity()
        val incoming = NearbyRecipientEncryptionPublicIdentity(
            algorithm = stored.algorithm,
            keyId = stored.keyId,
            publicKeySec1 = other.publicKeySec1,
        )

        assertEquals(NearbyRecipientKeyPinResult.Conflict, classify(stored, incoming))
    }

    @Test
    fun samePublicKeyWithDifferentKeyIdConflicts() {
        val stored = validIdentity()
        val incoming = NearbyRecipientEncryptionPublicIdentity(
            algorithm = stored.algorithm,
            keyId = "A".repeat(43),
            publicKeySec1 = stored.publicKeySec1,
        )

        assertEquals(NearbyRecipientKeyPinResult.Conflict, classify(stored, incoming))
    }

    @Test
    fun partialAndProtocolInvalidStoredTuplesFailClosed() {
        val incoming = validIdentity()

        assertEquals(
            NearbyRecipientKeyPinResult.StoredPinInvalid,
            classifyStoredRecipientKeyPin(
                storedAlgorithm = incoming.algorithm.wireValue,
                storedKeyId = null,
                storedPublicKey = incoming.publicKeySec1,
                incomingIdentity = incoming,
            ),
        )
        assertEquals(
            NearbyRecipientKeyPinResult.StoredPinInvalid,
            classifyStoredRecipientKeyPin(
                storedAlgorithm = "unsupported-algorithm",
                storedKeyId = incoming.keyId,
                storedPublicKey = incoming.publicKeySec1,
                incomingIdentity = incoming,
            ),
        )
    }

    private fun classify(
        stored: NearbyRecipientEncryptionPublicIdentity,
        incoming: NearbyRecipientEncryptionPublicIdentity,
    ) = classifyStoredRecipientKeyPin(
        storedAlgorithm = stored.algorithm.wireValue,
        storedKeyId = stored.keyId,
        storedPublicKey = stored.publicKeySec1,
        incomingIdentity = incoming,
    )

    private fun validIdentity(): NearbyRecipientEncryptionPublicIdentity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = P256Sec1PublicKeyCodec.encode(generator.generateKeyPair().public)
        val algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM
        return NearbyRecipientEncryptionPublicIdentity(
            algorithm = algorithm,
            keyId = RecipientEncryptionKeyId.derive(algorithm, publicKey),
            publicKeySec1 = publicKey,
        )
    }
}
