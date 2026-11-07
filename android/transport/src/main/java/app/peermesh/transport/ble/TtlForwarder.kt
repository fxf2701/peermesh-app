package app.peermesh.transport.ble

import app.peermesh.core.transport.EnvelopeBroadcaster
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.json.CanonicalJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class TtlForwarder(
    private val broadcaster: EnvelopeBroadcaster,
) {
    fun forwardIfAllowed(blob: ByteArray, nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        val envelope = parseEnvelope(blob) ?: return false
        val dtn = envelope.dtn
        if (dtn != null) {
            val expiresAt = envelope.ts + dtn.ttl_seconds
            if (nowSeconds > expiresAt) return false
            if (dtn.hop_count >= dtn.max_hops) return false
            return broadcaster.broadcastEnvelope(blob)
        }

        val ttl = envelope.ttl ?: return false
        val nextTtl = ttl - 1
        if (nextTtl <= 0) return false
        val updated = updateTtl(blob, nextTtl)
        return broadcaster.broadcastEnvelope(updated)
    }

    private fun updateTtl(blob: ByteArray, ttl: Int): ByteArray {
        val envelope = parseEnvelope(blob) ?: return blob
        val updated = envelope.copy(ttl = ttl)
        val json = CanonicalJson.json.encodeToString(EventEnvelope.serializer(), updated)
        return json.toByteArray(Charsets.UTF_8)
    }

    private fun parseEnvelope(blob: ByteArray): EventEnvelope? {
        return try {
            val json = blob.toString(Charsets.UTF_8)
            CanonicalJson.json.decodeFromString(EventEnvelope.serializer(), json)
        } catch (_: Exception) {
            null
        }
    }
}
