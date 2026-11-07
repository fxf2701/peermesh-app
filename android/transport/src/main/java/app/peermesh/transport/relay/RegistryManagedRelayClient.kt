package app.peermesh.transport.relay

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.net.RequestSigning
import app.peermesh.core.relay.RegistryClientGrantEnvelope
import app.peermesh.core.relay.RegistryClientSessionIssueRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class RegistryManagedRelayClient(
    private val baseUrl: String,
    private val requestSignatureSecret: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : RegistryManagedRelayTransport {
    override fun issueGrant(
        request: RegistryClientSessionIssueRequest,
    ): RegistryManagedRelayResult {
        val requestTarget = "/v1/registry/client/sessions"
        val payload = CanonicalJson.json.encodeToString(request)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val connection = openJsonPost(requestTarget, payloadBytes)
        return executeGrantMutation(connection, payloadBytes)
    }

    override fun renewGrant(
        grantId: String,
        request: RegistryClientSessionIssueRequest,
    ): RegistryManagedRelayResult {
        val requestTarget = "/v1/registry/client/sessions/${encodePathSegment(grantId)}:renew"
        val payload = CanonicalJson.json.encodeToString(request)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val connection = openJsonPost(requestTarget, payloadBytes)
        return executeGrantMutation(connection, payloadBytes)
    }

    private fun executeGrantMutation(
        connection: HttpURLConnection,
        payloadBytes: ByteArray,
    ): RegistryManagedRelayResult {
        return try {
            connection.outputStream.use { it.write(payloadBytes) }
            val status = connection.responseCode
            val body = readBody(connection)
            if (status in 200..299) {
                val parsed = body?.let {
                    CanonicalJson.json.decodeFromString(RegistryClientGrantEnvelope.serializer(), it)
                }
                RegistryManagedRelayResult(
                    success = true,
                    statusCode = status,
                    grantEnvelope = parsed,
                    errorCode = null,
                    errorMessage = null,
                )
            } else {
                val error = parseRegistryError(body)
                RegistryManagedRelayResult(
                    success = false,
                    statusCode = status,
                    grantEnvelope = null,
                    errorCode = error?.code,
                    errorMessage = error?.message ?: body,
                )
            }
        } catch (err: Exception) {
            RegistryManagedRelayResult(
                success = false,
                statusCode = 0,
                grantEnvelope = null,
                errorCode = null,
                errorMessage = err.message,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openJsonPost(requestTarget: String, payloadBytes: ByteArray): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + requestTarget)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            val timestampSeconds = (System.currentTimeMillis() / 1000L).toString()
            val signature = RequestSigning.signHmacSha256Hex(
                secret = requestSignatureSecret,
                method = "POST",
                requestTarget = requestTarget,
                timestampSeconds = timestampSeconds,
                bodyBytes = payloadBytes,
            )
            setRequestProperty("X-Registry-Timestamp", timestampSeconds)
            setRequestProperty("X-Registry-Signature", signature)
        }
    }

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

    private fun parseRegistryError(body: String?): RegistryErrorEnvelope? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            CanonicalJson.json.decodeFromString<RegistryErrorResponse>(body).error
        }.getOrNull()
    }

    private fun encodePathSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}

data class RegistryManagedRelayResult(
    val success: Boolean,
    val statusCode: Int,
    val grantEnvelope: RegistryClientGrantEnvelope?,
    val errorCode: String?,
    val errorMessage: String?,
)

@Serializable
private data class RegistryErrorResponse(
    val error: RegistryErrorEnvelope? = null,
)

@Serializable
private data class RegistryErrorEnvelope(
    val code: String? = null,
    val message: String? = null,
)
