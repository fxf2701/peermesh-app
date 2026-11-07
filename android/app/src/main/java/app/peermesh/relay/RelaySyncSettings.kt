package app.peermesh.relay

import android.content.Context
import app.peermesh.PeerMeshPrefs

enum class SyncMode {
    NORMAL,
    BATTERY_SAVER,
}

object RelaySyncSettings {
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_SYNC_MODE = "relay_sync_mode"
    private const val KEY_SYNC_ENABLED = "relay_sync_enabled"
    private const val KEY_CLOUD_TO_BLE_BRIDGE_ENABLED = "cloud_to_ble_bridge_enabled"
    private const val KEY_LAST_SUCCESS_MS = "relay_last_success_ms"
    private const val KEY_LAST_ATTEMPT_MS = "relay_last_attempt_ms"
    private const val KEY_LAST_PUSH_OK = "relay_last_push_ok"
    private const val KEY_LAST_PULL_OK = "relay_last_pull_ok"
    private const val KEY_LAST_OUTCOME_MS = "relay_last_outcome_ms"
    private const val KEY_SEED_RELAYS = "relay_seed_list"
    private const val KEY_SELECTED_RELAYS = "relay_selected_list"
    private const val KEY_DEV_MODE = "relay_dev_mode"
    private const val KEY_AUTOMATIC_CLOUD_SYNC_MIGRATED = "automatic_cloud_sync_migrated"

    fun load(context: Context): SyncMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_SYNC_MODE, SyncMode.NORMAL.name) ?: SyncMode.NORMAL.name
        return runCatching { SyncMode.valueOf(value) }.getOrDefault(SyncMode.NORMAL)
    }

    fun save(context: Context, mode: SyncMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SYNC_MODE, mode.name).apply()
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SYNC_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
    }

    fun migrateLegacyHeaderControlToAutomatic(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTOMATIC_CLOUD_SYNC_MIGRATED, false)) return
        // Preserve an explicit choice already stored by the user.
        if (prefs.contains(KEY_SYNC_ENABLED)) {
            prefs.edit().putBoolean(KEY_AUTOMATIC_CLOUD_SYNC_MIGRATED, true).apply()
            return
        }
        prefs.edit()
            .putBoolean(KEY_SYNC_ENABLED, true)
            .putBoolean(KEY_AUTOMATIC_CLOUD_SYNC_MIGRATED, true)
            .apply()
    }

    fun isCloudToBleBridgeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CLOUD_TO_BLE_BRIDGE_ENABLED, false)
    }

    fun setCloudToBleBridgeEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CLOUD_TO_BLE_BRIDGE_ENABLED, enabled).apply()
    }

    fun markSyncAttempt(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_ATTEMPT_MS, System.currentTimeMillis()).apply()
    }

    fun markSyncSuccess(context: Context) {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS_MS, now)
            .putLong(KEY_LAST_ATTEMPT_MS, now)
            .apply()
    }

    fun markSyncOutcome(context: Context, pushOk: Boolean, pullOk: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_LAST_PUSH_OK, pushOk)
            .putBoolean(KEY_LAST_PULL_OK, pullOk)
            .putLong(KEY_LAST_OUTCOME_MS, System.currentTimeMillis())
            .apply()
    }

    fun lastOutcomeSummary(context: Context, maxAgeMs: Long): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ts = prefs.getLong(KEY_LAST_OUTCOME_MS, 0L)
        if (ts <= 0L) return null
        if (System.currentTimeMillis() - ts > maxAgeMs) return null
        val pushOk = prefs.getBoolean(KEY_LAST_PUSH_OK, false)
        val pullOk = prefs.getBoolean(KEY_LAST_PULL_OK, false)
        return "push ${if (pushOk) "ok" else "fail"} / pull ${if (pullOk) "ok" else "fail"}"
    }

    fun lastSuccessMs(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_SUCCESS_MS, 0L)
    }

    fun lastAttemptMs(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_ATTEMPT_MS, 0L)
    }

    fun loadSeedRelaysRaw(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SEED_RELAYS, "") ?: ""
    }

    fun saveSeedRelaysRaw(context: Context, raw: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SEED_RELAYS, raw).apply()
    }

    fun parseSeedRelays(raw: String): List<String> {
        return raw.split("\n", ",", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun loadSelectedRelays(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SELECTED_RELAYS, "") ?: ""
        return parseSeedRelays(raw)
    }

    fun saveSelectedRelays(context: Context, relays: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_RELAYS, relays.joinToString("\n")).apply()
    }

    fun isDeveloperMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEV_MODE, false)
    }

    fun setDeveloperMode(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEV_MODE, enabled).apply()
    }

    fun filterRelayBaseUrls(
        context: Context,
        relayUrls: List<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val normalized = relayUrls
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val schemeFiltered = if (isDeveloperMode(context)) {
            normalized
        } else {
            normalized.filter { it.startsWith("https://") }
        }
        return schemeFiltered.filterNot { RelayLocalBlockStore.isBlocked(context, it, nowMs) }
    }

    fun resolveRelayBaseUrls(context: Context, fallback: String): List<String> {
        val nowMs = System.currentTimeMillis()
        val seeds = parseSeedRelays(loadSeedRelaysRaw(context))
        val selected = loadSelectedRelays(context).filter { seeds.contains(it) }
        val result = if (selected.isNotEmpty()) selected else seeds
        val blockFiltered = filterRelayBaseUrls(context, result, nowMs)
        if (blockFiltered.isNotEmpty()) return blockFiltered
        return filterRelayBaseUrls(context, listOf(fallback), nowMs)
    }

    fun resolveRelayBaseUrl(context: Context, fallback: String): String {
        return resolveRelayBaseUrls(context, fallback).firstOrNull() ?: fallback
    }
}
