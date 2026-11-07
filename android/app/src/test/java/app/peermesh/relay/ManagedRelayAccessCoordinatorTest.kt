package app.peermesh.relay

import android.content.ContextWrapper
import app.peermesh.config.AppSettings
import app.peermesh.config.Distribution
import app.peermesh.config.Environment
import app.peermesh.core.relay.ClientAccessGrant
import app.peermesh.core.relay.ClientAccessGrantLimits
import app.peermesh.core.relay.ClientRelaySession
import app.peermesh.core.relay.ClientRelaySessionOpenRequest
import app.peermesh.core.relay.ClientRelaySessionRenewRequest
import app.peermesh.core.relay.RelayCandidate
import app.peermesh.core.relay.RegistryClientGrantEnvelope
import app.peermesh.core.relay.RegistryClientSessionIssueRequest
import app.peermesh.transport.relay.RegistryManagedRelayResult
import app.peermesh.transport.relay.RegistryManagedRelayTransport
import app.peermesh.transport.relay.RelayManagedSessionTransport
import app.peermesh.transport.relay.RelaySessionMutationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedRelayAccessCoordinatorTest {
    @Test
    fun `reuses cached binding without network when grant and session are healthy`() {
        val nowMs = 1_716_900_000_000L
        val store = FakeBindingStore(
            binding = createBinding(
                relayUrl = "http://relay-a:8080",
                relayId = "relay-a",
                grantExpiresAt = nowMs / 1000L + 600,
                sessionExpiresAt = nowMs / 1000L + 600,
            ),
        )
        val registry = FakeRegistryClient()
        val relayClients = mutableMapOf<String, FakeRelayClient>()
        val coordinator = coordinator(
            nowMs = nowMs,
            store = store,
            registry = registry,
            relayClients = relayClients,
        )

        val result = coordinator.acquire(listOf("group-1"))

        assertTrue(result.success)
        assertEquals(0, registry.issueCount)
        assertEquals(0, registry.renewCount)
        assertTrue(relayClients.isEmpty())
        assertEquals("crs_test", result.binding?.session?.session_id)
    }

    @Test
    fun `renews cached grant and session near expiry`() {
        val nowMs = 1_716_900_000_000L
        val store = FakeBindingStore(
            binding = createBinding(
                relayUrl = "http://relay-a:8080",
                relayId = "relay-a",
                grantExpiresAt = nowMs / 1000L + 30,
                sessionExpiresAt = nowMs / 1000L + 30,
            ),
        )
        val renewedGrant = createGrant(
            relayId = "relay-a",
            endpoint = "http://relay-a:8080",
            expiresAt = nowMs / 1000L + 600,
        )
        val renewedSession = createSession(
            relayId = "relay-a",
            grantId = renewedGrant.grant_id,
            expiresAt = nowMs / 1000L + 600,
            sessionId = "crs_renewed",
        )
        val registry = FakeRegistryClient(renewGrantResult = renewedGrant)
        val relayClient = FakeRelayClient(renewSessionResult = renewedSession)
        val coordinator = coordinator(
            nowMs = nowMs,
            store = store,
            registry = registry,
            relayClients = mutableMapOf("http://relay-a:8080" to relayClient),
        )

        val result = coordinator.acquire(listOf("group-1"))

        assertTrue(result.success)
        assertEquals(0, registry.issueCount)
        assertEquals(1, registry.renewCount)
        assertEquals(1, relayClient.renewCount)
        assertEquals("crs_renewed", store.binding?.session?.session_id)
        assertEquals(renewedGrant.expires_at, store.binding?.grant?.expires_at)
    }

    @Test
    fun `reopens session from cached grant when registry is unavailable`() {
        val nowMs = 1_716_900_000_000L
        val cachedGrant = createGrant(
            relayId = "relay-a",
            endpoint = "http://relay-a:8080",
            expiresAt = nowMs / 1000L + 600,
        )
        val store = FakeBindingStore(
            binding = ManagedRelayAccessBinding(
                relayUrl = "http://relay-a:8080",
                relayId = "relay-a",
                grant = cachedGrant,
                session = createSession(
                    relayId = "relay-a",
                    grantId = cachedGrant.grant_id,
                    expiresAt = nowMs / 1000L - 10,
                    sessionId = "crs_old",
                ),
            ),
        )
        val reopenedSession = createSession(
            relayId = "relay-a",
            grantId = cachedGrant.grant_id,
            expiresAt = nowMs / 1000L + 500,
            sessionId = "crs_reopened",
        )
        val relayClient = FakeRelayClient(openSessionResults = mutableListOf(successResult(session = reopenedSession)))
        val coordinator = coordinator(
            nowMs = nowMs,
            store = store,
            registry = FakeRegistryClient(),
            relayClients = mutableMapOf("http://relay-a:8080" to relayClient),
        )

        val result = coordinator.acquire(listOf("group-1"))

        assertTrue(result.success)
        assertEquals(1, relayClient.openCount)
        assertEquals("crs_reopened", result.binding?.session?.session_id)
        assertEquals("crs_reopened", store.binding?.session?.session_id)
    }

    @Test
    fun `fails over to next candidate when first relay rejects session open`() {
        val nowMs = 1_716_900_000_000L
        val store = FakeBindingStore()
        val grant = createGrant(
            relayId = "relay-a",
            endpoint = "http://relay-a:8080",
            expiresAt = nowMs / 1000L + 600,
            candidates = listOf(
                RelayCandidate("relay-a", "http://relay-a:8080", "trusted", null),
                RelayCandidate("relay-b", "http://relay-b:8080", "trusted", null),
            ),
        )
        val registry = FakeRegistryClient(issueGrantResult = grant)
        val relayA = FakeRelayClient(
            openSessionResults = mutableListOf(
                RelaySessionMutationResult(
                    success = false,
                    statusCode = 403,
                    session = null,
                    errorCode = "wrong_destination_relay",
                    errorMessage = "denied",
                ),
            ),
        )
        val relayBSession = createSession(
            relayId = "relay-b",
            grantId = grant.grant_id,
            expiresAt = nowMs / 1000L + 600,
            sessionId = "crs_b",
        )
        val relayB = FakeRelayClient(openSessionResults = mutableListOf(successResult(session = relayBSession)))
        val coordinator = coordinator(
            nowMs = nowMs,
            store = store,
            registry = registry,
            relayClients = mutableMapOf(
                "http://relay-a:8080" to relayA,
                "http://relay-b:8080" to relayB,
            ),
        )

        val result = coordinator.acquire(listOf("group-1"))

        assertTrue(result.success)
        assertEquals(1, registry.issueCount)
        assertEquals(1, relayA.openCount)
        assertEquals(1, relayB.openCount)
        assertEquals("relay-b", result.binding?.relayId)
        assertEquals("http://relay-b:8080", result.binding?.relayUrl)
        assertEquals(null, store.bootstrapError)
        assertEquals(null, store.sessionOpenError)
    }

    @Test
    fun `stores bootstrap error when grant issue fails`() {
        val nowMs = 1_716_900_000_000L
        val store = FakeBindingStore()
        val coordinator = coordinator(
            nowMs = nowMs,
            store = store,
            registry = FakeRegistryClient(),
            relayClients = mutableMapOf(),
        )

        val result = coordinator.acquire(listOf("group-1"))

        assertTrue(!result.success)
        assertEquals("grant_issue_failed", result.errorCode)
        assertEquals("grant_issue_failed", store.bootstrapError?.code)
        assertEquals("device-a", store.bootstrapError?.clientInstanceId)
        assertEquals(listOf("group-1"), store.bootstrapError?.gidScope)
        assertEquals(nowMs, store.bootstrapError?.recordedAtMs)
    }

    @Test
    fun `stores session open error when all candidates reject`() {
        val nowMs = 1_716_900_000_000L
        val grant = createGrant(
            relayId = "relay-a",
            endpoint = "http://relay-a:8080",
            expiresAt = nowMs / 1000L + 600,
        )
        val store = FakeBindingStore()
        val coordinator = coordinator(
            nowMs = nowMs,
            store = store,
            registry = FakeRegistryClient(issueGrantResult = grant),
            relayClients = mutableMapOf(
                "http://relay-a:8080" to FakeRelayClient(
                    openSessionResults = mutableListOf(
                        RelaySessionMutationResult(
                            success = false,
                            statusCode = 403,
                            session = null,
                            errorCode = "wrong_destination_relay",
                            errorMessage = "denied",
                        ),
                    ),
                ),
            ),
        )

        val result = coordinator.acquire(listOf("group-1"))

        assertTrue(!result.success)
        assertEquals("wrong_destination_relay", result.errorCode)
        assertEquals("wrong_destination_relay", store.sessionOpenError?.code)
        assertEquals("http://relay-a:8080", store.sessionOpenError?.relayUrl)
        assertEquals("relay-a", store.sessionOpenError?.relayId)
        assertEquals("cg_test", store.sessionOpenError?.grantId)
        assertEquals(listOf("group-1"), store.sessionOpenError?.gidScope)
        assertEquals(nowMs, store.sessionOpenError?.recordedAtMs)
    }

    private fun coordinator(
        nowMs: Long,
        store: FakeBindingStore,
        registry: FakeRegistryClient,
        relayClients: MutableMap<String, FakeRelayClient>,
    ): ManagedRelayAccessCoordinator {
        val settings = AppSettings(
            environment = Environment.DEV,
            distribution = Distribution.OFFICIAL,
            relayBaseUrl = "",
            registryBaseUrl = "http://registry:8090",
            relayRequestSignatureSecret = "",
            registryRequestSignatureSecret = "registry-secret",
        )
        return ManagedRelayAccessCoordinator(
            context = ContextWrapper(null),
            settings = settings,
            deviceIdProvider = { "device-a" },
            nowMsProvider = { nowMs },
            isDeveloperMode = { true },
            isRelayBlocked = { _, _ -> false },
            bindingStore = store,
            abuseReportContextSink = {},
            registryClientFactory = { _, _ -> registry },
            relayClientFactory = { baseUrl -> relayClients.getOrPut(baseUrl) { FakeRelayClient() } },
        )
    }

    private fun createBinding(
        relayUrl: String,
        relayId: String,
        grantExpiresAt: Long,
        sessionExpiresAt: Long,
    ): ManagedRelayAccessBinding {
        val grant = createGrant(relayId, relayUrl, grantExpiresAt)
        return ManagedRelayAccessBinding(
            relayUrl = relayUrl,
            relayId = relayId,
            grant = grant,
            session = createSession(relayId, grant.grant_id, sessionExpiresAt),
        )
    }

    private fun createGrant(
        relayId: String,
        endpoint: String,
        expiresAt: Long,
        candidates: List<RelayCandidate> = listOf(
            RelayCandidate(relayId, endpoint, "trusted", null),
        ),
    ): ClientAccessGrant {
        return ClientAccessGrant(
            grant_id = "cg_test",
            issued_at = expiresAt - 300,
            expires_at = expiresAt,
            policy_version = 1,
            client_instance_id = "device-a",
            candidate_relays = candidates,
            allowed_gids = listOf("group-1"),
            permissions = listOf("ingest", "fetch"),
            limits = ClientAccessGrantLimits(),
            access_token = "access-token",
            connection_mode = "client_selects_and_relay_establishes_session",
            registry_signature = "sig",
        )
    }

    private fun createSession(
        relayId: String,
        grantId: String,
        expiresAt: Long,
        sessionId: String = "crs_test",
    ): ClientRelaySession {
        return ClientRelaySession(
            session_id = sessionId,
            relay_id = relayId,
            grant_id = grantId,
            client_instance_id = "device-a",
            allowed_gids = listOf("group-1"),
            permissions = listOf("ingest", "fetch"),
            issued_at = expiresAt - 120,
            expires_at = expiresAt,
            relay_nonce = "nonce",
            session_binding_token = "binding-token",
        )
    }

    private fun successResult(session: ClientRelaySession) =
        RelaySessionMutationResult(
            success = true,
            statusCode = 200,
            session = session,
            errorCode = null,
            errorMessage = null,
        )

    private class FakeBindingStore(
        var binding: ManagedRelayAccessBinding? = null,
    ) : ManagedRelayBindingStore {
        data class BootstrapError(
            val code: String,
            val message: String,
            val clientInstanceId: String,
            val gidScope: List<String>,
            val recordedAtMs: Long,
        )

        data class SessionOpenError(
            val code: String,
            val message: String,
            val relayUrl: String,
            val relayId: String,
            val grantId: String,
            val clientInstanceId: String,
            val gidScope: List<String>,
            val recordedAtMs: Long,
        )

        var saveCount = 0
        var clearCount = 0
        var bootstrapError: BootstrapError? = null
        var sessionOpenError: SessionOpenError? = null

        override fun load(): ManagedRelayAccessBinding? = binding

        override fun save(binding: ManagedRelayAccessBinding) {
            this.binding = binding
            saveCount += 1
        }

        override fun clear() {
            binding = null
            clearCount += 1
        }

        override fun saveBootstrapError(
            code: String,
            message: String,
            clientInstanceId: String,
            gidScope: List<String>,
            recordedAtMs: Long,
        ) {
            bootstrapError = BootstrapError(code, message, clientInstanceId, gidScope, recordedAtMs)
        }

        override fun clearBootstrapError() {
            bootstrapError = null
        }

        override fun saveSessionOpenError(
            code: String,
            message: String,
            relayUrl: String,
            relayId: String,
            grantId: String,
            clientInstanceId: String,
            gidScope: List<String>,
            recordedAtMs: Long,
        ) {
            sessionOpenError = SessionOpenError(
                code = code,
                message = message,
                relayUrl = relayUrl,
                relayId = relayId,
                grantId = grantId,
                clientInstanceId = clientInstanceId,
                gidScope = gidScope,
                recordedAtMs = recordedAtMs,
            )
        }

        override fun clearSessionOpenError() {
            sessionOpenError = null
        }
    }

    private class FakeRegistryClient(
        private val issueGrantResult: ClientAccessGrant? = null,
        private val renewGrantResult: ClientAccessGrant? = null,
    ) : RegistryManagedRelayTransport {
        var issueCount = 0
        var renewCount = 0

        override fun issueGrant(request: RegistryClientSessionIssueRequest): RegistryManagedRelayResult {
            issueCount += 1
            val grant = issueGrantResult
            return if (grant != null) {
                RegistryManagedRelayResult(
                    success = true,
                    statusCode = 201,
                    grantEnvelope = RegistryClientGrantEnvelope(ok = true, grant = grant),
                    errorCode = null,
                    errorMessage = null,
                )
            } else {
                RegistryManagedRelayResult(false, 503, null, "grant_issue_failed", "failed")
            }
        }

        override fun renewGrant(grantId: String, request: RegistryClientSessionIssueRequest): RegistryManagedRelayResult {
            renewCount += 1
            val grant = renewGrantResult
            return if (grant != null) {
                RegistryManagedRelayResult(
                    success = true,
                    statusCode = 200,
                    grantEnvelope = RegistryClientGrantEnvelope(ok = true, grant = grant),
                    errorCode = null,
                    errorMessage = null,
                )
            } else {
                RegistryManagedRelayResult(false, 503, null, "grant_renew_failed", "failed")
            }
        }
    }

    private class FakeRelayClient(
        val openSessionResults: MutableList<RelaySessionMutationResult> = mutableListOf(),
        private val renewSessionResult: ClientRelaySession? = null,
    ) : RelayManagedSessionTransport {
        var openCount = 0
        var renewCount = 0
        var closeCount = 0

        override fun openManagedSession(
            request: ClientRelaySessionOpenRequest,
            grant: ClientAccessGrant,
        ): RelaySessionMutationResult {
            openCount += 1
            return openSessionResults.removeFirstOrNull()
                ?: RelaySessionMutationResult(false, 500, null, "missing_fake_open_result", "missing")
        }

        override fun renewManagedSession(
            sessionId: String,
            request: ClientRelaySessionRenewRequest,
            grant: ClientAccessGrant,
            session: ClientRelaySession?,
        ): RelaySessionMutationResult {
            renewCount += 1
            return renewSessionResult?.let {
                RelaySessionMutationResult(
                    success = true,
                    statusCode = 200,
                    session = it,
                    errorCode = null,
                    errorMessage = null,
                )
            }
                ?: RelaySessionMutationResult(false, 500, null, "missing_fake_renew_result", "missing")
        }

        override fun closeManagedSession(
            sessionId: String,
            request: app.peermesh.core.relay.ClientRelaySessionCloseRequest,
            session: ClientRelaySession?,
        ): RelaySessionMutationResult {
            closeCount += 1
            return RelaySessionMutationResult(true, 200, null, null, null)
        }
    }
}
