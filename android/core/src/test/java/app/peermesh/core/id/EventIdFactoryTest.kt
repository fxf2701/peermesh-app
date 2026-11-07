package app.peermesh.core.id

import org.junit.Assert.assertEquals
import org.junit.Test

class EventIdFactoryTest {
    @Test
    fun generate_isDeterministic() {
        val nonce = ByteArray(16) { 1 }
        val first = EventIdFactory.generate("gid", "device", 123L, nonce)
        val second = EventIdFactory.generate("gid", "device", 123L, nonce)
        assertEquals(first, second)
    }

    @Test
    fun generate_changesWithNonce() {
        val first = EventIdFactory.generate("gid", "device", 123L, ByteArray(16) { 1 })
        val second = EventIdFactory.generate("gid", "device", 123L, ByteArray(16) { 2 })
        assertEquals(false, first == second)
    }
}
