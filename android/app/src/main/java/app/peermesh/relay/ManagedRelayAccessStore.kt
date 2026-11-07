package app.peermesh.relay

import android.content.Context
import app.peermesh.PeerMeshPrefs
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.relay.ClientAccessGrant
import app.peermesh.core.relay.ClientRelaySession
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object ManagedRelayAccessStore {
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_BINDING = "managed_relay_access_binding_json"
    private const val KEY_LAST_BOOTSTRAP_ERROR = "managed_relay_last_bootstrap_error_json"
    private const val KEY_LAST_SESSION_OPEN_ERROR = "managed_relay_last_session_open_error_json"

    @Serializable
    private data class StoredBinding(
        val relayUrl: String,
        val relayId: String,
        val grant: ClientAccessGrant,
        val session: ClientRelaySession,
        val updatedAtMs: Long,
    )

    @Serializable
    data class StoredBootstrapError(
        val code: String,
        val message: String,
        val clientInstanceId: String,
        val gidScope: List<String>,
        val recordedAtMs: Long,
    )

    @Serializable
    data class StoredSessionOpenError(
        val code: String,
        val message: String,
        val relayUrl: String,
        val relayId: String,
        val grantId: String,
        val clientInstanceId: String,
        val gidScope: List<String>,
        val recordedAtMs: Long,
    )

    fun load(context: Context): ManagedRelayAccessBinding? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BINDING, null).orEmpty()
        val stored = runCatching {
            CanonicalJson.json.decodeFromString<StoredBinding>(raw)
        }.getOrNull() ?: return null
        if (stored.relayUrl.isBlank() || stored.relayId.isBlank()) return null
        return ManagedRelayAccessBinding(
            relayUrl = stored.relayUrl,
            relayId = stored.relayId,
            grant = stored.grant,
            session = stored.session,
        )
    }

    fun save(context: Context, binding: ManagedRelayAccessBinding) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = StoredBinding(
            relayUrl = binding.relayUrl,
            relayId = binding.relayId,
            grant = binding.grant,
            session = binding.session,
            updatedAtMs = System.currentTimeMillis(),
        )
        prefs.edit()
            .putString(KEY_BINDING, CanonicalJson.json.encodeToString(stored))
            .remove(KEY_LAST_BOOTSTRAP_ERROR)
            .remove(KEY_LAST_SESSION_OPEN_ERROR)
            .apply()
    }

    fun clear(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_BINDING).apply()
    }

    fun saveBootstrapError(
        context: Context,
        code: String,
        message: String,
        clientInstanceId: String,
        gidScope: List<String>,
        recordedAtMs: Long = System.currentTimeMillis(),
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = StoredBootstrapError(
            code = code,
            message = message,
            clientInstanceId = clientInstanceId,
            gidScope = gidScope,
            recordedAtMs = recordedAtMs,
        )
        prefs.edit()
            .putString(KEY_LAST_BOOTSTRAP_ERROR, CanonicalJson.json.encodeToString(stored))
            .apply()
    }

    fun clearBootstrapError(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_BOOTSTRAP_ERROR).apply()
    }

    fun saveSessionOpenError(
        context: Context,
        code: String,
        message: String,
        relayUrl: String,
        relayId: String,
        grantId: String,
        clientInstanceId: String,
        gidScope: List<String>,
        recordedAtMs: Long = System.currentTimeMillis(),
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = StoredSessionOpenError(
            code = code,
            message = message,
            relayUrl = relayUrl,
            relayId = relayId,
            grantId = grantId,
            clientInstanceId = clientInstanceId,
            gidScope = gidScope,
            recordedAtMs = recordedAtMs,
        )
        prefs.edit()
            .putString(KEY_LAST_SESSION_OPEN_ERROR, CanonicalJson.json.encodeToString(stored))
            .apply()
    }

    fun clearSessionOpenError(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_SESSION_OPEN_ERROR).apply()
    }
}
