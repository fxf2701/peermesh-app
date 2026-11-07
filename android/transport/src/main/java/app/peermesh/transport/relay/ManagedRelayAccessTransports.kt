package app.peermesh.transport.relay

import app.peermesh.core.relay.ClientAccessGrant
import app.peermesh.core.relay.ClientRelaySessionCloseRequest
import app.peermesh.core.relay.ClientRelaySessionOpenRequest
import app.peermesh.core.relay.ClientRelaySessionRenewRequest
import app.peermesh.core.relay.RegistryClientSessionIssueRequest

interface RegistryManagedRelayTransport {
    fun issueGrant(request: RegistryClientSessionIssueRequest): RegistryManagedRelayResult

    fun renewGrant(grantId: String, request: RegistryClientSessionIssueRequest): RegistryManagedRelayResult
}

interface RelayManagedSessionTransport {
    fun openManagedSession(
        request: ClientRelaySessionOpenRequest,
        grant: ClientAccessGrant,
    ): RelaySessionMutationResult

    fun renewManagedSession(
        sessionId: String,
        request: ClientRelaySessionRenewRequest,
        grant: ClientAccessGrant,
        session: app.peermesh.core.relay.ClientRelaySession? = null,
    ): RelaySessionMutationResult

    fun closeManagedSession(
        sessionId: String,
        request: ClientRelaySessionCloseRequest,
        session: app.peermesh.core.relay.ClientRelaySession? = null,
    ): RelaySessionMutationResult
}
