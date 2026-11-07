package app.peermesh.beta

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import app.peermesh.BuildConfig
import app.peermesh.PeerMeshPrefs
import app.peermesh.R

object BetaProgramUpdatePromptController {
    private const val KEY_LAST_PROMPT_AT_MS = "beta_program_last_prompt_at_ms"
    private const val KEY_LAST_PROMPT_VERSION = "beta_program_last_prompt_version"
    private const val OPTIONAL_PROMPT_INTERVAL_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var activeDialog: AlertDialog? = null

    fun maybeShowPrompt(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activeDialog?.isShowing == true) return
        val config = PeerMeshBetaProgram.currentConfig() ?: return
        val installedVersion = BuildConfig.VERSION_NAME
        val minimumSupportedVersion = config.minimumSupportedVersion?.trim().orEmpty()
        val latestVersion = config.latestVersion?.trim().orEmpty()
        val required = minimumSupportedVersion.isNotBlank() &&
            AppVersionComparator.compare(installedVersion, minimumSupportedVersion) < 0
        val optional = !required &&
            latestVersion.isNotBlank() &&
            AppVersionComparator.compare(installedVersion, latestVersion) < 0
        if (!required && !optional) return
        if (optional && shouldThrottleOptionalPrompt(activity, latestVersion)) return

        val targetVersion = if (required) minimumSupportedVersion else latestVersion
        val titleRes = if (required) R.string.beta_update_required_title else R.string.beta_update_optional_title
        val message = activity.getString(
            if (required) R.string.beta_update_required_body else R.string.beta_update_optional_body,
            targetVersion,
            formatChangelog(activity, config),
        )
        activeDialog = AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setMessage(message)
            .setCancelable(!required)
            .setPositiveButton(R.string.beta_update_cta) { _, _ ->
                rememberPrompt(activity, latestVersion.ifBlank { minimumSupportedVersion })
                openGooglePlay(activity)
            }
            .apply {
                if (!required) {
                    setNegativeButton(R.string.beta_update_not_now) { _, _ ->
                        rememberPrompt(activity, latestVersion)
                    }
                }
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { activeDialog = null }
                dialog.show()
            }
    }

    private fun shouldThrottleOptionalPrompt(context: Context, latestVersion: String): Boolean {
        val prefs = prefs(context)
        val lastPromptAt = prefs.getLong(KEY_LAST_PROMPT_AT_MS, 0L)
        val lastPromptVersion = prefs.getString(KEY_LAST_PROMPT_VERSION, null)
        if (lastPromptVersion != latestVersion) return false
        return System.currentTimeMillis() - lastPromptAt < OPTIONAL_PROMPT_INTERVAL_MS
    }

    private fun rememberPrompt(context: Context, version: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_PROMPT_AT_MS, System.currentTimeMillis())
            .putString(KEY_LAST_PROMPT_VERSION, version)
            .apply()
    }

    private fun formatChangelog(context: Context, config: BetaProgramConfig): String {
        if (config.changelog.isEmpty()) {
            return context.getString(R.string.beta_whats_new_empty)
        }
        return config.changelog.joinToString(separator = "\n") { "\u2022 $it" }
    }

    private fun openGooglePlay(activity: Activity) {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${activity.packageName}"))
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}"),
        )
        runCatching { activity.startActivity(marketIntent) }
            .recoverCatching { activity.startActivity(webIntent) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PeerMeshPrefs.NAME, Context.MODE_PRIVATE)
}
