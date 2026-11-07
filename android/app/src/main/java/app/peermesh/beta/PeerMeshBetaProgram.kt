package app.peermesh.beta

import android.content.Context
import app.peermesh.PeerMeshPrefs
import app.peermesh.config.EnvironmentLoader
import app.peermesh.core.json.CanonicalJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

object PeerMeshBetaProgram {
    private const val KEY_CACHED_CONFIG_JSON = "beta_program_config_json"
    private const val KEY_LAST_FETCH_AT_MS = "beta_program_last_fetch_at_ms"
    private const val REFRESH_INTERVAL_MS = 4 * 60 * 60 * 1000L
    private const val BETA_PROGRAM_PATH = "/v1/registry/client/beta-program"

    @Volatile
    private var cachedConfig: BetaProgramConfig? = null

    private val listeners = CopyOnWriteArrayList<(BetaProgramConfig?) -> Unit>()

    fun initialize(context: Context) {
        cachedConfig = loadCached(context.applicationContext)
    }

    fun currentConfig(): BetaProgramConfig? = cachedConfig

    fun isEnabled(): Boolean = cachedConfig?.enabled == true

    fun addListener(listener: (BetaProgramConfig?) -> Unit) {
        listeners.addIfAbsent(listener)
        listener(cachedConfig)
    }

    fun removeListener(listener: (BetaProgramConfig?) -> Unit) {
        listeners.remove(listener)
    }

    fun refreshAsyncIfStale(
        context: Context,
        minIntervalMs: Long = REFRESH_INTERVAL_MS,
    ) {
        val appContext = context.applicationContext
        val prefs = prefs(appContext)
        val lastFetchAt = prefs.getLong(KEY_LAST_FETCH_AT_MS, 0L)
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastFetchAt < minIntervalMs) return
        prefs.edit().putLong(KEY_LAST_FETCH_AT_MS, nowMs).apply()
        Thread {
            refresh(appContext)
        }.start()
    }

    fun refresh(context: Context): RefreshResult {
        val appContext = context.applicationContext
        val endpointBaseUrl = EnvironmentLoader.load().registryBaseUrl.trim().trimEnd('/')
        if (endpointBaseUrl.isBlank()) {
            return RefreshResult(
                config = cachedConfig,
                usedCachedFallback = cachedConfig != null,
                endpointConfigured = false,
                errorCode = "registry_base_url_missing",
            )
        }

        val url = URL(endpointBaseUrl + BETA_PROGRAM_PATH)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
        }

        return try {
            val status = connection.responseCode
            val responseText = readResponseText(connection, status)
            if (status !in 200..299) {
                RefreshResult(
                    config = cachedConfig,
                    usedCachedFallback = cachedConfig != null,
                    endpointConfigured = true,
                    errorCode = "beta_program_http_$status",
                )
            } else {
                val config = parseRemoteConfig(responseText) ?: BetaProgramConfig()
                persist(appContext, config)
                RefreshResult(
                    config = config,
                    usedCachedFallback = false,
                    endpointConfigured = true,
                )
            }
        } catch (_: Exception) {
            RefreshResult(
                config = cachedConfig,
                usedCachedFallback = cachedConfig != null,
                endpointConfigured = true,
                errorCode = "beta_program_fetch_failed",
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseRemoteConfig(responseText: String?): BetaProgramConfig? {
        if (responseText.isNullOrBlank()) return null
        val envelope = runCatching {
            CanonicalJson.json.decodeFromString<RegistryBetaProgramEnvelope>(responseText)
        }.getOrNull()
        return envelope?.resolvedConfig()
    }

    private fun persist(context: Context, config: BetaProgramConfig) {
        prefs(context).edit()
            .putString(KEY_CACHED_CONFIG_JSON, CanonicalJson.json.encodeToString(config))
            .apply()
        publish(config)
    }

    private fun publish(config: BetaProgramConfig?) {
        val previous = cachedConfig
        cachedConfig = config
        if (previous == config) return
        listeners.forEach { listener -> listener(config) }
    }

    private fun loadCached(context: Context): BetaProgramConfig? {
        val raw = prefs(context).getString(KEY_CACHED_CONFIG_JSON, null)
        if (raw.isNullOrBlank()) return null
        return runCatching {
            CanonicalJson.json.decodeFromString<BetaProgramConfig>(raw)
        }.getOrNull()
    }

    private fun readResponseText(connection: HttpURLConnection, status: Int): String? {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { BufferedReader(InputStreamReader(it)).readText() }?.ifBlank { null }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PeerMeshPrefs.NAME, Context.MODE_PRIVATE)

    data class RefreshResult(
        val config: BetaProgramConfig?,
        val usedCachedFallback: Boolean,
        val endpointConfigured: Boolean,
        val errorCode: String? = null,
    )
}
