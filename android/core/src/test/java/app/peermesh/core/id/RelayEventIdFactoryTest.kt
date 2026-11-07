package app.peermesh.core.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEventIdFactoryTest {
    @Test
    fun `generates stable uuid from event id`() {
        val eventId = "event-123"
        val first = RelayEventIdFactory.fromEventId(eventId)
        val second = RelayEventIdFactory.fromEventId(eventId)
        assertEquals(first, second)
        assertTrue(first.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")))
    }
}
