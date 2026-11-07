package app.peermesh.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import app.peermesh.R
import app.peermesh.PeerMeshCapabilities
import app.peermesh.transport.relay.RelayTrafficStats
import app.peermesh.transport.relay.TrafficSnapshot
import app.peermesh.data.debug.ActivityLog

class RelaySyncService : Service() {
    private data class PendingNetworkEvaluation(
        val reason: String,
        val allowed: Boolean,
    )

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isPaused = false
    private var forceForeground = false
    private var foregroundStarted = false
    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null
    private var lastSnapshot: TrafficSnapshot? = null
    private var pendingNetworkEvaluation: PendingNetworkEvaluation? = null
    private var networkEvaluationRunnable: Runnable? = null
    private var lastNotificationText: String? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        forceForeground = intent?.getBooleanExtra(EXTRA_FORCE_FOREGROUND, false) == true
        ensureForegroundStartedIfRequired()
        if (!PeerMeshCapabilities.canUseRelaySync) {
            ActivityLog.add(
                label = "Relay sync start blocked",
                source = "relay",
                message = "capability=false",
            )
            RelaySyncLifecycleCoordinator.onCapabilityUnavailable(this)
            SYNC_RUNNING.set(false)
            notifyStatusChanged()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!RelaySyncSettings.isEnabled(this)) {
            ActivityLog.add(
                label = "Relay sync start blocked",
                source = "relay",
                message = "enabled=false",
            )
            SYNC_RUNNING.set(false)
            notifyStatusChanged()
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = RelaySyncSettings.load(this)
        if (mode == SyncMode.BATTERY_SAVER && !forceForeground) {
            ActivityLog.add(
                label = "Relay sync start deferred",
                source = "relay",
                message = "mode=$mode forceForeground=$forceForeground",
            )
            RelaySyncLifecycleCoordinator.onBackgroundModeRequested(this)
            SYNC_RUNNING.set(false)
            notifyStatusChanged()
            stopSelf()
            return START_NOT_STICKY
        }
        ActivityLog.add(
            label = "Relay sync start",
            source = "relay",
            message = "mode=$mode forceForeground=$forceForeground",
        )
        if (connectivityManager == null) {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }
        if (networkCallback == null) {
            ensureForegroundStarted()
            startWhenAllowedOrStop()
        } else {
            updateNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAndCleanup()
        ActivityLog.add(
            label = "Relay sync stopped",
            source = "relay",
        )
        SYNC_RUNNING.set(false)
        notifyStatusChanged()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun networksPendingIntent(): android.app.PendingIntent =
        android.app.PendingIntent.getActivity(
            this, 102,
            Intent(this, app.peermesh.StatisticsActivity::class.java).apply {
                putExtra("network_target", "cloud_sync")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Relay Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val text = if (isPaused) {
            "Relay sync paused (offline)"
        } else {
            val summary = trafficSummary()
            if (summary.isNotBlank()) "Relay sync running • $summary" else "Relay sync running"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_satellite)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(networksPendingIntent())
            .setOngoing(true)
            .build()
    }

    private fun currentNotificationText(): String {
        return if (isPaused) {
            "Relay sync paused (offline)"
        } else {
            val summary = trafficSummary()
            if (summary.isNotBlank()) "Relay sync running • $summary" else "Relay sync running"
        }
    }

    private fun ensureForegroundStartedIfRequired() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureForegroundStarted()
        }
    }

    private fun ensureForegroundStarted() {
        if (foregroundStarted) return
        startForeground(NOTIFICATION_ID, createNotification())
        foregroundStarted = true
    }

    private fun startWhenAllowedOrStop() {
        val manager = connectivityManager ?: return
        ensureNetworkCallbackRegistered(manager)
        if (!shouldRunNow(manager)) {
            Log.i(TAG, "Relay sync not allowed at start, scheduling resume worker")
            ActivityLog.add(
                label = "Relay sync paused",
                source = "relay",
                message = "reason=start_not_allowed",
            )
            if (shouldPauseInPlace()) {
                pauseInPlace()
                return
            }
            RelaySyncResumeWorker.enqueue(this)
            pauseInPlace()
            stopSelfSafely()
            return
        }
        isPaused = false
        RelaySyncCoordinator.get(this).start(desiredIntervalMs())
        SYNC_RUNNING.set(true)
        notifyStatusChanged()
        startTrafficUpdates()
    }

    private fun ensureNetworkCallbackRegistered(manager: ConnectivityManager) {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                evaluateNetworkChange("Network available")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val online = isOnline(manager)
                Log.i(TAG, "Network capabilities changed, isOnline=$online")
                if (!online) {
                    evaluateNetworkChange("Network not validated")
                } else if (RelaySyncSettings.load(this@RelaySyncService) == SyncMode.NORMAL) {
                    evaluateNetworkChange("Network validated")
                } else {
                    evaluateNetworkChange("Network validated (check charging)")
                }
            }

            override fun onLost(network: Network) {
                evaluateNetworkChange("Network lost")
            }
        }
        networkCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            manager.registerNetworkCallback(request, callback)
        }
    }

    private fun evaluateNetworkChange(reason: String) {
        val manager = connectivityManager ?: return
        val allowed = shouldRunNow(manager)
        scheduleNetworkEvaluation(reason, allowed)
    }

    private fun scheduleNetworkEvaluation(reason: String, allowed: Boolean) {
        pendingNetworkEvaluation = PendingNetworkEvaluation(reason, allowed)
        networkEvaluationRunnable?.let { statsHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val evaluation = pendingNetworkEvaluation ?: return@Runnable
            pendingNetworkEvaluation = null
            applyNetworkEvaluation(evaluation.reason, evaluation.allowed)
        }
        networkEvaluationRunnable = runnable
        statsHandler.postDelayed(runnable, NETWORK_EVALUATION_DEBOUNCE_MS)
    }

    private fun applyNetworkEvaluation(reason: String, allowed: Boolean) {
        Log.i(TAG, "$reason, shouldRunNow=$allowed")
        ActivityLog.add(
            label = "Relay sync network change",
            source = "relay",
            message = "reason=$reason allowed=$allowed",
        )
        if (allowed && !isPaused && SYNC_RUNNING.get()) {
            return
        }
        if (!allowed && isPaused && !SYNC_RUNNING.get()) {
            return
        }
        if (allowed) {
            isPaused = false
            updateNotification()
            RelaySyncCoordinator.get(this).start(desiredIntervalMs())
            SYNC_RUNNING.set(true)
            notifyStatusChanged()
            startTrafficUpdates()
        } else {
            if (shouldPauseInPlace()) {
                pauseInPlace()
                return
            }
            if (RelaySyncSettings.load(this) == SyncMode.BATTERY_SAVER) {
                RelaySyncLifecycleCoordinator.onBackgroundModeRequested(this)
                pauseInPlace()
                stopSelfSafely()
                return
            }
            RelaySyncCoordinator.get(this).stop()
            RelaySyncResumeWorker.enqueue(this)
            pauseInPlace()
            stopSelfSafely()
        }
    }

    private fun shouldPauseInPlace(): Boolean {
        return forceForeground || RelaySyncSettings.load(this) == SyncMode.NORMAL
    }

    private fun pauseInPlace() {
        RelaySyncCoordinator.get(this).stop()
        stopTrafficUpdates()
        isPaused = true
        updateNotification()
        SYNC_RUNNING.set(false)
        notifyStatusChanged()
    }

    private fun stopAndCleanup() {
        stopTrafficUpdates()
        networkEvaluationRunnable?.let { statsHandler.removeCallbacks(it) }
        networkEvaluationRunnable = null
        pendingNetworkEvaluation = null
        RelaySyncCoordinator.get(this).stop()
        val manager = connectivityManager
        val callback = networkCallback
        if (manager != null && callback != null) {
            manager.unregisterNetworkCallback(callback)
        }
        networkCallback = null
    }

    private fun isOnline(manager: ConnectivityManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            manager.activeNetworkInfo?.isConnected == true
        }
    }

    private fun shouldRunNow(manager: ConnectivityManager): Boolean {
        if (!isOnline(manager)) return false
        return when (RelaySyncSettings.load(this)) {
            SyncMode.NORMAL -> true
            SyncMode.BATTERY_SAVER -> forceForeground
        }
    }

    private fun stopSelfSafely() {
        val offline = connectivityManager?.let { !isOnline(it) } == true
        Log.i(TAG, "Stopping service (offline=$offline)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        cancelAllNotifications()
        stopSelf()
    }

    private fun cancelAllNotifications() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        lastNotificationText = null
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        val text = currentNotificationText()
        if (lastNotificationText == text) return
        lastNotificationText = text
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_satellite)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(networksPendingIntent())
                .setOngoing(true)
                .build()
        )
    }

    private fun notifyStatusChanged() {
        val signature = "${SYNC_RUNNING.get()}|$isPaused"
        if (lastBroadcastState == signature) return
        lastBroadcastState = signature
        val intent = Intent(ACTION_RELAY_STATUS_CHANGED).setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun startTrafficUpdates() {
        if (statsRunnable != null) return
        lastSnapshot = RelayTrafficStats.snapshot()
        val runnable = Runnable {
            updateNotification()
            statsHandler.postDelayed(statsRunnable!!, TRAFFIC_UPDATE_MS)
        }
        statsRunnable = runnable
        statsHandler.postDelayed(runnable, TRAFFIC_UPDATE_MS)
    }

    private fun stopTrafficUpdates() {
        statsRunnable?.let { statsHandler.removeCallbacks(it) }
        statsRunnable = null
        lastSnapshot = null
        lastNotificationText = null
    }

    private fun trafficSummary(): String {
        val current = RelayTrafficStats.snapshot()
        val last = lastSnapshot
        if (last == null) {
            lastSnapshot = current
            return ""
        }
        val deltaMs = (current.timestampMs - last.timestampMs).coerceAtLeast(1)
        val upDelta = (current.uploaded - last.uploaded).coerceAtLeast(0)
        val downDelta = (current.downloaded - last.downloaded).coerceAtLeast(0)
        lastSnapshot = current
        val upRate = upDelta * 1000L / deltaMs
        val downRate = downDelta * 1000L / deltaMs
        return "↓ ${formatBytes(current.downloaded)} | ${formatRate(downRate)}  ↑ ${formatBytes(current.uploaded)} | ${formatRate(upRate)}"
    }

    private fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.2f MB", bytes / mb)
            bytes >= kb -> String.format("%.2f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private fun formatRate(bytesPerSec: Long): String {
        return "${formatBytes(bytesPerSec)}/s"
    }

    companion object {
        private const val TAG = "RelaySyncService"
        private const val CHANNEL_ID = "relay_sync"
        private const val NOTIFICATION_ID = 1002
        private const val TRAFFIC_UPDATE_MS = 2000L
        private const val NETWORK_EVALUATION_DEBOUNCE_MS = 1500L
        private val SYNC_RUNNING = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile private var lastBroadcastState: String? = null
        const val ACTION_RELAY_STATUS_CHANGED = "app.peermesh.action.RELAY_STATUS_CHANGED"
        private const val EXTRA_FORCE_FOREGROUND = "extra_force_foreground"
        private const val INTERVAL_NORMAL_FOREGROUND_MS = 15_000L
        private const val INTERVAL_BATTERY_FOREGROUND_MS = 120_000L

        fun start(context: Context, forceForeground: Boolean = false) {
            if (!PeerMeshCapabilities.canUseRelaySync) {
                RelaySyncLifecycleCoordinator.onCapabilityUnavailable(context)
                return
            }
            if (!RelaySyncSettings.isEnabled(context)) return
            val intent = Intent(context, RelaySyncService::class.java)
                .putExtra(EXTRA_FORCE_FOREGROUND, forceForeground)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RelaySyncService::class.java)
            context.stopService(intent)
            SYNC_RUNNING.set(false)
            lastBroadcastState = null
            val broadcast = Intent(ACTION_RELAY_STATUS_CHANGED).setPackage(context.packageName)
            context.sendBroadcast(broadcast)
        }

        fun isSyncRunning(): Boolean {
            return SYNC_RUNNING.get()
        }

        fun setSyncRunning(value: Boolean) {
            SYNC_RUNNING.set(value)
        }

        fun desiredIntervalMs(context: Context, forceForeground: Boolean): Long {
            return if (RelaySyncSettings.load(context) == SyncMode.BATTERY_SAVER && forceForeground) {
                INTERVAL_BATTERY_FOREGROUND_MS
            } else {
                INTERVAL_NORMAL_FOREGROUND_MS
            }
        }
    }

    private fun desiredIntervalMs(): Long {
        return if (RelaySyncSettings.load(this) == SyncMode.BATTERY_SAVER && forceForeground) {
            INTERVAL_BATTERY_FOREGROUND_MS
        } else {
            INTERVAL_NORMAL_FOREGROUND_MS
        }
    }
}
