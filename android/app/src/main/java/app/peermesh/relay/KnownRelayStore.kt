package app.peermesh.relay

import android.content.Context
import app.peermesh.PeerMeshPrefs
import app.peermesh.core.json.CanonicalJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object KnownRelayStore {
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_KNOWN_RELAYS = "known_relays_json"
    private const val KNOWN_RELAY_TTL_MS = 30L * 24L * 60L * 60L * 1000L

    @Serializable
    private data class StoredKnownRelay(
        val relayUrl: String,
        val lastSuccessAtMs: Long,
    )

    fun activeRelayUrls(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> {
        return loadAndClean(context, nowMs)
            .sortedByDescending { it.lastSuccessAtMs }
            .map { it.relayUrl }
    }

    fun recordSuccess(
        context: Context,
        relayUrl: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val normalizedRelayUrl = relayUrl.trim()
        if (normalizedRelayUrl.isEmpty()) return
        val next = loadAndClean(context, nowMs)
            .associateByTo(mutableMapOf()) { it.relayUrl }
        next[normalizedRelayUrl] = StoredKnownRelay(
            relayUrl = normalizedRelayUrl,
            lastSuccessAtMs = nowMs,
        )
        save(context, next.values.toList())
    }

    private fun loadAndClean(
        context: Context,
        nowMs: Long,
    ): List<StoredKnownRelay> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_KNOWN_RELAYS, null).orEmpty()
        val parsed = runCatching {
            CanonicalJson.json.decodeFromString<List<StoredKnownRelay>>(raw)
        }.getOrDefault(emptyList())
        val active = parsed
            .filter { it.relayUrl.isNotBlank() && nowMs - it.lastSuccessAtMs <= KNOWN_RELAY_TTL_MS }
            .distinctBy { it.relayUrl.trim() }
        if (active.size != parsed.size || raw.isBlank()) {
            save(context, active)
        }
        return active
    }

    private fun save(context: Context, entries: List<StoredKnownRelay>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val payload = CanonicalJson.json.encodeToString(entries.sortedByDescending { it.lastSuccessAtMs })
        prefs.edit().putString(KEY_KNOWN_RELAYS, payload).apply()
    }
}
