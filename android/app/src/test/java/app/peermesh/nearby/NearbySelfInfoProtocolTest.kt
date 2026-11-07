package app.peermesh.nearby

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.security.groupinvitation.P256Sec1PublicKeyCodec
import app.peermesh.security.groupinvitation.RecipientEncryptionKeyId
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbySelfInfoProtocolTest {
    @Test
    fun `valid identity produces deterministic complete snapshot`() {
        val identity = validIdentity()

        val first = NearbySelfInfoProtocol.buildSnapshot(
            ownerDeviceId = "device-a",
            senderDisplayName = "Alice",
            avatarId = "pet_cat",
            profileUpdatedAtMs = 3_000L,
            recipientEncryptionIdentity = identity,
        )
        val second = NearbySelfInfoProtocol.buildSnapshot(
            ownerDeviceId = "device-a",
            senderDisplayName = "Alice",
            avatarId = "pet_cat",
            profileUpdatedAtMs = 3_000L,
            recipientEncryptionIdentity = identity,
        )

        assertEquals(first.revision, second.revision)
        assertTrue(NearbySelfInfoProtocol.isValidRevision(first.revision))
        assertEquals(identity, first.recipientEncryptionIdentity)
    }

    @Test
    fun `revision changes when identity changes without profile edit`() {
        val first = snapshot(validIdentity())
        val second = snapshot(validIdentity())

        assertNotEquals(first.revision, second.revision)
    }

    @Test
    fun `available and unavailable snapshots have different revisions`() {
        val available = snapshot(validIdentity())
        val unavailable = snapshot(null)

        assertNotEquals(available.revision, unavailable.revision)
        assertNull(unavailable.recipientEncryptionIdentity)
    }

    @Test
    fun `apply and validate round trip preserves public identity`() {
        val identity = validIdentity()
        val snapshot = snapshot(identity)
        val envelope = NearbySelfInfoProtocol.applySnapshot(baseSelfInfo(), snapshot)

        val validated = NearbySelfInfoProtocol.validateReceived(envelope)

        assertNotNull(validated)
        assertEquals(snapshot.revision, validated?.revision)
        assertEquals(identity.algorithm, validated?.recipientEncryptionIdentity?.algorithm)
        assertEquals(identity.keyId, validated?.recipientEncryptionIdentity?.keyId)
        assertArrayEquals(identity.publicKeySec1, validated?.recipientEncryptionIdentity?.publicKeySec1)
    }

    @Test
    fun `partial extension suppresses identity but preserves unavailable snapshot semantics`() {
        val unavailable = snapshot(null)
        val envelope = baseSelfInfo().copy(
            recipient_encryption_supported = true,
            recipient_encryption_algorithm = PrivateInvitationEncryptionAlgorithm.P256_HKDF_SHA256_A256GCM.wireValue,
            self_info_revision = unavailable.revision,
        )

        assertNull(NearbySelfInfoProtocol.parseAdvertisedIdentity(envelope))
        val validated = NearbySelfInfoProtocol.validateReceived(envelope)
        assertNotNull(validated)
        assertNull(validated?.recipientEncryptionIdentity)
    }

    @Test
    fun `mismatched key id suppresses identity and invalidates claimed available revision`() {
        val identity = validIdentity()
        val snapshot = snapshot(identity)
        val envelope = NearbySelfInfoProtocol.applySnapshot(baseSelfInfo(), snapshot).copy(
            recipient_encryption_key_id = Base64Url.encode(ByteArray(32) { 7 }),
        )

        assertNull(NearbySelfInfoProtocol.parseAdvertisedIdentity(envelope))
        assertNull(NearbySelfInfoProtocol.validateReceived(envelope))
    }

    @Test
    fun `defensive copy prevents public key mutation`() {
        val identity = validIdentity()
        val first = identity.publicKeySec1
        first[1] = (first[1].toInt() xor 0x01).toByte()

        assertFalse(first.contentEquals(identity.publicKeySec1))
    }

    private fun snapshot(identity: NearbyRecipientEncryptionPublicIdentity?) =
        NearbySelfInfoProtocol.buildSnapshot(
            ownerDeviceId = "device-a",
            senderDisplayName = "Alice",
            avatarId = "pet_cat",
            profileUpdatedAtMs = 3_000L,
            recipientEncryptionIdentity = identity,
        )

    private fun baseSelfInfo() = NearbyRoomTransportEnvelope(
        kind = NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1,
        room_id = "room-1",
        sender_device_id = "device-a",
        origin_device_id = "device-a",
        sender_display_name = "Alice",
        avatar_id = "pet_cat",
        profile_updated_at_ms = 3_000L,
        created_at_ms = 1_000L,
        expires_at_ms = 2_000L,
        target_device_id = "device-b",
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
