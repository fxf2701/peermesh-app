package app.peermesh.data.relay

import org.junit.Assert.assertTrue
import org.junit.Test

class RelayBackoffTest {
    @Test
    fun `delay grows and caps`() {
        val backoff = RelayBackoff(baseDelayMs = 1000L, maxDelayMs = 5000L, jitterRatio = 0.0)
        val first = backoff.nextDelayMs()
        val second = backoff.nextDelayMs()
        val third = backoff.nextDelayMs()
        val fourth = backoff.nextDelayMs()

        assertTrue(first == 1000L)
        assertTrue(second == 2000L)
        assertTrue(third == 4000L)
        assertTrue(fourth == 5000L)
    }
}
