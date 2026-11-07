package app.peermesh

import android.content.Context
import app.peermesh.core.encoding.Base64Url
import java.security.SecureRandom

object DeviceIdProvider {
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_ACTIVE_ACTOR = "active_actor_id"
    private const val KEY_MAIN_ACTOR = "main_actor_id"

    fun get(context: Context): String {
        return getActiveActor(context)
    }

    fun getActiveActor(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val active = prefs.getString(KEY_ACTIVE_ACTOR, null)
        if (!active.isNullOrBlank()) return active
        val main = prefs.getString(KEY_MAIN_ACTOR, null)
        if (!main.isNullOrBlank()) {
            prefs.edit().putString(KEY_ACTIVE_ACTOR, main).apply()
            return main
        }
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            prefs.edit()
                .putString(KEY_MAIN_ACTOR, existing)
                .putString(KEY_ACTIVE_ACTOR, existing)
                .apply()
            return existing
        }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = Base64Url.encode(bytes)
        prefs.edit()
            .putString(KEY_DEVICE_ID, id)
            .putString(KEY_MAIN_ACTOR, id)
            .putString(KEY_ACTIVE_ACTOR, id)
            .apply()
        return id
    }

    fun setActiveActor(context: Context, actorId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_MAIN_ACTOR, null).isNullOrBlank()) {
            prefs.edit().putString(KEY_MAIN_ACTOR, actorId).apply()
        }
        prefs.edit().putString(KEY_ACTIVE_ACTOR, actorId).apply()
    }

    fun getMainActor(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MAIN_ACTOR, null)
    }
}
