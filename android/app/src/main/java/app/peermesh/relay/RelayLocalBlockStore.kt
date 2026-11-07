package app.peermesh.relay

import android.content.Context
import app.peermesh.PeerMeshPrefs
import app.peermesh.core.json.CanonicalJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object RelayLocalBlockStore {
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_BLOCKED_RELAYS = "relay_blocked_relays_json"
    private const val DEFAULT_BLOCK_TTL_MS = 6L * 60L * 60L * 1000L

    @Serializable
    data class BlockedRelay(
        val relayUrl: String,
        val reason: String,
        val blockedAtMs: Long,
        val expiresAtMs: Long,
    )

    fun activeBlocks(context: Context, nowMs: Long = System.currentTimeMillis()): List<BlockedRelay> {
        val cleaned = loadAndClean(context, nowMs)
        return cleaned.values.sortedBy { it.relayUrl }
    }

    fun isBlocked(
        context: Context,
        relayUrl: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (relayUrl.isBlank()) return false
        return loadAndClean(context, nowMs).containsKey(relayUrl.trim())
    }

    fun block(
        context: Context,
        relayUrl: String,
        reason: String,
        ttlMs: Long = DEFAULT_BLOCK_TTL_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): BlockedRelay? {
        val normalizedRelayUrl = relayUrl.trim()
        val normalizedReason = reason.trim()
        if (normalizedRelayUrl.isEmpty() || normalizedReason.isEmpty()) return null
        val expiresAtMs = nowMs + ttlMs.coerceAtLeast(60_000L)
        val next = loadAndClean(context, nowMs).toMutableMap()
        val entry = BlockedRelay(
            relayUrl = normalizedRelayUrl,
            reason = normalizedReason,
            blockedAtMs = nowMs,
            expiresAtMs = expiresAtMs,
        )
        next[normalizedRelayUrl] = entry
        save(context, next.values.toList())
        return entry
    }

    fun clear(context: Context, relayUrl: String) {
        val normalizedRelayUrl = relayUrl.trim()
        if (normalizedRelayUrl.isEmpty()) return
        val next = loadAndClean(context, System.currentTimeMillis()).toMutableMap()
        if (next.remove(normalizedRelayUrl) != null) {
            save(context, next.values.toList())
        }
    }

    private fun loadAndClean(context: Context, nowMs: Long): Map<String, BlockedRelay> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BLOCKED_RELAYS, null).orEmpty()
        val parsed = runCatching {
            CanonicalJson.json.decodeFromString<List<BlockedRelay>>(raw)
        }.getOrDefault(emptyList())
        val active = parsed
            .filter { it.relayUrl.isNotBlank() && it.expiresAtMs > nowMs }
            .associateBy { it.relayUrl.trim() }
        val shouldRewrite = active.size != parsed.size || raw.isBlank()
        if (shouldRewrite) {
            save(context, active.values.toList())
        }
        return active
    }

    private fun save(context: Context, entries: List<BlockedRelay>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val payload = CanonicalJson.json.encodeToString(entries.sortedBy { it.relayUrl })
        prefs.edit().putString(KEY_BLOCKED_RELAYS, payload).apply()
    }
}
