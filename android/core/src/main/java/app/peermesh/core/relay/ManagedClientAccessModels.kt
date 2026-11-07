package app.peermesh.core.relay

import app.peermesh.core.json.CanonicalJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RelayCandidate(
    val relay_id: String,
    val endpoint: String,
    val trust_level: String? = null,
    val region: String? = null,
)

@Serializable
data class ClientAccessGrantLimits(
    val max_events_per_minute: Int? = null,
    val max_bytes_per_minute: Int? = null,
    val max_in_flight: Int? = null,
)

@Serializable
data class ClientAccessGrant(
    val grant_id: String,
    val issued_at: Long,
    val expires_at: Long,
    val policy_version: Int = 1,
    val client_instance_id: String,
    val candidate_relays: List<RelayCandidate> = emptyList(),
    val allowed_gids: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val limits: ClientAccessGrantLimits = ClientAccessGrantLimits(),
    val access_token: String,
    val connection_mode: String? = null,
    val registry_signature: String,
    val network_hints: RegistryClientNetworkHints? = null,
    val revoked_at: Long? = null,
    val created_at: Long? = null,
    val updated_at: Long? = null,
)

@Serializable
data class RegistryClientSessionIssueRequest(
    val client_instance_id: String,
    val requested_gids: List<String>,
    val requested_operations: List<String>,
    val network_hints: RegistryClientNetworkHints? = null,
)

@Serializable
data class RegistryClientNetworkHints(
    val region: String? = null,
    val transport: String? = null,
)

@Serializable
data class RegistryClientGrantEnvelope(
    val ok: Boolean,
    val grant: ClientAccessGrant? = null,
)

@Serializable
data class ClientSessionMaterial(
    val version: Int = 1,
    val mode: String = "session_binding",
)

@Serializable
data class ClientAccessGrantScope(
    val grant_id: String,
    val policy_version: Int = 1,
    val allowed_gids: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
)

@Serializable
data class ClientRelaySessionOpenRequest(
    val grant_id: String,
    val access_token: String,
    val client_instance_id: String,
    val relay_id: String,
    val requested_gids: List<String>,
    val requested_operations: List<String>,
    val client_nonce: String,
    val client_session_material: ClientSessionMaterial = ClientSessionMaterial(),
)

@Serializable
data class ClientRelaySessionRenewRequest(
    val access_token: String,
    val client_instance_id: String,
    val requested_gids: List<String>,
    val requested_operations: List<String>,
    val client_nonce: String,
    val client_session_material: ClientSessionMaterial = ClientSessionMaterial(),
)

@Serializable
data class ClientRelaySessionCloseRequest(
    val client_instance_id: String,
)

@Serializable
data class ClientRelaySession(
    val session_id: String,
    val relay_id: String,
    val grant_id: String,
    val client_instance_id: String,
    val allowed_gids: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val issued_at: Long,
    val expires_at: Long,
    val relay_nonce: String? = null,
    val session_binding_token: String,
    val relay_session_material: ClientSessionMaterial? = null,
    val client_session_material: ClientSessionMaterial? = null,
    val client_nonce: String? = null,
    val access_grant_expires_at: Long? = null,
    val access_grant_scope: ClientAccessGrantScope? = null,
    val access_grant_snapshot: ClientAccessGrant? = null,
    val created_at: Long? = null,
    val updated_at: Long? = null,
    val closed_at: Long? = null,
)

@Serializable
data class ClientRelaySessionEnvelope(
    val ok: Boolean,
    val session: ClientRelaySession? = null,
)

data class RelayManagedAccessContext(
    val grant: ClientAccessGrant,
    val session: ClientRelaySession,
    val encodedGrantHeader: String,
)

fun ClientAccessGrant.toRelayAccessGrantHeaderJson(): String {
    val candidateRelays = JsonArray(
        candidate_relays.map { candidate ->
            JsonObject(
                buildMap {
                    put("relay_id", JsonPrimitive(candidate.relay_id))
                    put("endpoint", JsonPrimitive(candidate.endpoint))
                    put(
                        "trust_level",
                        candidate.trust_level?.let(::JsonPrimitive) ?: JsonNull,
                    )
                    put(
                        "region",
                        candidate.region?.let(::JsonPrimitive) ?: JsonNull,
                    )
                },
            )
        },
    )
    val limitsObject = JsonObject(
        buildMap {
            limits.max_events_per_minute?.let { put("max_events_per_minute", JsonPrimitive(it)) }
            limits.max_bytes_per_minute?.let { put("max_bytes_per_minute", JsonPrimitive(it)) }
            limits.max_in_flight?.let { put("max_in_flight", JsonPrimitive(it)) }
        },
    )
    val root = JsonObject(
        buildMap {
            put("grant_id", JsonPrimitive(grant_id))
            put("issued_at", JsonPrimitive(issued_at))
            put("expires_at", JsonPrimitive(expires_at))
            put("policy_version", JsonPrimitive(policy_version))
            put("client_instance_id", JsonPrimitive(client_instance_id))
            put("candidate_relays", candidateRelays)
            put("allowed_gids", JsonArray(allowed_gids.map(::JsonPrimitive)))
            put("permissions", JsonArray(permissions.map(::JsonPrimitive)))
            put("limits", limitsObject)
            put("access_token", JsonPrimitive(access_token))
            connection_mode?.let { put("connection_mode", JsonPrimitive(it)) }
            put("registry_signature", JsonPrimitive(registry_signature))
        },
    )
    return CanonicalJson.canonicalize(root)
}
