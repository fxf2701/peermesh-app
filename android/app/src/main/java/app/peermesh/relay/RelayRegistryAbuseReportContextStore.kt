package app.peermesh.relay

import android.content.Context
import app.peermesh.PeerMeshPrefs
import app.peermesh.core.json.CanonicalJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object RelayRegistryAbuseReportContextStore {
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_CONTEXTS = "relay_registry_abuse_report_contexts_json"

    @Serializable
    data class AbuseReportContext(
        val relayUrl: String,
        val registryBaseUrl: String,
        val relayId: String,
        val grantId: String,
        val clientInstanceId: String,
        val recordedAtMs: Long,
    )

    fun get(context: Context, relayUrl: String): AbuseReportContext? {
        val normalizedRelayUrl = relayUrl.trim()
        if (normalizedRelayUrl.isEmpty()) return null
        return loadAll(context)[normalizedRelayUrl]
    }

    fun put(context: Context, entry: AbuseReportContext) {
        val normalizedRelayUrl = entry.relayUrl.trim()
        if (normalizedRelayUrl.isEmpty()) return
        val next = loadAll(context).toMutableMap()
        next[normalizedRelayUrl] = entry.copy(relayUrl = normalizedRelayUrl)
        save(context, next.values.toList())
    }

    fun clear(context: Context, relayUrl: String) {
        val normalizedRelayUrl = relayUrl.trim()
        if (normalizedRelayUrl.isEmpty()) return
        val next = loadAll(context).toMutableMap()
        if (next.remove(normalizedRelayUrl) != null) {
            save(context, next.values.toList())
        }
    }

    private fun loadAll(context: Context): Map<String, AbuseReportContext> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONTEXTS, null).orEmpty()
        val parsed = runCatching {
            CanonicalJson.json.decodeFromString<List<AbuseReportContext>>(raw)
        }.getOrDefault(emptyList())
        return parsed
            .filter { it.relayUrl.isNotBlank() && it.grantId.isNotBlank() && it.clientInstanceId.isNotBlank() }
            .associateBy { it.relayUrl.trim() }
    }

    private fun save(context: Context, entries: List<AbuseReportContext>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val payload = CanonicalJson.json.encodeToString(entries.sortedBy { it.relayUrl })
        prefs.edit().putString(KEY_CONTEXTS, payload).apply()
    }
}
