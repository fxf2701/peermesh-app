package app.peermesh.core.transport

data class EnvelopeReceiveResult(
    val accepted: Boolean,
    val relayEligible: Boolean = false,
    val relayKey: String? = null,
    val relaySummary: String? = null,
    val relayOriginDeviceIds: Set<String> = emptySet(),
    val actorDid: String? = null,
    val decoded: Boolean = accepted,
    val opaqueForwardAllowed: Boolean = !decoded,
    val retryable: Boolean = false,
)

interface EnvelopeReceiver {
    fun onEnvelopeReceived(blob: ByteArray): Boolean

    fun onEnvelopeReceivedWithMeta(blob: ByteArray): EnvelopeReceiveResult {
        return EnvelopeReceiveResult(accepted = onEnvelopeReceived(blob))
    }

    fun onDtnDataReceived(blob: ByteArray): EnvelopeReceiveResult {
        return onEnvelopeReceivedWithMeta(blob)
    }
}
