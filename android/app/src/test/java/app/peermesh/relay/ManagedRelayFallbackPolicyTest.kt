package app.peermesh.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedRelayFallbackPolicyTest {
    @Test
    fun `uses known relays when managed bootstrap fails before any relay session`() {
        val report = RelaySyncRunReport.empty().copy(
            success = false,
            errorSummary = "registry timeout",
            relayCount = 0,
            gidCount = 1,
        )

        assertTrue(ManagedRelayFallbackPolicy.shouldUseKnownRelays(report))
    }

    @Test
    fun `uses known relays when managed reauth fails after stale session`() {
        val report = RelaySyncRunReport.empty().copy(
            success = false,
            errorSummary = "pull https://relay-a: expired",
            relayCount = 1,
            gidCount = 1,
            note = "managed_reauth_failed:registry_down",
        )

        assertTrue(ManagedRelayFallbackPolicy.shouldUseKnownRelays(report))
    }

    @Test
    fun `does not use known relays for healthy managed report`() {
        val report = RelaySyncRunReport.empty().copy(
            success = true,
            relayCount = 1,
            gidCount = 1,
        )

        assertFalse(ManagedRelayFallbackPolicy.shouldUseKnownRelays(report))
    }
}
