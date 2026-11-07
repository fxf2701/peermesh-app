package app.peermesh.core.json

import app.peermesh.core.model.EventEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

object EnvelopeCanonicalizer {
    fun canonicalizeForMac(envelope: EventEnvelope): String {
        val jsonElement = CanonicalJson.json.encodeToJsonElement(EventEnvelope.serializer(), envelope)
        val obj = jsonElement as JsonObject
        val withoutSig = JsonObject(obj.filterKeys { it != "sig" })
        return CanonicalJson.canonicalize(withoutSig)
    }
}
