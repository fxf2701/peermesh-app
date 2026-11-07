package app.peermesh.transport.ble.dtn

private const val DIAGNOSTIC_ID_SAMPLE_LIMIT = 5

class DtnControlRouter(
    private val engine: DtnSyncEngine,
    private val diagnostics: DtnDiagnosticsSink? = null,
) {
    fun route(
        blob: ByteArray,
        isDeferred: (ByteArray) -> Boolean = { false },
        sendResponse: (ByteArray) -> Boolean,
    ): DtnControlRouteResult {
        val packet = DtnControlCodec.decode(blob) ?: return DtnControlRouteResult.NotDtn
        diagnostics?.log(packet.toDiagnosticEvent(direction = "recv"))
        val responses = try {
            engine.onControlPacket(packet)
        } catch (failure: Throwable) {
            diagnostics?.log(
                DtnDiagnosticEvent(
                    label = "DTN control handle failed",
                    type = packet.type,
                    direction = "failed",
                    peerId = packet.peerId,
                    gid = packet.gid,
                    sessionId = packet.sessionId,
                    idCount = packet.ids.size,
                    dataCount = packet.data.size,
                    idSample = packet.ids.take(DIAGNOSTIC_ID_SAMPLE_LIMIT),
                    reason = "exception=${failure.javaClass.name} message=${failure.message ?: "none"}",
                )
            )
            throw failure
        }
        var sent = 0
        responses.forEach { response ->
            if (sendResponse(DtnControlCodec.encode(response))) {
                sent += 1
                diagnostics?.log(response.toDiagnosticEvent(direction = "sent"))
            } else if (isDeferred(DtnControlCodec.encode(response))) {
                diagnostics?.log(response.toDiagnosticEvent(direction = "deferred", reason = "nearby_foreground retry_consumed=false"))
            } else {
                diagnostics?.log(response.toDiagnosticEvent(direction = "send_failed", reason = "enqueue_failed"))
            }
        }
        diagnostics?.log(
            DtnDiagnosticEvent(
                label = "DTN sync summary",
                type = packet.type,
                direction = "route",
                peerId = packet.peerId,
                gid = packet.gid,
                sessionId = packet.sessionId,
                responseCount = responses.size,
                sentCount = sent,
            )
        )
        return DtnControlRouteResult.Handled(
            packetType = packet.type,
            responseCount = responses.size,
            sentCount = sent,
        )
    }

    private fun DtnControlPacket.toDiagnosticEvent(direction: String, reason: String? = null): DtnDiagnosticEvent {
        val diagnosticType = if (type == DtnControlTypes.DATA && carrierData.isNotEmpty()) {
            "carrier_data"
        } else {
            type
        }
        return DtnDiagnosticEvent(
            label = "DTN $diagnosticType $direction",
            type = diagnosticType,
            direction = direction,
            peerId = peerId,
            gid = gid,
            sessionId = sessionId,
            idCount = ids.size,
            dataCount = data.size,
            idSample = ids.take(DIAGNOSTIC_ID_SAMPLE_LIMIT),
            dataIdSample = data.map { it.eventId }.take(DIAGNOSTIC_ID_SAMPLE_LIMIT),
            reason = reason,
        )
    }
}

sealed class DtnControlRouteResult {
    data object NotDtn : DtnControlRouteResult()
    data class Handled(
        val packetType: String,
        val responseCount: Int,
        val sentCount: Int,
    ) : DtnControlRouteResult()
}
