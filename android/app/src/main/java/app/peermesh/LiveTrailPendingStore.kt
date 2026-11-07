package app.peermesh

import android.content.Context
import android.content.SharedPreferences

object LiveTrailPendingStore {
    private const val PREFS_NAME = "live_trail_pending_state"
    private const val KEY_HIDDEN = "hidden_ids"

    fun hide(context: Context, trailId: String) {
        val prefs = prefs(context)
        val current = prefs.getStringSet(KEY_HIDDEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.add(trailId)) {
            prefs.edit().putStringSet(KEY_HIDDEN, current).apply()
        }
    }

    fun isHidden(context: Context, trailId: String): Boolean {
        val prefs = prefs(context)
        val current = prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()
        return current.contains(trailId)
    }

    fun clearHidden(context: Context, trailId: String) {
        val prefs = prefs(context)
        val current = prefs.getStringSet(KEY_HIDDEN, emptySet())?.toMutableSet() ?: return
        if (current.remove(trailId)) {
            prefs.edit().putStringSet(KEY_HIDDEN, current).apply()
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
