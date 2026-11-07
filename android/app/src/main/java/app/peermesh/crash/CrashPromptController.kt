package app.peermesh.crash

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import app.peermesh.CrashReportActivity
import app.peermesh.PeerMeshPrefs
import app.peermesh.R

object CrashPromptController {
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_PROMPT_ENABLED = "crash_prompt_enabled"
    private const val KEY_LAST_PROMPTED = "crash_last_prompted"
    private var activeDialog: AlertDialog? = null

    fun isPromptEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PROMPT_ENABLED, true)
    }

    fun setPromptEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PROMPT_ENABLED, enabled).apply()
    }

    fun maybeShowPrompt(activity: Activity) {
        if (!isPromptEnabled(activity)) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (activeDialog?.isShowing == true) return
        val latest = CrashStorage.latestReportFile(activity) ?: return
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_PROMPTED, "") == latest.name) return

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.crash_prompt_title)
            .setMessage(R.string.crash_prompt_body)
            .setNegativeButton(R.string.crash_prompt_not_now) { _, _ ->
                prefs.edit().putString(KEY_LAST_PROMPTED, latest.name).apply()
            }
            .setPositiveButton(R.string.crash_prompt_review) { _, _ ->
                prefs.edit().putString(KEY_LAST_PROMPTED, latest.name).apply()
                val intent = Intent(activity, CrashReportActivity::class.java).apply {
                    putExtra(CrashReportActivity.EXTRA_FILE_NAME, latest.name)
                }
                activity.startActivity(intent)
            }
            .create()
        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
            }
        }
        activeDialog = dialog
        dialog.show()
    }

    fun dismissPrompt() {
        activeDialog?.dismiss()
        activeDialog = null
    }
}
