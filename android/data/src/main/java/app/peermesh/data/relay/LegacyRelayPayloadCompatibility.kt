package app.peermesh.data.relay

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventEnvelope
import kotlinx.serialization.json.JsonElement

internal object LegacyRelayPayloadCompatibility {
    fun decodeLegacyEnvelope(
        body: JsonElement,
        allowPlaintextBody: Boolean,
    ): EventEnvelope? {
        if (!allowPlaintextBody) return null
        return runCatching {
            CanonicalJson.json.decodeFromJsonElement(EventEnvelope.serializer(), body)
        }.getOrNull()
    }
}
