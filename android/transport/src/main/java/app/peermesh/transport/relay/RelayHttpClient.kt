package app.peermesh.transport.relay

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.net.RequestSigning
import app.peermesh.core.relay.ClientRelaySessionCloseRequest
import app.peermesh.core.relay.ClientRelaySessionEnvelope
import app.peermesh.core.relay.ClientRelaySessionOpenRequest
import app.peermesh.core.relay.ClientRelaySessionRenewRequest
import app.peermesh.core.relay.ClientRelaySession
import app.peermesh.core.relay.ClientAccessGrant
import app.peermesh.core.relay.RelayManagedAccessContext
import app.peermesh.core.relay.RelayFetchResponse
import app.peermesh.core.relay.RelayIngestRequest
import app.peermesh.core.relay.RelayIngestResponse
import app.peermesh.core.relay.toRelayAccessGrantHeaderJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log
import java.util.Base64

class RelayHttpClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    private val requestSignatureSecret: String? = null,
    private val managedAccessContext: RelayManagedAccessContext? = null,
) : RelayManagedSessionTransport {
    fun ingest(request: RelayIngestRequest): RelayIngestResult {
        val requestTarget = "/v1/events:ingest"
        val url = URL(baseUrl.trimEnd('/') + requestTarget)
        val payload = CanonicalJson.json.encodeToString(RelayIngestRequest.serializer(), request)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val startMs = System.currentTimeMillis()
        Log.i(TAG, "Relay HTTP ingest url=$url")
        RelayTrafficStats.addUploaded(payloadBytes.size.toLong())
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            applyManagedAccessHeaders(managedAccessContext)
            applyRequestSignature(
                method = "POST",
                requestTarget = requestTarget,
                bodyBytes = payloadBytes,
            )
        }

        return try {
            connection.outputStream.use { it.write(payloadBytes) }
            val status = connection.responseCode
            val body = readBody(connection)
            if (status in 200..299) {
                val parsed = body?.let {
                    CanonicalJson.json.decodeFromString(RelayIngestResponse.serializer(), it)
                }
                RelayTrafficStats.addDownloaded(body?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.i(
                    TAG,
                    "Relay HTTP ingest ok status=$status accepted=${parsed?.accepted?.size ?: 0} rejected=${parsed?.rejected?.size ?: 0} elapsedMs=$elapsedMs",
                )
                RelayIngestResult(
                    success = true,
                    statusCode = status,
                    accepted = parsed?.accepted?.size ?: 0,
                    rejected = parsed?.rejected?.size ?: 0,
                    errorMessage = null,
                    errorCode = null,
                    rejectedReasons = parsed?.rejected
                        ?.mapNotNull { it.reason ?: it.eid }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList(),
                )
            } else {
                val relayError = parseRelayError(body)
                RelayTrafficStats.addDownloaded(body?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.w(TAG, "Relay HTTP ingest error status=$status body=${body ?: "empty"} elapsedMs=$elapsedMs")
                RelayIngestResult(
                    success = false,
                    statusCode = status,
                    accepted = 0,
                    rejected = 0,
                    errorMessage = relayError?.message ?: body,
                    errorCode = relayError?.code,
                    rejectedReasons = emptyList(),
                )
            }
        } catch (err: Exception) {
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.w(TAG, "Relay HTTP ingest exception ${err.message ?: "unknown"} elapsedMs=$elapsedMs")
            RelayIngestResult(
                success = false,
                statusCode = 0,
                accepted = 0,
                rejected = 0,
                errorMessage = err.message,
                errorCode = null,
                rejectedReasons = emptyList(),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun fetch(gid: String, cursor: String?, limit: Int): RelayFetchResult {
        val query = buildString {
            append("?gid=")
            append(encode(gid))
            if (!cursor.isNullOrBlank()) {
                append("&cursor=")
                append(encode(cursor))
            }
            append("&limit=")
            append(limit)
        }
        val requestTarget = "/v1/events:fetch" + query
        return executeFetch(requestTarget)
    }

    fun fetchByEventIds(gid: String, eventIds: List<String>): RelayFetchResult {
        val ids = eventIds.filter { it.isNotBlank() }.distinct().take(MAX_REPAIR_EVENT_IDS)
        if (gid.isBlank() || ids.isEmpty()) {
            return RelayFetchResult(true, 0, emptyList(), null, null, null)
        }
        val requestTarget = buildString {
            append("/v1/events:fetch?gid=")
            append(encode(gid))
            append("&event_ids=")
            append(encode(ids.joinToString(",")))
            append("&limit=")
            append(ids.size)
        }
        return executeFetch(requestTarget)
    }

    private fun executeFetch(requestTarget: String): RelayFetchResult {
        val url = URL(baseUrl.trimEnd('/') + requestTarget)
        val startMs = System.currentTimeMillis()
        Log.i(TAG, "Relay HTTP fetch url=$url")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            applyManagedAccessHeaders(managedAccessContext)
            applyRequestSignature(
                method = "GET",
                requestTarget = requestTarget,
                bodyBytes = ByteArray(0),
            )
        }

        return try {
            val status = connection.responseCode
            val body = readBody(connection)
            if (status in 200..299) {
                val parsed = body?.let {
                    CanonicalJson.json.decodeFromString(RelayFetchResponse.serializer(), it)
                }
                RelayTrafficStats.addDownloaded(body?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.i(TAG, "Relay HTTP fetch ok status=$status events=${parsed?.events?.size ?: 0} elapsedMs=$elapsedMs")
                RelayFetchResult(
                    success = true,
                    statusCode = status,
                    events = parsed?.events ?: emptyList(),
                    nextCursor = parsed?.next_cursor,
                    errorMessage = null,
                    errorCode = null,
                )
            } else {
                val relayError = parseRelayError(body)
                RelayTrafficStats.addDownloaded(body?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)
                val elapsedMs = System.currentTimeMillis() - startMs
                Log.w(TAG, "Relay HTTP fetch error status=$status body=${body ?: "empty"} elapsedMs=$elapsedMs")
                RelayFetchResult(
                    success = false,
                    statusCode = status,
                    events = emptyList(),
                    nextCursor = null,
                    errorMessage = relayError?.message ?: body,
                    errorCode = relayError?.code,
                )
            }
        } catch (err: Exception) {
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.w(TAG, "Relay HTTP fetch exception ${err.message ?: "unknown"} elapsedMs=$elapsedMs")
            RelayFetchResult(
                success = false,
                statusCode = 0,
                events = emptyList(),
                nextCursor = null,
                errorMessage = err.message,
                errorCode = null,
            )
        } finally {
            connection.disconnect()
        }
    }

    override fun openManagedSession(
        request: ClientRelaySessionOpenRequest,
        grant: ClientAccessGrant,
    ): RelaySessionMutationResult {
        val requestTarget = "/v1/client/session:open"
        val url = URL(baseUrl.trimEnd('/') + requestTarget)
        val payload = CanonicalJson.json.encodeToString(request)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Relay-Access-Grant", encodeGrant(grant))
            applyRequestSignature(
                method = "POST",
                requestTarget = requestTarget,
                bodyBytes = payloadBytes,
            )
        }
        return executeSessionMutation(connection, payloadBytes)
    }

    override fun closeManagedSession(
        sessionId: String,
        request: ClientRelaySessionCloseRequest,
        session: ClientRelaySession?,
    ): RelaySessionMutationResult {
        val requestTarget = "/v1/client/session/${encodePathSegment(sessionId)}:close"
        val url = URL(baseUrl.trimEnd('/') + requestTarget)
        val payload = CanonicalJson.json.encodeToString(request)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            applyManagedSessionHeaders(session)
            applyRequestSignature(
                method = "POST",
                requestTarget = requestTarget,
                bodyBytes = payloadBytes,
            )
        }
        return executeSessionMutation(connection, payloadBytes)
    }

    override fun renewManagedSession(
        sessionId: String,
        request: ClientRelaySessionRenewRequest,
        grant: ClientAccessGrant,
        session: ClientRelaySession?,
    ): RelaySessionMutationResult {
        val requestTarget = "/v1/client/session/${encodePathSegment(sessionId)}:renew"
        val url = URL(baseUrl.trimEnd('/') + requestTarget)
        val payload = CanonicalJson.json.encodeToString(request)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Relay-Access-Grant", encodeGrant(grant))
            applyManagedSessionHeaders(session)
            applyRequestSignature(
                method = "POST",
                requestTarget = requestTarget,
                bodyBytes = payloadBytes,
            )
        }
        return executeSessionMutation(connection, payloadBytes)
    }

    private fun executeSessionMutation(
        connection: HttpURLConnection,
        payloadBytes: ByteArray,
    ): RelaySessionMutationResult {
        return try {
            connection.outputStream.use { it.write(payloadBytes) }
            val status = connection.responseCode
            val body = readBody(connection)
            if (status in 200..299) {
                val parsed = body?.let {
                    CanonicalJson.json.decodeFromString(ClientRelaySessionEnvelope.serializer(), it)
                }
                RelaySessionMutationResult(
                    success = true,
                    statusCode = status,
                    session = parsed?.session,
                    errorCode = null,
                    errorMessage = null,
                )
            } else {
                val relayError = parseRelayError(body)
                RelaySessionMutationResult(
                    success = false,
                    statusCode = status,
                    session = null,
                    errorCode = relayError?.code,
                    errorMessage = relayError?.message ?: body,
                )
            }
        } catch (err: Exception) {
            RelaySessionMutationResult(
                success = false,
                statusCode = 0,
                session = null,
                errorCode = null,
                errorMessage = err.message,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.applyRequestSignature(
        method: String,
        requestTarget: String,
        bodyBytes: ByteArray,
    ) {
        val secret = requestSignatureSecret?.takeIf { it.isNotBlank() } ?: return
        val timestampSeconds = (System.currentTimeMillis() / 1000L).toString()
        val signature = RequestSigning.signHmacSha256Hex(
            secret = secret,
            method = method,
            requestTarget = requestTarget,
            timestampSeconds = timestampSeconds,
            bodyBytes = bodyBytes,
        )
        setRequestProperty("X-Relay-Timestamp", timestampSeconds)
        setRequestProperty("X-Relay-Signature", signature)
    }

    private fun HttpURLConnection.applyManagedAccessHeaders(
        context: RelayManagedAccessContext?,
    ) {
        if (context == null) return
        setRequestProperty("X-Relay-Access-Grant", context.encodedGrantHeader)
        setRequestProperty("X-Relay-Access-Token", context.grant.access_token)
        applyManagedSessionHeaders(context.session)
    }

    private fun HttpURLConnection.applyManagedSessionHeaders(
        session: ClientRelaySession?,
    ) {
        if (session == null) return
        setRequestProperty("X-Client-Relay-Session", session.session_id)
        setRequestProperty("X-Client-Relay-Session-Token", session.session_binding_token)
    }

    private fun encodeGrant(grant: ClientAccessGrant): String {
        val json = grant.toRelayAccessGrantHeaderJson()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private fun encodePathSegment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun readBody(connection: HttpURLConnection): String? {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return null
        BufferedReader(InputStreamReader(stream)).use { reader ->
            return reader.readText().trim()
        }
    }

    private fun parseRelayError(body: String?): RelayErrorEnvelope? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            CanonicalJson.json.decodeFromString<RelayErrorResponse>(body).error
        }.getOrNull()
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "RelayHttpClient"
        private const val MAX_REPAIR_EVENT_IDS = 32
    }
}

@Serializable
private data class RelayErrorResponse(
    val error: RelayErrorEnvelope? = null,
)

@Serializable
private data class RelayErrorEnvelope(
    val code: String? = null,
    val message: String? = null,
)

data class RelayIngestResult(
    val success: Boolean,
    val statusCode: Int,
    val accepted: Int,
    val rejected: Int,
    val errorMessage: String?,
    val errorCode: String?,
    val rejectedReasons: List<String>,
)

data class RelayFetchResult(
    val success: Boolean,
    val statusCode: Int,
    val events: List<RelayFetchEvent>,
    val nextCursor: String?,
    val errorMessage: String?,
    val errorCode: String?,
)

data class RelaySessionMutationResult(
    val success: Boolean,
    val statusCode: Int,
    val session: ClientRelaySession?,
    val errorCode: String?,
    val errorMessage: String?,
)

typealias RelayFetchEvent = app.peermesh.core.relay.RelayEvent
