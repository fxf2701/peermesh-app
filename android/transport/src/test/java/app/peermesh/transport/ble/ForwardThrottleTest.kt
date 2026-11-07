package app.peermesh.transport.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardThrottleTest {
    @Test
    fun allow_limitsWithinWindow() {
        val throttle = ForwardThrottle(maxForwards = 2, windowSeconds = 60)
        assertTrue(throttle.allow(100))
        assertTrue(throttle.allow(100))
        assertFalse(throttle.allow(100))
    }

    @Test
    fun allow_resetsAfterWindow() {
        val throttle = ForwardThrottle(maxForwards = 1, windowSeconds = 10)
        assertTrue(throttle.allow(100))
        assertFalse(throttle.allow(105))
        assertTrue(throttle.allow(111))
    }
}
