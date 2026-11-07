package app.peermesh.relay

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.net.RequestSigning
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

interface RelayRegistryAbuseReportTransport {
    fun submit(
        registryBaseUrl: String,
        request: RelayRegistryAbuseReportRequest,
        requestSignatureSecret: String? = null,
    ): RelayRegistryAbuseReportResult
}

class RelayRegistryAbuseReportClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : RelayRegistryAbuseReportTransport {
    override fun submit(
        registryBaseUrl: String,
        request: RelayRegistryAbuseReportRequest,
        requestSignatureSecret: String?,
    ): RelayRegistryAbuseReportResult {
        val normalizedBaseUrl = registryBaseUrl.trim().trimEnd('/')
        if (normalizedBaseUrl.isEmpty()) {
            return RelayRegistryAbuseReportResult(
                success = false,
                statusCode = 0,
                errorCode = "registry_base_url_missing",
                errorMessage = "Registry base URL missing.",
            )
        }
        val url = URL("$normalizedBaseUrl/v1/registry/client/reports")
        val payload = CanonicalJson.json.encodeToString(request)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            applyRegistryRequestSignature(
                requestTarget = "/v1/registry/client/reports",
                payloadBytes = payloadBytes,
                requestSignatureSecret = requestSignatureSecret,
            )
        }
        return try {
            connection.outputStream.use { it.write(payloadBytes) }
            val status = connection.responseCode
            val body = readBody(connection)
            if (status in 200..299) {
                RelayRegistryAbuseReportResult(
                    success = true,
                    statusCode = status,
                    errorCode = null,
                    errorMessage = null,
                )
            } else {
                val error = parseRelayError(body)
                RelayRegistryAbuseReportResult(
                    success = false,
                    statusCode = status,
                    errorCode = error?.code,
                    errorMessage = error?.message ?: body,
                )
            }
        } catch (err: Exception) {
            RelayRegistryAbuseReportResult(
                success = false,
                statusCode = 0,
                errorCode = null,
                errorMessage = err.message,
            )
        } finally {
            connection.disconnect()
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

    private fun parseRelayError(body: String?): RelayErrorEnvelope? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            CanonicalJson.json.decodeFromString<RelayErrorResponse>(body).error
        }.getOrNull()
    }

    private fun HttpURLConnection.applyRegistryRequestSignature(
        requestTarget: String,
        payloadBytes: ByteArray,
        requestSignatureSecret: String?,
    ) {
        val secret = requestSignatureSecret?.trim().orEmpty()
        if (secret.isEmpty()) return
        val timestampSeconds = (System.currentTimeMillis() / 1000L).toString()
        val signature = RequestSigning.signHmacSha256Hex(
            secret = secret,
            method = "POST",
            requestTarget = requestTarget,
            timestampSeconds = timestampSeconds,
            bodyBytes = payloadBytes,
        )
        setRequestProperty("X-Registry-Timestamp", timestampSeconds)
        setRequestProperty("X-Registry-Signature", signature)
    }
}

@Serializable
data class RelayRegistryAbuseReportRequest(
    val grant_id: String,
    val relay_id: String,
    val client_instance_id: String,
    val reason: String,
    val timestamp: Long,
    val counters: Map<String, Int> = emptyMap(),
)

data class RelayRegistryAbuseReportResult(
    val success: Boolean,
    val statusCode: Int,
    val errorCode: String?,
    val errorMessage: String?,
)

@Serializable
private data class RelayErrorResponse(
    val error: RelayErrorEnvelope? = null,
)

@Serializable
private data class RelayErrorEnvelope(
    val code: String? = null,
    val message: String? = null,
)
