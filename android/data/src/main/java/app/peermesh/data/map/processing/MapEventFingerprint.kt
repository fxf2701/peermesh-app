package app.peermesh.data.map.processing

import app.peermesh.core.model.EventCore
import app.peermesh.core.json.CanonicalJson
import java.security.MessageDigest
import kotlinx.serialization.json.encodeToJsonElement

/** Deterministic cryptographic fingerprint over canonical serialized wire-event content. */
internal fun interface MapEventFingerprint {
    fun sha256(event: EventCore): String
}

/**
 * M2C2A implementation anchor.
 *
 * Codex must implement this from the repository's canonical EventCore serialization contract.
 * Do not use object hashCode(), toString(), Room insertion order, or adjusted ingest values.
 */
internal object Sha256MapEventFingerprint : MapEventFingerprint {
    override fun sha256(event: EventCore): String {
        val element = CanonicalJson.json.encodeToJsonElement(EventCore.serializer(), event)
        val bytes = CanonicalJson.canonicalize(element).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
