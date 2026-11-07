package app.peermesh.data.relay

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.transport.EnvelopeReceiver
import app.peermesh.transport.relay.RelayHttpClient
import app.peermesh.data.ingest.EventSources
import app.peermesh.data.ingest.EnvelopeReceiverImpl
import kotlinx.serialization.encodeToString
import app.peermesh.data.debug.ActivityLog

class RelayPullManager(
    private val relayUrl: String,
    private val relayClient: RelayHttpClient,
    private val receiver: EnvelopeReceiver,
    private val stateStore: RelayPullStateStore = InMemoryRelayPullStateStore(),
    private val allowLegacyRelayPlaintext: Boolean = false,
) {
    fun pullByCursor(
        gid: String,
        limit: Int = 200,
    ): RelayPullReport {
        val startMs = System.currentTimeMillis()
        if (relayUrl.isBlank() || gid.isBlank()) return RelayPullReport.empty()

        val cursor = stateStore.load(relayUrl, gid)?.cursor
        ActivityLog.add(
            label = "Relay pull",
            source = "relay",
            message = "relay=$relayUrl gid=$gid cursor=${cursor ?: "none"}",
        )
        val result = relayClient.fetch(gid, cursor, limit)
        if (!result.success) {
            val elapsedMs = System.currentTimeMillis() - startMs
            ActivityLog.add(
                label = "Relay pull error",
                source = "relay",
                message = "relay=$relayUrl gid=$gid error=${result.errorMessage ?: "unknown"} elapsedMs=$elapsedMs",
            )
            return RelayPullReport(
                received = 0,
                accepted = 0,
                success = false,
                statusCode = result.statusCode,
                errorMessage = result.errorMessage,
                errorCode = result.errorCode,
            )
        }

        val accepted = receive(result.events)

        ActivityLog.add(
            label = "Relay pull report",
            source = "relay",
            message = "relay=$relayUrl gid=$gid received=${result.events.size} accepted=$accepted elapsedMs=${System.currentTimeMillis() - startMs}",
        )

        val nextCursor = result.nextCursor
        if (!nextCursor.isNullOrBlank()) {
            stateStore.save(relayUrl, gid, RelayPullState(nextCursor))
        }

        return RelayPullReport(
            received = result.events.size,
            accepted = accepted,
            success = true,
            statusCode = 200,
            errorMessage = null,
            errorCode = null,
        )
    }

    fun repairByEventIds(gid: String, eventIds: List<String>): RelayPullReport {
        if (relayUrl.isBlank() || gid.isBlank() || eventIds.isEmpty()) return RelayPullReport.empty()
        val result = relayClient.fetchByEventIds(gid, eventIds)
        if (!result.success) {
            return RelayPullReport(
                received = 0,
                accepted = 0,
                success = false,
                statusCode = result.statusCode,
                errorMessage = result.errorMessage,
                errorCode = result.errorCode,
            )
        }
        val accepted = receive(result.events)
        ActivityLog.add(
            label = "Relay map dependency repair",
            source = "relay",
            message = "relay=$relayUrl gid=$gid requested=${eventIds.size} received=${result.events.size} accepted=$accepted",
        )
        return RelayPullReport(
            received = result.events.size,
            accepted = accepted,
            success = true,
            statusCode = result.statusCode,
            errorMessage = null,
            errorCode = null,
        )
    }

    private fun receive(events: List<app.peermesh.core.relay.RelayEvent>): Int {
        var accepted = 0
        events.forEach { event ->
            val ok = if (receiver is EnvelopeReceiverImpl) {
                receiver.onRelayEventReceived(event)
            } else {
                val envelope = decodeEnvelope(event.body) ?: return@forEach
                val bytes = CanonicalJson.json.encodeToString(EventEnvelope.serializer(), envelope)
                    .toByteArray(Charsets.UTF_8)
                receiver.onEnvelopeReceived(bytes)
            }
            if (ok) accepted += 1
        }
        return accepted
    }

    private fun decodeEnvelope(body: kotlinx.serialization.json.JsonElement): EventEnvelope? {
        return LegacyRelayPayloadCompatibility.decodeLegacyEnvelope(
            body = body,
            allowPlaintextBody = allowLegacyRelayPlaintext,
        )
    }
}

data class RelayPullReport(
    val received: Int,
    val accepted: Int,
    val success: Boolean,
    val statusCode: Int,
    val errorMessage: String?,
    val errorCode: String?,
) {
    companion object {
        fun empty() = RelayPullReport(
            received = 0,
            accepted = 0,
            success = true,
            statusCode = 0,
            errorMessage = null,
            errorCode = null,
        )
    }
}
