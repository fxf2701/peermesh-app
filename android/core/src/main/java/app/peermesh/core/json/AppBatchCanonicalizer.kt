package app.peermesh.core.json

import app.peermesh.core.model.AppBatchEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

object AppBatchCanonicalizer {
    fun canonicalizeForMac(batch: AppBatchEnvelope): String {
        val jsonElement = CanonicalJson.json.encodeToJsonElement(AppBatchEnvelope.serializer(), batch)
        val obj = jsonElement as JsonObject
        val withoutSig = JsonObject(obj.filterKeys { it != "app_sig" })
        return CanonicalJson.canonicalize(withoutSig)
    }
}
