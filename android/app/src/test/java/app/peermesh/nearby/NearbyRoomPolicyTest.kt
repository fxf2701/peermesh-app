package app.peermesh.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyRoomPolicyTest {
    @Test
    fun `sanitizeRoomName caps to 50 characters`() {
        val raw = "a".repeat(60)

        val sanitized = NearbyRoomPolicy.sanitizeRoomName(raw)

        assertEquals(50, sanitized.length)
    }

    @Test
    fun `sanitizeRoomName caps to 100 utf8 bytes without splitting emoji`() {
        val raw = "😀".repeat(30)

        val sanitized = NearbyRoomPolicy.sanitizeRoomName(raw)

        assertTrue(sanitized.encodeToByteArray().size <= NearbyRoomPolicy.MAX_ROOM_NAME_BYTES)
        assertEquals("😀".repeat(25), sanitized)
    }
}
