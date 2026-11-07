package app.peermesh.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventClockSkewTest {
    @Test
    fun `uses event time when skew within limit`() {
        val arrival = 1_000L
        val eventTime = arrival + EventClockSkew.MAX_SKEW_SECONDS
        val resolved = EventClockSkew.resolveEventTimeSeconds(eventTime, arrival)
        assertEquals(eventTime, resolved)
        assertFalse(EventClockSkew.isSkewed(eventTime, arrival))
    }

    @Test
    fun `uses arrival time when skew exceeds limit`() {
        val arrival = 1_000L
        val eventTime = arrival + EventClockSkew.MAX_SKEW_SECONDS + 1
        val resolved = EventClockSkew.resolveEventTimeSeconds(eventTime, arrival)
        assertEquals(arrival, resolved)
        assertTrue(EventClockSkew.isSkewed(eventTime, arrival))
    }
}
