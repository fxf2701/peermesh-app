package app.peermesh.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.peermesh.PeerMeshPrefs
import app.peermesh.data.debug.ActivityLog
import app.peermesh.transport.adapter.NearbyForegroundDiagnosticLabels
import java.util.Locale

class ActivityLogBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!ActivityLog.isEnabled()) return
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVITY_LOG_AUTO_AFTER_REBOOT, false)) return
        prefs.edit().putBoolean(KEY_ACTIVITY_LOG_AUTO_AFTER_REBOOT, false).apply()
        val filePrefix = prefs.getString(KEY_ACTIVITY_LOG_PREFIX, "").orEmpty()
        val durationMs = parseDurationMs(prefs.getString(KEY_ACTIVITY_LOG_DURATION, "").orEmpty()) ?: return
        val patterns = parsePatterns(prefs.getString(KEY_ACTIVITY_LOG_PATTERNS, "").orEmpty())
        val labels = ensureDiscoveryLabels(
            parsePatterns(prefs.getString(KEY_ACTIVITY_LOG_ALLOWED_LABELS, "").orEmpty())
        )
        ActivityLogCaptureService.startCapture(
            context = context,
            durationMs = durationMs,
            patterns = patterns,
            labels = labels,
            filePrefix = filePrefix,
        )
    }

    private fun parseDurationMs(raw: String): Long? {
        val value = raw.trim().lowercase(Locale.US)
        if (value.isEmpty()) return null
        return when {
            value.endsWith("ms") -> value.removeSuffix("ms").trim().toLongOrNull()
            value.endsWith("s") -> value.removeSuffix("s").trim().toLongOrNull()?.times(1000L)
            else -> value.toLongOrNull()?.times(1000L)
        }
    }

    private fun parsePatterns(raw: String): List<String> {
        return raw.split("\n").mapNotNull { it.trim().ifEmpty { null } }
    }

    private fun ensureDiscoveryLabels(labels: List<String>): List<String> {
        return (labels + DISCOVERY_MARKER_LABELS + NearbyForegroundDiagnosticLabels.ALL).distinct()
    }

    companion object {
        private const val PREFS_NAME = PeerMeshPrefs.NAME
        private const val KEY_ACTIVITY_LOG_PREFIX = "activity_log_prefix"
        private const val KEY_ACTIVITY_LOG_DURATION = "activity_log_duration"
        private const val KEY_ACTIVITY_LOG_PATTERNS = "activity_log_patterns"
        private const val KEY_ACTIVITY_LOG_ALLOWED_LABELS = "activity_log_allowed_labels"
        private const val KEY_ACTIVITY_LOG_AUTO_AFTER_REBOOT = "activity_log_auto_after_reboot"
        private val DISCOVERY_MARKER_LABELS = listOf(
            "BLE scan callback",
            "BLE mfg match",
            "BLE mfg skip",
            "Nearby changed",
            "Nearby rooms discovery burst",
            "Nearby room self suppressed",
            "Nearby room advertise provider",
            "Nearby room advertisement state",
            "Nearby room advertisement refresh skipped",
            "Nearby room advertisement refresh deferred",
            "Nearby room advertisement refresh rejected",
            "Nearby room advertise payload",
            "Nearby room discovery payload",
            "Queued BLE targets",
            "BLE target selected",
            "BLE target pinned",
            "GATT connect failed",
            "GATT disconnect reason",
            "GATT connect backoff",
            "GATT connected",
            "GATT services discovered",
            "GATT server started",
            "BLE advertise started",
            "App foreground",
            "App background",
        )
    }
}
