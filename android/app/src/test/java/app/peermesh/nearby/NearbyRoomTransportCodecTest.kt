package app.peermesh.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NearbyRoomTransportCodecTest {
    @Test
    fun `encodes and decodes metadata response envelopes`() {
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_METADATA_RESPONSE_V1,
                room_id = "room-1",
                sender_device_id = "device-a",
                sender_display_name = "Alice",
                created_at_ms = 1_000L,
                expires_at_ms = 2_000L,
                target_device_id = "device-c",
                room_name = "Phong hop 😀",
                participant_count = 3,
            )
        )

        val decoded = NearbyRoomTransportCodec.decode(payload)

        assertNotNull(decoded)
        assertEquals(NearbyRoomTransportEnvelope.KIND_METADATA_RESPONSE_V1, decoded?.kind)
        assertEquals("Phong hop 😀", decoded?.room_name)
        assertEquals("device-c", decoded?.target_device_id)
        assertEquals(3, decoded?.participant_count)
        assertEquals(2_000L, decoded?.expires_at_ms)
    }

    @Test
    fun `encodes and decodes edited message metadata`() {
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_MESSAGE_V2,
                room_id = "room-1",
                sender_device_id = "device-a",
                origin_device_id = "device-a",
                created_at_ms = 1_000L,
                expires_at_ms = 2_000L,
                edited_at_ms = 1_500L,
                deleted_at_ms = 1_800L,
                message_id = "message-1",
                reply_to = "message-0",
                ciphertext = "cipher",
                nonce = "nonce",
                key_version = 1,
            )
        )

        val decoded = NearbyRoomTransportCodec.decode(payload)

        assertNotNull(decoded)
        assertEquals(NearbyRoomTransportEnvelope.KIND_MESSAGE_V2, decoded?.kind)
        assertEquals("message-1", decoded?.message_id)
        assertEquals("message-0", decoded?.reply_to)
        assertEquals(1_500L, decoded?.edited_at_ms)
        assertEquals(1_800L, decoded?.deleted_at_ms)
    }

    @Test
    fun `encodes and decodes owner mute envelopes`() {
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
                kind = NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1,
                room_id = "room-1",
                sender_device_id = "relay-a",
                owner_device_id = "owner-a",
                origin_device_id = "owner-a",
                created_at_ms = 1_000L,
                expires_at_ms = 2_000L,
                target_device_id = "device-b",
            )
        )

        val decoded = NearbyRoomTransportCodec.decode(payload)

        assertNotNull(decoded)
        assertEquals(NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1, decoded?.kind)
        assertEquals("owner-a", decoded?.origin_device_id)
        assertEquals("device-b", decoded?.target_device_id)
    }

    @Test
    fun `encodes and decodes self info envelopes`() {
        val payload = NearbyRoomTransportCodec.encode(
            NearbyRoomTransportEnvelope(
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
        )

        val decoded = NearbyRoomTransportCodec.decode(payload)

        assertNotNull(decoded)
        assertEquals(NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1, decoded?.kind)
        assertEquals("Alice", decoded?.sender_display_name)
        assertEquals("pet_cat", decoded?.avatar_id)
        assertEquals(3_000L, decoded?.profile_updated_at_ms)
        assertEquals("device-b", decoded?.target_device_id)
    }
}
