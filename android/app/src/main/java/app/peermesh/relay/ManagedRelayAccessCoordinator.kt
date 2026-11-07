package app.peermesh.relay

import android.content.Context
import app.peermesh.DeviceIdProvider
import app.peermesh.config.AppSettings
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.relay.ClientAccessGrant
import app.peermesh.core.relay.ClientRelaySession
import app.peermesh.core.relay.ClientRelaySessionCloseRequest
import app.peermesh.core.relay.ClientRelaySessionOpenRequest
import app.peermesh.core.relay.ClientRelaySessionRenewRequest
import app.peermesh.core.relay.ClientSessionMaterial
import app.peermesh.core.relay.RelayCandidate
import app.peermesh.core.relay.RelayManagedAccessContext
import app.peermesh.core.relay.RegistryClientNetworkHints
import app.peermesh.core.relay.RegistryClientSessionIssueRequest
import app.peermesh.core.relay.toRelayAccessGrantHeaderJson
import app.peermesh.data.debug.ActivityLog
import app.peermesh.transport.relay.RegistryManagedRelayClient
import app.peermesh.transport.relay.RegistryManagedRelayTransport
import app.peermesh.transport.relay.RelayHttpClient
import app.peermesh.transport.relay.RelayManagedSessionTransport
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.encodeToString

interface ManagedRelayBindingStore {
    fun load(): ManagedRelayAccessBinding?
    fun save(binding: ManagedRelayAccessBinding)
    fun clear()
    fun saveBootstrapError(
        code: String,
        message: String,
        clientInstanceId: String,
        gidScope: List<String>,
        recordedAtMs: Long,
    )
    fun clearBootstrapError()
    fun saveSessionOpenError(
        code: String,
        message: String,
        relayUrl: String,
        relayId: String,
        grantId: String,
        clientInstanceId: String,
        gidScope: List<String>,
        recordedAtMs: Long,
    )
    fun clearSessionOpenError()
}

class ManagedRelayAccessCoordinator(
    private val context: Context,
    private val settings: AppSettings,
    private val deviceIdProvider: () -> String = { DeviceIdProvider.get(context) },
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
    private val isDeveloperMode: () -> Boolean = { RelaySyncSettings.isDeveloperMode(context) },
    private val isRelayBlocked: (String, Long) -> Boolean = { relayUrl, nowMs ->
        RelayLocalBlockStore.isBlocked(context, relayUrl, nowMs)
    },
    private val bindingStore: ManagedRelayBindingStore = object : ManagedRelayBindingStore {
        override fun load(): ManagedRelayAccessBinding? = ManagedRelayAccessStore.load(context)
        override fun save(binding: ManagedRelayAccessBinding) = ManagedRelayAccessStore.save(context, binding)
        override fun clear() = ManagedRelayAccessStore.clear(context)
        override fun saveBootstrapError(
            code: String,
            message: String,
            clientInstanceId: String,
            gidScope: List<String>,
            recordedAtMs: Long,
        ) = ManagedRelayAccessStore.saveBootstrapError(context, code, message, clientInstanceId, gidScope, recordedAtMs)
        override fun clearBootstrapError() = ManagedRelayAccessStore.clearBootstrapError(context)
        override fun saveSessionOpenError(
            code: String,
            message: String,
            relayUrl: String,
            relayId: String,
            grantId: String,
            clientInstanceId: String,
            gidScope: List<String>,
            recordedAtMs: Long,
        ) = ManagedRelayAccessStore.saveSessionOpenError(
            context = context,
            code = code,
            message = message,
            relayUrl = relayUrl,
            relayId = relayId,
            grantId = grantId,
            clientInstanceId = clientInstanceId,
            gidScope = gidScope,
            recordedAtMs = recordedAtMs,
        )
        override fun clearSessionOpenError() = ManagedRelayAccessStore.clearSessionOpenError(context)
    },
    private val abuseReportContextSink: (ManagedRelayAccessBinding) -> Unit = { binding ->
        RelayRegistryAbuseReportContextStore.put(
            context,
            RelayRegistryAbuseReportContextStore.AbuseReportContext(
                relayUrl = binding.relayUrl,
                registryBaseUrl = settings.registryBaseUrl,
                relayId = binding.relayId,
                grantId = binding.grant.grant_id,
                clientInstanceId = binding.session.client_instance_id,
                recordedAtMs = nowMsProvider(),
            ),
        )
    },
    private val registryClientFactory: (String, String) -> RegistryManagedRelayTransport = { baseUrl, secret ->
        RegistryManagedRelayClient(baseUrl = baseUrl, requestSignatureSecret = secret)
    },
    private val relayClientFactory: (String) -> RelayManagedSessionTransport = { baseUrl ->
        RelayHttpClient(baseUrl = baseUrl)
    },
) {
    fun isConfigured(): Boolean {
        return settings.registryBaseUrl.isNotBlank() &&
            settings.registryRequestSignatureSecret.isNotBlank()
    }

    fun acquire(gids: List<String>): ManagedRelayAccessBindingResult {
        if (!isConfigured()) {
            return ManagedRelayAccessBindingResult(
                success = false,
                errorCode = "managed_client_access_not_configured",
                errorMessage = "Managed client access is not configured.",
            )
        }
        val normalizedGids = gids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedGids.isEmpty()) {
            return ManagedRelayAccessBindingResult(
                success = false,
                errorCode = "no_gid_scope",
                errorMessage = "No gids available for managed relay sync.",
            )
        }

        val cached = bindingStore.load()?.takeIf { isBindingEligible(it, normalizedGids) }
        if (cached != null) {
            val reused = reuseOrRefreshCached(cached, normalizedGids)
            if (reused.success) {
                return reused
            }
        }
        return issueAndOpenFresh(normalizedGids)
    }

    fun close(binding: ManagedRelayAccessBinding, clearCache: Boolean = true) {
        val relayClient = RelayHttpClient(
            baseUrl = binding.relayUrl,
        )
        val result = relayClient.closeManagedSession(
            sessionId = binding.session.session_id,
            request = ClientRelaySessionCloseRequest(
                client_instance_id = binding.session.client_instance_id,
            ),
            session = binding.session,
        )
        if (clearCache) {
            bindingStore.clear()
        }
        ActivityLog.add(
            label = if (result.success) "Managed relay session closed" else "Managed relay session close failed",
            source = "relay",
            message = "relay=${binding.relayUrl} session=${binding.session.session_id} status=${result.statusCode} error=${result.errorCode ?: "none"}",
        )
    }

    private fun reuseOrRefreshCached(
        cached: ManagedRelayAccessBinding,
        gids: List<String>,
    ): ManagedRelayAccessBindingResult {
        val nowSeconds = nowMsProvider() / 1000L
        val grant = cached.grant
        val session = cached.session

        if (grant.expires_at > nowSeconds && session.expires_at > nowSeconds && !shouldRenewGrant(grant, nowSeconds) && !shouldRenewSession(session, nowSeconds)) {
            ActivityLog.add(
                label = "Managed relay binding reused",
                source = "relay",
                message = "relay=${cached.relayUrl} grant=${grant.grant_id} session=${session.session_id}",
            )
            return ManagedRelayAccessBindingResult(success = true, binding = cached.withAuthContext())
        }

        var currentGrant = grant
        if (grant.expires_at <= nowSeconds || shouldRenewGrant(grant, nowSeconds)) {
            val renewedGrant = renewGrant(grant, gids)
            if (renewedGrant != null) {
                currentGrant = renewedGrant
            } else if (grant.expires_at <= nowSeconds) {
                return issueAndOpenFresh(gids)
            }
        }

        if (session.expires_at > nowSeconds && !shouldRenewSession(session, nowSeconds)) {
            val updated = cached.copy(grant = currentGrant)
            persistBinding(updated)
            return ManagedRelayAccessBindingResult(success = true, binding = updated.withAuthContext())
        }

        val renewedSession = if (session.expires_at > nowSeconds) {
            renewSession(cached.copy(grant = currentGrant), gids)
        } else {
            null
        }
        if (renewedSession != null) {
            persistBinding(renewedSession)
            return ManagedRelayAccessBindingResult(success = true, binding = renewedSession.withAuthContext())
        }

        if (currentGrant.expires_at > nowSeconds) {
            val reopened = openAgainstGrant(currentGrant, gids)
            if (reopened.success) return reopened
        }
        return issueAndOpenFresh(gids)
    }

    private fun issueAndOpenFresh(gids: List<String>): ManagedRelayAccessBindingResult {
        val clientInstanceId = deviceIdProvider()
        val registryClient = registryClientFactory(
            settings.registryBaseUrl,
            settings.registryRequestSignatureSecret,
        )
        val request = RegistryClientSessionIssueRequest(
            client_instance_id = clientInstanceId,
            requested_gids = gids,
            requested_operations = listOf("ingest", "fetch"),
            network_hints = RegistryClientNetworkHints(
                transport = if (isDeveloperMode()) "http" else "https",
            ),
        )
        val grantResult = registryClient.issueGrant(request)
        val grant = grantResult.grantEnvelope?.grant
            ?: run {
                val errorCode = grantResult.errorCode ?: "grant_issue_failed"
                val errorMessage = grantResult.errorMessage ?: "Managed relay access grant failed."
                bindingStore.saveBootstrapError(
                    code = errorCode,
                    message = errorMessage,
                    clientInstanceId = clientInstanceId,
                    gidScope = gids,
                    recordedAtMs = nowMsProvider(),
                )
                bindingStore.clearSessionOpenError()
                return ManagedRelayAccessBindingResult(
                    success = false,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                )
            }
        bindingStore.clearBootstrapError()
        return openAgainstGrant(grant, gids)
    }

    private fun renewGrant(
        existingGrant: ClientAccessGrant,
        gids: List<String>,
    ): ClientAccessGrant? {
        val registryClient = registryClientFactory(
            settings.registryBaseUrl,
            settings.registryRequestSignatureSecret,
        )
        val request = RegistryClientSessionIssueRequest(
            client_instance_id = existingGrant.client_instance_id,
            requested_gids = gids,
            requested_operations = listOf("ingest", "fetch"),
            network_hints = RegistryClientNetworkHints(
                transport = if (isDeveloperMode()) "http" else "https",
            ),
        )
        val result = runCatching {
            registryClient.renewGrant(existingGrant.grant_id, request)
        }.getOrNull() ?: return null
        val grant = result.grantEnvelope?.grant ?: return null
        ActivityLog.add(
            label = "Managed relay grant renewed",
            source = "relay",
            message = "grant=${grant.grant_id} expires=${grant.expires_at}",
        )
        return grant
    }

    private fun openAgainstGrant(
        grant: ClientAccessGrant,
        gids: List<String>,
    ): ManagedRelayAccessBindingResult {
        val candidates = selectCandidates(grant.candidate_relays)
        if (candidates.isEmpty()) {
            bindingStore.saveBootstrapError(
                code = "no_candidate_relay",
                message = "No eligible relay candidate available.",
                clientInstanceId = deviceIdProvider(),
                gidScope = gids,
                recordedAtMs = nowMsProvider(),
            )
            bindingStore.clearSessionOpenError()
            return ManagedRelayAccessBindingResult(
                success = false,
                errorCode = "no_candidate_relay",
                errorMessage = "No eligible relay candidate available.",
            )
        }
        bindingStore.clearBootstrapError()
        val clientInstanceId = deviceIdProvider()
        var lastErrorCode = "session_open_failed"
        var lastErrorMessage = "Managed relay session open failed."
        for (candidate in candidates) {
            val relayUrl = candidate.endpoint.trim().trimEnd('/')
            val relayClient = relayClientFactory(relayUrl)
            val sessionOpen = relayClient.openManagedSession(
                request = ClientRelaySessionOpenRequest(
                    grant_id = grant.grant_id,
                    access_token = grant.access_token,
                    client_instance_id = clientInstanceId,
                    relay_id = candidate.relay_id,
                    requested_gids = gids,
                    requested_operations = listOf("ingest", "fetch"),
                    client_nonce = randomToken(),
                    client_session_material = ClientSessionMaterial(),
                ),
                grant = grant,
            )
            val session = sessionOpen.session
            if (!sessionOpen.success || session == null) {
                lastErrorCode = sessionOpen.errorCode ?: "session_open_failed"
                lastErrorMessage = sessionOpen.errorMessage ?: "Managed relay session open failed."
                bindingStore.saveSessionOpenError(
                    code = lastErrorCode,
                    message = lastErrorMessage,
                    relayUrl = relayUrl,
                    relayId = candidate.relay_id,
                    grantId = grant.grant_id,
                    clientInstanceId = clientInstanceId,
                    gidScope = gids,
                    recordedAtMs = nowMsProvider(),
                )
                ActivityLog.add(
                    label = "Managed relay candidate rejected",
                    source = "relay",
                    message = "relay=$relayUrl relayId=${candidate.relay_id} code=$lastErrorCode",
                )
                continue
            }
            val binding = ManagedRelayAccessBinding(
                relayUrl = relayUrl,
                relayId = candidate.relay_id,
                grant = grant,
                session = session,
            )
            persistBinding(binding)
            ActivityLog.add(
                label = "Managed relay session",
                source = "relay",
                message = "relay=$relayUrl relayId=${candidate.relay_id} grant=${grant.grant_id} session=${session.session_id}",
            )
            return ManagedRelayAccessBindingResult(success = true, binding = binding.withAuthContext())
        }
        return ManagedRelayAccessBindingResult(
            success = false,
            errorCode = lastErrorCode,
            errorMessage = lastErrorMessage,
        )
    }

    private fun renewSession(
        binding: ManagedRelayAccessBinding,
        gids: List<String>,
    ): ManagedRelayAccessBinding? {
        val relayClient = relayClientFactory(binding.relayUrl)
        val result = relayClient.renewManagedSession(
            sessionId = binding.session.session_id,
            request = ClientRelaySessionRenewRequest(
                access_token = binding.grant.access_token,
                client_instance_id = binding.session.client_instance_id,
                requested_gids = gids,
                requested_operations = listOf("ingest", "fetch"),
                client_nonce = randomToken(),
                client_session_material = ClientSessionMaterial(),
            ),
            grant = binding.grant,
            session = binding.session,
        )
        val session = result.session ?: return null
        ActivityLog.add(
            label = "Managed relay session renewed",
            source = "relay",
            message = "relay=${binding.relayUrl} grant=${binding.grant.grant_id} session=${session.session_id}",
        )
        return binding.copy(session = session)
    }

    private fun isBindingEligible(binding: ManagedRelayAccessBinding, gids: List<String>): Boolean {
        val nowMs = nowMsProvider()
        if (binding.relayUrl.isBlank() || binding.relayId.isBlank()) return false
        if (isRelayBlocked(binding.relayUrl, nowMs)) return false
        if (!isDeveloperMode() && !binding.relayUrl.startsWith("https://")) return false
        if (binding.session.client_instance_id != deviceIdProvider()) return false
        val allowed = binding.session.allowed_gids.ifEmpty { binding.grant.allowed_gids }
        return gids.all { allowed.contains(it) }
    }

    private fun persistBinding(binding: ManagedRelayAccessBinding) {
        bindingStore.save(binding)
        bindingStore.clearBootstrapError()
        bindingStore.clearSessionOpenError()
        abuseReportContextSink(binding)
    }

    private fun shouldRenewGrant(grant: ClientAccessGrant, nowSeconds: Long): Boolean {
        return grant.expires_at - nowSeconds <= RENEW_SKEW_SECONDS
    }

    private fun shouldRenewSession(session: ClientRelaySession, nowSeconds: Long): Boolean {
        return session.expires_at - nowSeconds <= RENEW_SKEW_SECONDS
    }

    private fun selectCandidates(candidates: List<RelayCandidate>): List<RelayCandidate> {
        val nowMs = nowMsProvider()
        return candidates
            .asSequence()
            .mapNotNull { candidate ->
                val endpoint = candidate.endpoint.trim()
                if (endpoint.isEmpty()) return@mapNotNull null
                if (!isDeveloperMode() && !endpoint.startsWith("https://")) return@mapNotNull null
                if (isRelayBlocked(endpoint, nowMs)) return@mapNotNull null
                candidate.copy(endpoint = endpoint.trimEnd('/'))
            }
            .toList()
    }

    private fun encodeGrant(grant: ClientAccessGrant): String {
        val bytes = grant.toRelayAccessGrantHeaderJson().toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun randomToken(size: Int = 18): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun ManagedRelayAccessBinding.withAuthContext(): ManagedRelayAccessBinding =
        copy(
            authContext = RelayManagedAccessContext(
                grant = grant,
                session = session,
                encodedGrantHeader = encodeGrant(grant),
            ),
        )

    companion object {
        private const val RENEW_SKEW_SECONDS = 60L
    }
}

data class ManagedRelayAccessBinding(
    val relayUrl: String,
    val relayId: String,
    val grant: ClientAccessGrant,
    val session: ClientRelaySession,
    val authContext: RelayManagedAccessContext? = null,
)

data class ManagedRelayAccessBindingResult(
    val success: Boolean,
    val binding: ManagedRelayAccessBinding? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)
