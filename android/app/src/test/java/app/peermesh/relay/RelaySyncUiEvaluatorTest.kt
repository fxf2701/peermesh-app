package app.peermesh.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySyncUiEvaluatorTest {
    @Test
    fun `managed config counts as configured path without direct relay urls`() {
        assertTrue(
            RelaySyncUiEvaluator.hasConfiguredPath(
                directRelayCount = 0,
                managedConfigured = true,
            ),
        )
    }

    @Test
    fun `indicator stays on when managed path is configured and online`() {
        assertEquals(
            RelayIndicatorState.ON,
            RelaySyncUiEvaluator.indicatorState(
                relayAvailable = true,
                relayEnabled = true,
                internetOnline = true,
                hasRecentSuccess = false,
                directRelayCount = 0,
                managedConfigured = true,
            ),
        )
    }

    @Test
    fun `indicator pauses when no direct or managed relay path exists`() {
        assertEquals(
            RelayIndicatorState.PAUSED,
            RelaySyncUiEvaluator.indicatorState(
                relayAvailable = true,
                relayEnabled = true,
                internetOnline = true,
                hasRecentSuccess = false,
                directRelayCount = 0,
                managedConfigured = false,
            ),
        )
        assertFalse(
            RelaySyncUiEvaluator.hasConfiguredPath(
                directRelayCount = 0,
                managedConfigured = false,
            ),
        )
    }
}
