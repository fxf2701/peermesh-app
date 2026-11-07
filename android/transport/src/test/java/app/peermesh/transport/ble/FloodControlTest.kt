package app.peermesh.transport.ble

import org.junit.Assert.assertTrue
import org.junit.Test

class FloodControlTest {
    @Test
    fun jitterDelay_withinBounds() {
        val flood = FloodControl(minDelayMs = 10, maxDelayMs = 20)
        repeat(50) {
            val value = flood.jitterDelayMs()
            assertTrue(value in 10..20)
        }
    }
}
