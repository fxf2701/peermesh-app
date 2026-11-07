package app.peermesh.transport.ble.dtn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtnControlRoutePolicyTest {
    @Test
    fun `queued control may follow stable device to rotated address before first chunk starts`() {
        assertTrue(
            DtnControlRoutePolicy.shouldRebindQueuedEnvelope(
                hasStartedEnvelope = false,
                hasActivePacket = false,
                writeInFlight = false,
            ),
        )
    }

    @Test
    fun `active control envelope stays pinned to old address until current chunk finishes`() {
        assertFalse(
            DtnControlRoutePolicy.shouldRebindQueuedEnvelope(
                hasStartedEnvelope = false,
                hasActivePacket = true,
                writeInFlight = false,
            ),
        )
    }

    @Test
    fun `write in flight prevents rebinding even before active packet bookkeeping settles`() {
        assertFalse(
            DtnControlRoutePolicy.shouldRebindQueuedEnvelope(
                hasStartedEnvelope = false,
                hasActivePacket = false,
                writeInFlight = true,
            ),
        )
    }
    @Test
    fun `started multi-chunk control envelope remains on old lane between chunk callbacks`() {
        assertFalse(
            DtnControlRoutePolicy.shouldRebindQueuedEnvelope(
                hasStartedEnvelope = true,
                hasActivePacket = false,
                writeInFlight = false,
            ),
        )
    }

}
