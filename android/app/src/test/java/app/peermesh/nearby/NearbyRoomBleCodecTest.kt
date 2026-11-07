package app.peermesh.nearby

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyRoomBleCodecTest {
    @Test
    fun `encodes minimal discovery payload within advertisement budget`() {
        val advertisement = advertisement(roomName = "Room A #1")

        val payload = NearbyRoomBleCodec.encode(advertisement, nowMs = BASE_NOW_MS)

        assertNotEquals(0, payload.size)
        assertEquals(9, payload.size)
        val decoded = NearbyRoomBleCodec.decode(
            bytes = payload,
            ownerDeviceId = advertisement.ownerDeviceId,
            nowMs = BASE_NOW_MS,
        )
        assertNotNull(decoded)
        assertEquals("", decoded?.roomName)
    }

    @Test
    fun `room name is omitted from discovery payload and recovered as empty`() {
        val advertisement = advertisement(roomName = "A😀BC")

        val payload = NearbyRoomBleCodec.encode(advertisement, nowMs = BASE_NOW_MS)

        assertNotEquals(0, payload.size)
        val decoded = NearbyRoomBleCodec.decode(
            bytes = payload,
            ownerDeviceId = advertisement.ownerDeviceId,
            nowMs = BASE_NOW_MS,
        )
        assertNotNull(decoded)
        assertEquals("", decoded?.roomName)
        assertArrayEquals(payload, NearbyRoomBleCodec.encode(decoded!!, nowMs = BASE_NOW_MS))
    }

    @Test
    fun `supports empty name discovery payloads`() {
        val advertisement = advertisement(roomName = "")

        val payload = NearbyRoomBleCodec.encode(advertisement, nowMs = BASE_NOW_MS)

        assertNotEquals(0, payload.size)
        val decoded = NearbyRoomBleCodec.decode(
            bytes = payload,
            ownerDeviceId = advertisement.ownerDeviceId,
            nowMs = BASE_NOW_MS,
        )
        assertNotNull(decoded)
        assertEquals("", decoded?.roomName)
    }

    @Test
    fun `rounds active ttl up to one minute minimum`() {
        val advertisement = advertisement(roomName = "Room A #1").copy(
            expiresAtMs = BASE_NOW_MS + 1L,
        )

        val payload = NearbyRoomBleCodec.encode(advertisement, nowMs = BASE_NOW_MS)

        assertNotEquals(0, payload.size)
        assertEquals(0x01, payload[7].toInt() and 0xFF)
        val decoded = NearbyRoomBleCodec.decode(
            bytes = payload,
            ownerDeviceId = advertisement.ownerDeviceId,
            nowMs = BASE_NOW_MS,
        )
        assertNotNull(decoded)
        assertEquals(BASE_NOW_MS + 60_000L, decoded?.expiresAtMs)
    }

    @Test
    fun `rejects malformed utf8 room names`() {
        val malformedPayload = byteArrayOf(
            NearbyRoomBleCodec.PROTOCOL_VERSION.toByte(),
            0x01,
            0x23,
            0x45,
            0x67,
            0x89.toByte(),
            0xAB.toByte(),
            0x01,
            0x1E,
            0x02,
            0xC3.toByte(),
            0x28,
        )

        val decoded = NearbyRoomBleCodec.decode(
            bytes = malformedPayload,
            ownerDeviceId = "10308F486AD9F054",
            nowMs = BASE_NOW_MS,
        )

        assertNull(decoded)
    }

    private fun advertisement(roomName: String): NearbyRoomBleAdvertisement {
        return NearbyRoomBleAdvertisement(
            roomId = "0d3cd14233da",
            roomName = roomName,
            ownerDeviceId = "10308F486AD9F054",
            createdAtMs = BASE_NOW_MS,
            expiresAtMs = BASE_NOW_MS + NearbyRoomPolicy.ROOM_TTL_MS,
            participantCount = 1,
            protocolVersion = NearbyRoomBleCodec.PROTOCOL_VERSION,
        )
    }

    companion object {
        private const val BASE_NOW_MS = 1_000_000L
    }
}
