package app.peermesh.relay

import app.peermesh.core.relay.ClientAccessGrant
import app.peermesh.core.relay.ClientAccessGrantLimits
import app.peermesh.core.relay.ClientRelaySession
import app.peermesh.core.relay.RelayCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedRelaySyncRuntimeTest {
    @Test
    fun `returns first cycle when auth is healthy`() {
        val provider = FakeAccessProvider(
            acquisitions = ArrayDeque(
                listOf(successBinding("http://relay-a:8080", "relay-a")),
            ),
        )
        val runtime = ManagedRelaySyncRuntime(
            accessProvider = provider,
            cycleExecutor = FakeCycleExecutor(
                results = ArrayDeque(
                    listOf(
                        ManagedRelayCycleResult(
                            report = RelaySyncRunReport.empty().copy(success = true, relayCount = 1, gidCount = 1),
                            authFailureCode = null,
                        ),
                    ),
                ),
            ),
        )

        val report = runtime.runOnce(listOf("group-1"))

        assertTrue(report.success)
        assertEquals(1, provider.acquireCount)
        assertEquals(0, provider.clearCount)
        assertEquals(null, report.note)
    }

    @Test
    fun `reacquires and retries once after recoverable auth failure`() {
        val provider = FakeAccessProvider(
            acquisitions = ArrayDeque(
                listOf(
                    successBinding("http://relay-a:8080", "relay-a", sessionId = "crs_old"),
                    successBinding("http://relay-b:8080", "relay-b", sessionId = "crs_new"),
                ),
            ),
        )
        val runtime = ManagedRelaySyncRuntime(
            accessProvider = provider,
            cycleExecutor = FakeCycleExecutor(
                results = ArrayDeque(
                    listOf(
                        ManagedRelayCycleResult(
                            report = RelaySyncRunReport.empty().copy(
                                success = false,
                                errorSummary = "push http://relay-a:8080: token mismatch",
                                relayCount = 1,
                                gidCount = 1,
                            ),
                            authFailureCode = "client_session_token_mismatch",
                        ),
                        ManagedRelayCycleResult(
                            report = RelaySyncRunReport.empty().copy(
                                success = true,
                                relayCount = 1,
                                gidCount = 1,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val report = runtime.runOnce(listOf("group-1"))

        assertTrue(report.success)
        assertEquals(2, provider.acquireCount)
        assertEquals(1, provider.clearCount)
        assertEquals("http://relay-a:8080", provider.clearedRelayUrls.single())
        assertEquals("managed_reauth_retry", report.note)
    }

    @Test
    fun `keeps first failure and notes retry failure when reacquire fails`() {
        val provider = FakeAccessProvider(
            acquisitions = ArrayDeque(
                listOf(
                    successBinding("http://relay-a:8080", "relay-a"),
                    ManagedRelayAccessBindingResult(
                        success = false,
                        errorCode = "grant_issue_failed",
                        errorMessage = "registry down",
                    ),
                ),
            ),
        )
        val runtime = ManagedRelaySyncRuntime(
            accessProvider = provider,
            cycleExecutor = FakeCycleExecutor(
                results = ArrayDeque(
                    listOf(
                        ManagedRelayCycleResult(
                            report = RelaySyncRunReport.empty().copy(
                                success = false,
                                errorSummary = "pull http://relay-a:8080: expired",
                                relayCount = 1,
                                gidCount = 1,
                            ),
                            authFailureCode = "client_session_expired",
                        ),
                    ),
                ),
            ),
        )

        val report = runtime.runOnce(listOf("group-1"))

        assertTrue(!report.success)
        assertEquals(2, provider.acquireCount)
        assertEquals("managed_reauth_failed:grant_issue_failed", report.note)
        assertEquals("pull http://relay-a:8080: expired", report.errorSummary)
    }

    private fun successBinding(
        relayUrl: String,
        relayId: String,
        sessionId: String = "crs_test",
    ): ManagedRelayAccessBindingResult {
        val grant = ClientAccessGrant(
            grant_id = "cg_$relayId",
            issued_at = 100,
            expires_at = 900,
            policy_version = 1,
            client_instance_id = "device-a",
            candidate_relays = listOf(RelayCandidate(relayId, relayUrl, "trusted", null)),
            allowed_gids = listOf("group-1"),
            permissions = listOf("ingest", "fetch"),
            limits = ClientAccessGrantLimits(),
            access_token = "token_$relayId",
            connection_mode = "client_selects_and_relay_establishes_session",
            registry_signature = "sig",
        )
        val session = ClientRelaySession(
            session_id = sessionId,
            relay_id = relayId,
            grant_id = grant.grant_id,
            client_instance_id = "device-a",
            allowed_gids = listOf("group-1"),
            permissions = listOf("ingest", "fetch"),
            issued_at = 200,
            expires_at = 800,
            relay_nonce = "nonce",
            session_binding_token = "binding",
        )
        return ManagedRelayAccessBindingResult(
            success = true,
            binding = ManagedRelayAccessBinding(
                relayUrl = relayUrl,
                relayId = relayId,
                grant = grant,
                session = session,
            ),
        )
    }

    private class FakeAccessProvider(
        val acquisitions: ArrayDeque<ManagedRelayAccessBindingResult>,
    ) : ManagedRelayAccessProvider {
        var acquireCount = 0
        var clearCount = 0
        val clearedRelayUrls = mutableListOf<String>()

        override fun isConfigured(): Boolean = true

        override fun acquire(gids: List<String>): ManagedRelayAccessBindingResult {
            acquireCount += 1
            return acquisitions.removeFirst()
        }

        override fun clearCachedBinding(relayUrl: String) {
            clearCount += 1
            clearedRelayUrls += relayUrl
        }
    }

    private class FakeCycleExecutor(
        val results: ArrayDeque<ManagedRelayCycleResult>,
    ) : ManagedRelayCycleExecutor {
        override fun run(binding: ManagedRelayAccessBinding, gids: List<String>): ManagedRelayCycleResult {
            return results.removeFirst()
        }
    }
}
