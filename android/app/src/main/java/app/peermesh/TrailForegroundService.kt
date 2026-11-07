package app.peermesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class TrailForegroundService : Service() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var stopActionPending: Boolean = false
    private var stopActionRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PeerMeshCapabilities.canUseTrail) {
            TrailRecorder.disableForEntitlement(this)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        logNotificationState()
        if (intent?.action == ACTION_STOP_TRAIL) {
            handleStopAction()
            return START_NOT_STICKY
        }
        if (!TrailRecorder.isRecording() && !TrailRecorder.isAutoWatchEnabled(this) && !TrailRecorder.isSaving()) {
            stopSelf()
            return START_NOT_STICKY
        }
        TrailRecorder.onServiceStart(this)
        startUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        stopUpdates()
        stopActionRunnable?.let { handler.removeCallbacks(it) }
        stopActionRunnable = null
        stopActionPending = false
        super.onDestroy()
        TrailRecorder.onServiceStop(this)
    }

    override fun onBind(intent: Intent?) = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.trail_fg_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val saving = TrailRecorder.isSaving()
        val body = if (saving && !TrailRecorder.isRecording()) {
            getString(R.string.trail_fg_saving)
        } else if (!TrailRecorder.isRecording() && TrailRecorder.isAutoWatchEnabled(this)) {
            getString(R.string.trail_fg_ready)
        } else {
            val mode = if (TrailRecorder.getMode(this) == TrailRecordingMode.AUTOMATIC) {
                getString(R.string.trail_fg_mode_auto)
            } else {
                getString(R.string.trail_fg_mode_manual)
            }
            val elapsed = formatElapsed(TrailRecorder.currentElapsedSeconds())
            val distance = formatDistance(TrailRecorder.currentDistanceMeters())
            val points = TrailRecorder.currentPointCount()
            getString(R.string.trail_fg_body_format, mode, elapsed, distance, points)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_trail)
            .setContentTitle(getString(R.string.trail_fg_title))
            .setContentText(body)
            .setOngoing(true)
        if (TrailRecorder.isRecording() && !stopActionPending) {
            builder.addAction(0, getString(R.string.trail_stop), stopActionIntent())
        }
        return builder.build()
    }

    private fun startUpdates() {
        stopUpdates()
        val r = object : Runnable {
            override fun run() {
                if (!TrailRecorder.isRecording() && !TrailRecorder.isAutoWatchEnabled(this@TrailForegroundService) && !TrailRecorder.isSaving()) return
                if (!TrailRecorder.isLocationPermissionReady(this@TrailForegroundService)) {
                    TrailRecorder.handlePermissionMissing(this@TrailForegroundService)
                    stopSelf()
                    return
                }
                if (!TrailRecorder.isGpsEnabled(this@TrailForegroundService)) {
                    TrailRecorder.handleGpsDisabled(this@TrailForegroundService)
                }
                val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mgr.notify(NOTIF_ID, buildNotification())
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        updateRunnable = r
        handler.postDelayed(r, UPDATE_INTERVAL_MS)
    }

    private fun stopUpdates() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }

    private fun handleStopAction() {
        if (stopActionPending) return
        stopActionPending = true
        stopUpdates()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification())
        val delayedStop = Runnable {
            stopActionRunnable = null
            if (TrailRecorder.isRecording()) {
                TrailRecorder.stopManual(applicationContext)
            }
            stopActionPending = false
            if (!TrailRecorder.isRecording() && !TrailRecorder.isSaving() && !TrailRecorder.isAutoWatchEnabled(this)) {
                stopForegroundService()
            }
        }
        stopActionRunnable = delayedStop
        handler.postDelayed(delayedStop, STOP_ACTION_DELAY_MS)
    }

    private fun stopForegroundService() {
        stopUpdates()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun stopActionIntent(): PendingIntent {
        val intent = Intent(this, TrailForegroundService::class.java).apply {
            action = ACTION_STOP_TRAIL
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, STOP_REQUEST_CODE, intent, flags)
    }

    private fun logNotificationState() {
        val status = NotificationGate.status(this, CHANNEL_ID)
        when {
            status.enabled -> Log.i(TAG, "notif_enabled")
            status.needsPermission -> Log.w(TAG, "notif_disabled_runtime_perm_missing")
            status.channelDisabled -> Log.w(TAG, "notif_disabled_channel")
            else -> Log.w(TAG, "notif_disabled_os_level")
        }
    }

    private fun formatElapsed(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%dh %02dm", h, m)
        } else {
            String.format(java.util.Locale.US, "%dm %02ds", m, s)
        }
    }

    private fun formatDistance(meters: Float): String {
        return if (meters >= 1000f) {
            String.format(java.util.Locale.US, "%.2f km", meters / 1000f)
        } else {
            String.format(java.util.Locale.US, "%.0f m", meters)
        }
    }

    companion object {
        const val CHANNEL_ID = "trail_recording"
        private const val NOTIF_ID = 4101
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val TAG = "TrailForegroundSvc"
        private const val ACTION_STOP_TRAIL = "app.peermesh.action.STOP_TRAIL_RECORDING"
        private const val STOP_REQUEST_CODE = 4102
        private const val STOP_ACTION_DELAY_MS = 750L

        fun start(context: Context) {
            val intent = Intent(context, TrailForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrailForegroundService::class.java))
        }
    }
}
