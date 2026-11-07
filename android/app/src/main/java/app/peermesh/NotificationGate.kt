package app.peermesh

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationGate {
    enum class Reason {
        PERMISSION_MISSING,
        OS_DISABLED,
        CHANNEL_DISABLED,
        NONE,
    }

    data class Status(
        val enabled: Boolean,
        val needsPermission: Boolean,
        val channelDisabled: Boolean,
        val reason: Reason,
    )

    fun status(context: Context, channelId: String? = null): Status {
        val needsPermission = needsRuntimePermission(context)
        val appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val channelDisabled = isChannelDisabled(context, channelId)
        val enabled = appEnabled && !needsPermission && !channelDisabled
        val reason = when {
            needsPermission -> Reason.PERMISSION_MISSING
            !appEnabled -> Reason.OS_DISABLED
            channelDisabled -> Reason.CHANNEL_DISABLED
            else -> Reason.NONE
        }
        return Status(enabled, needsPermission, channelDisabled, reason)
    }

    fun needsRuntimePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            requestCode,
        )
    }

    fun openAppNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
        }
        context.startActivity(fallback)
    }

    fun openChannelSettings(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            openAppNotificationSettings(context)
            return
        }
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            openAppNotificationSettings(context)
        }
    }

    private fun isChannelDisabled(context: Context, channelId: String?): Boolean {
        if (channelId.isNullOrBlank()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(channelId) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }
}
