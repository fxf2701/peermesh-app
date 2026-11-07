package app.peermesh.nearby

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.groupinvitation.PrivateInvitationEncryptionAlgorithm
import app.peermesh.security.groupinvitation.P256Sec1PublicKeyCodec
import app.peermesh.security.groupinvitation.RecipientEncryptionKeyId
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRoomTransportCodecGI2B1Test {
    @Test
    fun `new self info round trips additive compact fields`() {
        val identity = validIdentity()
        val snapshot = NearbySelfInfoProtocol.buildSnapshot(
            ownerDeviceId = "device-a",
            senderDisplayName = "Alice",
            avatarId = "pet_cat",
            profileUpdatedAtMs = 3_000L,
            recipientEncryptionIdentity = identity,
        )
        val envelope = NearbySelfInfoProtocol.applySnapshot(baseSelfInfo(), snapshot)

        val encoded = NearbyRoomTransportCodec.encode(envelope)
        val raw = encoded.toString(Charsets.UTF_8)
        val decoded = NearbyRoomTransportCodec.decode(encoded)

        assertTrue(raw.contains("\"res\":true"))
        assertTrue(raw.contains("\"rea\""))
        assertTrue(raw.contains("\"rek\""))
        assertTrue(raw.contains("\"rep\""))
        assertTrue(raw.contains("\"sir\""))
        assertEquals(true, decoded?.recipient_encryption_supported)
        assertEquals(identity.algorithm.wireValue, decoded?.recipient_encryption_algorithm)
        assertEquals(identity.keyId, decoded?.recipient_encryption_key_id)
        assertEquals(Base64Url.encode(identity.publicKeySec1), decoded?.recipient_encryption_public_key)
        assertEquals(snapshot.revision, decoded?.self_info_revision)
    }

    @Test
    fun `legacy long aliases decode for self info`() {
        val identity = validIdentity()
        val snapshot = NearbySelfInfoProtocol.buildSnapshot(
            ownerDeviceId = "device-a",
            senderDisplayName = "Alice",
            avatarId = null,
            profileUpdatedAtMs = 3_000L,
            recipientEncryptionIdentity = identity,
        )
        val raw = """{
            "kind":"NEARBY_ROOM_SELF_INFO_V1",
            "room_id":"room-1",
            "sender_device_id":"device-a",
            "origin_device_id":"device-a",
            "sender_display_name":"Alice",
            "profile_updated_at_ms":3000,
            "created_at_ms":1000,
            "expires_at_ms":2000,
            "recipient_encryption_supported":true,
            "recipient_encryption_algorithm":"${identity.algorithm.wireValue}",
            "recipient_encryption_key_id":"${identity.keyId}",
            "recipient_encryption_public_key":"${Base64Url.encode(identity.publicKeySec1)}",
            "self_info_revision":"${snapshot.revision}"
        }""".trimIndent().toByteArray()

        val decoded = NearbyRoomTransportCodec.decode(raw)

        assertNotNull(decoded)
        assertEquals(true, decoded?.recipient_encryption_supported)
        assertEquals(snapshot.revision, decoded?.self_info_revision)
    }

    @Test
    fun `wrong optional types do not reject base self info`() {
        val raw = """{
            "k":"SI1","r":"room-1","sd":"device-a","ct":1000,"ex":2000,
            "res":"true","rea":7,"rek":{},"rep":[],"sir":false
        }""".trimIndent().toByteArray()

        val decoded = NearbyRoomTransportCodec.decode(raw)

        assertNotNull(decoded)
        assertNull(decoded?.recipient_encryption_supported)
        assertNull(decoded?.recipient_encryption_algorithm)
        assertNull(decoded?.recipient_encryption_key_id)
        assertNull(decoded?.recipient_encryption_public_key)
        assertNull(decoded?.self_info_revision)
        assertNull(NearbySelfInfoProtocol.validateReceived(decoded!!))
    }

    @Test
    fun `non self info kinds omit accidentally populated extension fields`() {
        val encoded = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_MESSAGE_V2,
                room_id = "room-1",
                sender_device_id = "device-a",
                created_at_ms = 1_000L,
                expires_at_ms = 2_000L,
                recipient_encryption_supported = true,
                recipient_encryption_algorithm = "should-not-leak",
                recipient_encryption_key_id = "should-not-leak",
                recipient_encryption_public_key = "should-not-leak",
                self_info_revision = "should-not-leak",
            )
        ).toString(Charsets.UTF_8)

        assertFalse(encoded.contains("\"res\""))
        assertFalse(encoded.contains("\"rea\""))
        assertFalse(encoded.contains("\"rek\""))
        assertFalse(encoded.contains("\"rep\""))
        assertFalse(encoded.contains("\"sir\""))
    }

    @Test
    fun `self info ack encodes only revision from extension set`() {
        val revision = Base64Url.encode(ByteArray(32) { 1 })
        val encoded = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1,
                room_id = "room-1",
                sender_device_id = "device-b",
                created_at_ms = 1_000L,
                expires_at_ms = 2_000L,
                recipient_encryption_supported = true,
                recipient_encryption_algorithm = "should-not-leak",
                recipient_encryption_key_id = "should-not-leak",
                recipient_encryption_public_key = "should-not-leak",
                self_info_revision = revision,
            )
        )
        val raw = encoded.toString(Charsets.UTF_8)
        val decoded = NearbyRoomTransportCodec.decode(encoded)

        assertTrue(raw.contains("\"sir\""))
        assertFalse(raw.contains("\"res\""))
        assertFalse(raw.contains("\"rea\""))
        assertEquals(revision, decoded?.self_info_revision)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `over budget self info is rejected before BLE chunking`() {
        NearbyRoomTransportCodec.encode(
            baseSelfInfo().copy(sender_display_name = "x".repeat(1_100))
        )
    }

    @Test
    fun `representative full self info remains within wire budget`() {
        val identity = validIdentity()
        val snapshot = NearbySelfInfoProtocol.buildSnapshot(
            ownerDeviceId = "device-a",
            senderDisplayName = "😀".repeat(32),
            avatarId = "pet_cat",
            profileUpdatedAtMs = 3_000L,
            recipientEncryptionIdentity = identity,
        )
        val encoded = NearbyRoomTransportCodec.encode(
            NearbySelfInfoProtocol.applySnapshot(
                baseSelfInfo().copy(sender_display_name = "😀".repeat(32)),
                snapshot,
            )
        )

        assertTrue(encoded.size <= NearbySelfInfoProtocol.MAX_SELF_INFO_WIRE_BYTES)
    }

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
