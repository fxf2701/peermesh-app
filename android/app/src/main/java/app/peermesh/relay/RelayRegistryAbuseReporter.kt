package app.peermesh.relay

import android.content.Context
import app.peermesh.DeviceIdProvider
import app.peermesh.config.EnvironmentLoader
import app.peermesh.data.debug.ActivityLog

object RelayRegistryAbuseReporter {
    fun submitBestEffort(
        context: Context,
        relayUrl: String,
        blockReason: String,
        errorCode: String?,
        counters: Map<String, Int> = emptyMap(),
        transport: RelayRegistryAbuseReportTransport = RelayRegistryAbuseReportClient(),
        nowMs: Long = System.currentTimeMillis(),
    ): RelayRegistryAbuseReportResult? {
        val reportReason = RelayLocalBlockPolicy.registryReportReasonFor(blockReason) ?: return null
        val reportContext = RelayRegistryAbuseReportContextStore.get(context, relayUrl)
        if (reportContext == null) {
            ActivityLog.add(
                label = "Relay abuse report skipped",
                source = "relay",
                message = "relay=$relayUrl reason=$blockReason code=${errorCode ?: "none"} skip=no_context",
            )
            return null
        }
        val registryBaseUrl = reportContext.registryBaseUrl.ifBlank {
            EnvironmentLoader.load().registryBaseUrl
        }.trim()
        if (registryBaseUrl.isBlank()) {
            ActivityLog.add(
                label = "Relay abuse report skipped",
                source = "relay",
                message = "relay=$relayUrl reason=$blockReason code=${errorCode ?: "none"} skip=no_registry_url",
            )
            return null
        }
        val request = RelayRegistryAbuseReportRequest(
            grant_id = reportContext.grantId,
            relay_id = reportContext.relayId,
            client_instance_id = reportContext.clientInstanceId.ifBlank {
                DeviceIdProvider.get(context)
            },
            reason = reportReason,
            timestamp = nowMs / 1000L,
            counters = counters,
        )
        val result = transport.submit(
            registryBaseUrl = registryBaseUrl,
            request = request,
            requestSignatureSecret = EnvironmentLoader.load().registryRequestSignatureSecret,
        )
        ActivityLog.add(
            label = if (result.success) "Relay abuse report sent" else "Relay abuse report failed",
            source = "relay",
            message = "relay=$relayUrl reason=$reportReason code=${errorCode ?: "none"} status=${result.statusCode} error=${result.errorCode ?: "none"}",
        )
        return result
    }
}
