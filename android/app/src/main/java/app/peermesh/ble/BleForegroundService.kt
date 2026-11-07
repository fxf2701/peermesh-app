package app.peermesh.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.app.NotificationCompat
import app.peermesh.R
import app.peermesh.ble.BlePermissionHelper
import app.peermesh.core.transport.EnvelopeReceiver
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.ingest.EnvelopeReceiverImpl
import app.peermesh.data.ingest.EventIngestor
import app.peermesh.data.membership.GroupPeerRouteStore
import app.peermesh.nearby.NearbyRoomTransportReceiver
import app.peermesh.security.crypto.SecureKeyStore
import app.peermesh.transport.adapter.MeshTransport
import app.peermesh.transport.ble.BleTrafficStats
import app.peermesh.transport.ble.BleTrafficSnapshot
import app.peermesh.transport.ble.BleConfig
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.provider.Settings
import app.peermesh.DeviceIdProvider
import app.peermesh.NotificationGate
import app.peermesh.OutboundBatchDispatcher
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.transport.AppBatcher
import app.peermesh.moderation.ModerationRepository

class BleForegroundService : Service() {
    private var meshTransport: MeshTransport? = null
    private var envelopeReceiverImpl: EnvelopeReceiver? = null
    private var isRunning = false
    private var foregroundStarted = false
    private var receiverRegistered = false
    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsRunnable: Runnable? = null
    private var lastSnapshot: BleTrafficSnapshot? = null
    private var lastRecoveryAtMs: Long = 0L
    private var lastBluetoothOnAtMs: Long = 0L
    private var btOnDelayScheduled: Boolean = false
    private var consecutiveStartupStallRecoveries: Int = 0
    private var scanStallRecoveryPending: Boolean = false
    private var transportStallRecoveryPending: Boolean = false
    private var transportStallConfirmTokenMs: Long = 0L
    private var startupStallMonitoringActive: Boolean = false
    private var envBlockedDebounceTokenMs: Long = 0L
    private var envBlockedDebounceAttempts: Int = 0
    private val serviceInstanceId = SERVICE_INSTANCE_COUNTER.incrementAndGet()

    private fun logServiceEvent(label: String, message: String) {
        ActivityLog.add(
            label = label,
            source = "mesh",
            message = "instance=$serviceInstanceId $message",
        )
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            ActivityLog.add(
                label = "BLE state receiver event",
                source = "mesh",
                message = "receiver=bluetooth action=${intent.action} state=$state",
            )
            val enabled = state == BluetoothAdapter.STATE_ON
            if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_OFF) {
                ActivityLog.add(
                    label = "BLE bluetooth state",
                    source = "mesh",
                    message = "enabled=$enabled state=$state",
                )
            }
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_ON) {
                Log.i(TAG, "Bluetooth state changed=$state; reevaluating BLE mesh")
                if (state == BluetoothAdapter.STATE_ON) {
                    lastBluetoothOnAtMs = System.currentTimeMillis()
                }
                evaluateMeshState(
                    forceHardRestart = state == BluetoothAdapter.STATE_ON,
                    trigger = "bluetooth_state_$state",
                )
            }
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                android.location.LocationManager.MODE_CHANGED_ACTION,
                Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                android.location.LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    ActivityLog.add(
                        label = "BLE state receiver event",
                        source = "mesh",
                        message = "receiver=location action=${intent.action}",
                    )
                    val enabled = isLocationEnabled()
                    ActivityLog.add(
                        label = "BLE location state",
                        source = "mesh",
                        message = "enabled=$enabled",
                    )
                    Log.i(TAG, "Location state changed; reevaluating BLE mesh")
                    evaluateMeshState(forceHardRestart = true, trigger = "location_state_changed")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        logServiceEvent(label = "BLE service lifecycle", message = "event=onCreate")
        scanStallRecoveryPending = false
        transportStallRecoveryPending = false
        startupStallMonitoringActive = false
        BleMeshStatus.lastMeshStartStage = null
        BleMeshStatus.lastMeshStartReason = null
        BleMeshStatus.lastMeshStartAtMs = 0L
        BleMeshStatus.lastTransportStartAtMs = 0L
        BleMeshStatus.lastStartupStallDetectedAtMs = 0L
        BleMeshStatus.lastStartupRecoveryAtMs = 0L
        BleMeshStatus.lastScanStartAtMs = 0L
        BleMeshStatus.lastScanStartSkippedAtMs = 0L
        BleMeshStatus.lastScanStartSkippedReason = null
        BleMeshStatus.lastAdvertiseStartedAtMs = 0L
        BleMeshStatus.lastScanCallbackAtMs = 0L
        BleMeshStatus.lastAutoRecoverAtMs = 0L
        BleMeshStatus.lastAdaptiveState = null
        BleMeshStatus.lastAdaptiveStateAtMs = 0L
        BleMeshStatus.lastTrafficAtMs = 0L
        BleMeshStatus.lastTransportProgressAtMs = 0L
        BleMeshStatus.lastGattConnectedAtMs = 0L
        BleMeshStatus.lastStallDetectedAtMs = 0L
        BleMeshStatus.lastStallRecoveryAtMs = 0L
        BleMeshStatus.lastNearbyTotal = -1
        BleMeshStatus.lastNearbyTotalAtMs = 0L
        BleMeshStatus.notifyChanged()
        SERVICE_RUNNING.set(true)
        val db = AppDatabaseProvider.get(applicationContext)
        val moderationRepository = ModerationRepository(applicationContext)
        val ingestor = EventIngestor(
            db = db,
            localActorProvider = {
                DeviceIdProvider.getActiveActor(applicationContext)
            },
            hiddenMessagePeerIdsProvider = { gid ->
                moderationRepository.hiddenMessagePeerIds(gid)
            },
        )
        val receiver = NearbyRoomTransportReceiver(
            applicationContext,
            EnvelopeReceiverImpl(
                ingestor = ingestor,
                keyStore = SecureKeyStore(applicationContext),
                groupPeerRouteStore = GroupPeerRouteStore(db.groupPeerRouteDao()),
            ),
        )
        envelopeReceiverImpl = receiver
        meshTransport = BleMeshRuntime.init(applicationContext, receiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "null"
        val caller = intent?.getStringExtra(EXTRA_CALLER) ?: "unknown"
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "none"
        logServiceEvent(
            label = "BLE service start command",
            message = "action=$action start_id=$startId flags=$flags caller=$caller reason=$reason is_running=$isRunning service_running=${SERVICE_RUNNING.get()} mesh_running=${MESH_RUNNING.get()} foreground=$foregroundStarted",
        )
        if (intent?.action != ACTION_STOP_MESH &&
            !app.peermesh.PeerMeshPrefs.isBleMeshUserEnabled(applicationContext)
        ) {
            logServiceEvent(
                label = "BLE service start blocked",
                message = "reason=user_disabled action=$action caller=$caller",
            )
            stopMeshAndService()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP_MESH -> stopMeshAndService()
            ACTION_RESET_MESH -> resetMesh(trigger = "intent:$caller:$reason")
            ACTION_START_MESH, null -> startMesh(trigger = "intent:$caller:$reason")
        }
        return START_STICKY
    }

    private fun startMesh(trigger: String) {
        if (!BlePermissionHelper.requiredPermissions().all { perm ->
                ContextCompat.checkSelfPermission(this, perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }) {
            Log.i(TAG, "BLE permissions missing; mesh not started")
            return
        }
        ensureForeground()
        registerStateReceiversIfNeeded()
        evaluateMeshState(forceHardRestart = false, trigger = trigger)
    }

    private fun resetMesh(trigger: String) {
        if (!BlePermissionHelper.requiredPermissions().all { perm ->
                ContextCompat.checkSelfPermission(this, perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }) {
            Log.i(TAG, "BLE permissions missing; mesh not reset")
            return
        }
        ensureForeground()
        registerStateReceiversIfNeeded()
        evaluateMeshState(forceHardRestart = true, trigger = trigger)
    }

    private fun stopMeshAndService() {
        logServiceEvent(
            label = "BLE service lifecycle",
            message = "event=stopMeshAndService is_running=$isRunning foreground=$foregroundStarted",
        )
        stopMeshInternal()
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            foregroundStarted = false
        }
        if (receiverRegistered) {
            unregisterReceiver(bluetoothReceiver)
            unregisterReceiver(locationReceiver)
            receiverRegistered = false
        }
        stopSelf()
    }

    private fun stopMeshInternal() {
        if (!isRunning) {
            logServiceEvent(
                label = "BLE service stop mesh",
                message = "event=noop already_running=false",
            )
            MESH_RUNNING.set(false)
            BleMeshStatus.notifyChanged()
            stopTrafficUpdates()
            return
        }
        logServiceEvent(
            label = "BLE service stop mesh",
            message = "event=stop running=true",
        )
        meshTransport?.stop()
        isRunning = false
        MESH_RUNNING.set(false)
        BleMeshStatus.notifyChanged()
        stopTrafficUpdates()
        Log.i(TAG, "BLE mesh stopped")
    }

    override fun onDestroy() {
        logServiceEvent(
            label = "BLE service lifecycle",
            message = "event=onDestroy is_running=$isRunning foreground=$foregroundStarted receiver_registered=$receiverRegistered",
        )
        if (receiverRegistered) {
            unregisterReceiver(bluetoothReceiver)
            unregisterReceiver(locationReceiver)
            receiverRegistered = false
        }
        stopTrafficUpdates()
        MESH_RUNNING.set(false)
        SERVICE_RUNNING.set(false)
        BleMeshStatus.notifyChanged()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!foregroundStarted ||
            !app.peermesh.PeerMeshPrefs.isBleMeshUserEnabled(applicationContext)
        ) return
        logServiceEvent(
            label = "BLE service lifecycle",
            message = "event=onTaskRemoved restarting=true",
        )
        val restartIntent = Intent(applicationContext, BleForegroundService::class.java).apply {
            action = ACTION_START_MESH
            putExtra(EXTRA_CALLER, "onTaskRemoved")
            putExtra(EXTRA_REASON, "task_removed_restart")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }

    private fun createNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Mesh",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val text = notificationStatusText()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mesh)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(android.app.PendingIntent.getActivity(
                this, 101,
                Intent(this, app.peermesh.StatisticsActivity::class.java).apply {
                    putExtra("network_target", "ble_mesh")
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            ))
            .setOngoing(true)
            .build()
    }


    private fun notificationStatusText(): String {
        val blocked = blockedReasonText()
        if (blocked != null) {
            return "BLE mesh paused: $blocked"
        }
        if (!isRunning) {
            return "BLE mesh paused"
        }
        val now = System.currentTimeMillis()
        val scanFresh = isScanFresh(now)
        val advertiseFresh = isAdvertiseFresh(now)
        val recovering = now - BleMeshStatus.lastAutoRecoverAtMs < RECOVERING_WINDOW_MS
        if (recovering) {
            return "BLE mesh recovering: restarting scan..."
        }
        if (now - BleMeshStatus.lastStartupRecoveryAtMs < RECOVERING_WINDOW_MS) {
            return "BLE mesh recovering: restarting startup..."
        }
        if (isStartupStalled(now)) {
            return "BLE mesh stalled: restarting startup..."
        }
        if (isTransportStalled(now)) {
            return "BLE mesh stalled: restarting transport..."
        }
        val lastTrafficAt = BleMeshStatus.lastTrafficAtMs
        if (lastTrafficAt > 0L && now - lastTrafficAt <= TRAFFIC_RECENT_WINDOW_MS) {
            val summary = trafficSummary()
            if (summary.isNotBlank()) {
                return "BLE mesh running - $summary"
            }
        }
        if (scanFresh && advertiseFresh) {
            val nearby = BleMeshStatus.nearbyTotalForUi(now) {
                meshTransport?.nearbyDeviceCount(now) ?: 0
            }
            if (nearby <= 0) {
                return "BLE mesh running: no nearby devices"
            }
            val summary = trafficSummary()
            return if (summary.isNotBlank()) "BLE mesh running - $summary" else "BLE mesh running"
        }
        if (BleMeshStatus.lastAdaptiveState == "idle") {
            return "BLE mesh running (idle)"
        }
        val scanReason = BleMeshStatus.lastScanStartSkippedReason
        if (!scanReason.isNullOrBlank() &&
            now - BleMeshStatus.lastScanStartSkippedAtMs < SCAN_SKIPPED_WINDOW_MS
        ) {
            return "BLE mesh paused: scan $scanReason"
        }
        return "BLE mesh starting..."
    }

    private fun startTrafficUpdates() {
        if (statsRunnable != null) return
        lastSnapshot = BleTrafficStats.snapshot()
        val runnable = Runnable {
            maybeResetStartupRecoveryState()
            maybeHardRestartAfterStartupStall()
            maybeHardRestartAfterScanStall()
            maybeHardRestartAfterTransportStall()
            updateNotification()
            val next = statsRunnable ?: return@Runnable
            statsHandler.postDelayed(next, TRAFFIC_UPDATE_MS)
        }
        statsRunnable = runnable
        statsHandler.postDelayed(runnable, TRAFFIC_UPDATE_MS)
    }

    private fun stopTrafficUpdates() {
        statsRunnable?.let { statsHandler.removeCallbacks(it) }
        statsRunnable = null
        lastSnapshot = null
    }

    private fun maybeHardRestartAfterScanStall() {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        if (isScanFresh(now)) {
            scanStallRecoveryPending = false
            return
        }
        if (scanStallRecoveryPending) return
        if (now - lastRecoveryAtMs < RECOVERY_COOLDOWN_MS) return
        val lastScan = if (BleMeshStatus.lastScanCallbackAtMs > 0L) {
            BleMeshStatus.lastScanCallbackAtMs
        } else {
            BleMeshStatus.lastScanStartAtMs
        }
        if (lastScan <= 0L) return
        val scanAgeMs = now - lastScan
        if (scanAgeMs < SCAN_STALL_HARD_RESTART_MS) return
        ActivityLog.add(
            label = "BLE mesh scan stalled",
            source = "mesh",
            message = "last_scan_age_ms=$scanAgeMs",
        )
        Log.w(TAG, "Hard-restarting BLE mesh after scan stall")
        scanStallRecoveryPending = true
        lastRecoveryAtMs = now
        resetTransportStallState()
        meshTransport?.hardRestart()
        MESH_RUNNING.set(true)
        BleMeshStatus.notifyChanged()
    }

    private fun maybeHardRestartAfterStartupStall() {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        if (!isStartupStalled(now)) return
        if (now - lastRecoveryAtMs < STARTUP_RECOVERY_COOLDOWN_MS) return
        consecutiveStartupStallRecoveries += 1
        val stalledStage = BleMeshStatus.lastMeshStartStage
        val escalateToReinit =
            stalledStage == "called" ||
                consecutiveStartupStallRecoveries >= STARTUP_STALL_REINIT_THRESHOLD
        lastRecoveryAtMs = now
        BleMeshStatus.lastStartupRecoveryAtMs = now
        ActivityLog.add(
            label = "BLE mesh startup recovery",
            source = "mesh",
            message = "attempt=$consecutiveStartupStallRecoveries stage=${stalledStage ?: "unknown"} mode=${if (escalateToReinit) "reinit_runtime" else "hard_restart"}",
        )
        if (escalateToReinit) {
            Log.w(TAG, "Reinitializing BLE mesh runtime after startup stall stage=$stalledStage")
            val receiver = envelopeReceiverImpl ?: return
            resetTransportStallState()
            meshTransport = BleMeshRuntime.reinitialize(applicationContext, receiver)
            meshTransport?.start()
        } else {
            Log.w(TAG, "Hard-restarting BLE mesh after startup stall")
            resetTransportStallState()
            meshTransport?.hardRestart()
        }
        MESH_RUNNING.set(true)
        BleMeshStatus.notifyChanged()
    }

    private fun maybeHardRestartAfterTransportStall() {
        if (!isRunning) return
        val now = System.currentTimeMillis()
        if (isTransportHealthy(now)) {
            transportStallRecoveryPending = false
            transportStallConfirmTokenMs = 0L
            return
        }
        if (transportStallRecoveryPending) return
        if (!isTransportStalled(now)) return
        if (now - lastRecoveryAtMs < TRANSPORT_RECOVERY_COOLDOWN_MS) return
        Log.w(TAG, "Transport stall detected; scheduling restart confirmation")
        ActivityLog.add(
            label = "BLE mesh transport stalled",
            source = "mesh",
            message = "nearby=${meshTransport?.nearbyDeviceCount() ?: 0} last_progress_age_ms=${transportProgressAgeMs(now)} action=confirm_then_restart",
        )
        transportStallRecoveryPending = true
        transportStallConfirmTokenMs = now
        val expectedToken = transportStallConfirmTokenMs
        statsHandler.postDelayed({
            confirmTransportStallAndMaybeRestart(expectedToken)
        }, TRANSPORT_STALL_CONFIRM_MS)
    }

    private fun confirmTransportStallAndMaybeRestart(tokenMs: Long) {
        if (tokenMs == 0L) return
        if (!isRunning) return
        if (!transportStallRecoveryPending) return
        if (transportStallConfirmTokenMs != tokenMs) return
        val now = System.currentTimeMillis()
        if (isTransportHealthy(now) || !isTransportStalled(now)) {
            transportStallRecoveryPending = false
            transportStallConfirmTokenMs = 0L
            ActivityLog.add(
                label = "BLE mesh transport recovery skipped",
                source = "mesh",
                message = "reason=stall_cleared_before_restart",
            )
            return
        }
        if (now - lastRecoveryAtMs < TRANSPORT_RECOVERY_COOLDOWN_MS) {
            transportStallRecoveryPending = false
            transportStallConfirmTokenMs = 0L
            return
        }
        Log.w(TAG, "Hard-restarting BLE mesh after confirmed transport stall")
        ActivityLog.add(
            label = "BLE mesh transport hard restart",
            source = "mesh",
            message = "nearby=${meshTransport?.nearbyDeviceCount() ?: 0} last_progress_age_ms=${transportProgressAgeMs(now)}",
        )
        transportStallRecoveryPending = false
        transportStallConfirmTokenMs = 0L
        lastRecoveryAtMs = now
        BleMeshStatus.lastStallRecoveryAtMs = now
        resetTransportStallState()
        meshTransport?.hardRestart()
        MESH_RUNNING.set(true)
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun trafficSummary(): String {
        val current = BleTrafficStats.snapshot()
        val last = lastSnapshot
        if (last == null) {
            lastSnapshot = current
            if (current.uploaded > 0L || current.downloaded > 0L) {
                BleMeshStatus.lastTrafficAtMs = current.timestampMs
            }
            return ""
        }
        val deltaMs = (current.timestampMs - last.timestampMs).coerceAtLeast(1)
        val upDelta = (current.uploaded - last.uploaded).coerceAtLeast(0)
        val downDelta = (current.downloaded - last.downloaded).coerceAtLeast(0)
        lastSnapshot = current
        if (upDelta > 0 || downDelta > 0) {
            BleMeshStatus.lastTrafficAtMs = current.timestampMs
        }
        val upRate = upDelta * 1000L / deltaMs
        val downRate = downDelta * 1000L / deltaMs
        return "RX ${formatBytes(current.downloaded)} | ${formatRate(downRate)}  TX ${formatBytes(current.uploaded)} | ${formatRate(upRate)}"
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
        const val CHANNEL_ID = "ble_mesh"
        const val NOTIFICATION_ID = 1001
        private const val ACTION_START_MESH = "app.peermesh.action.START_BLE_MESH"
        private const val ACTION_STOP_MESH = "app.peermesh.action.STOP_BLE_MESH"
        private const val ACTION_RESET_MESH = "app.peermesh.action.RESET_BLE_MESH"
        private const val EXTRA_CALLER = "mesh_caller"
        private const val EXTRA_REASON = "mesh_reason"
        private const val TAG = "BleMeshService"
        private const val TRAFFIC_UPDATE_MS = 2000L
        private const val RECOVERY_COOLDOWN_MS = 5_000L
        private const val TRANSPORT_RECOVERY_COOLDOWN_MS = 30_000L
        private const val STARTUP_RECOVERY_COOLDOWN_MS = 10_000L
        private const val BT_ON_START_DELAY_MS = 10_000L
        private const val STARTUP_STALL_HARD_RESTART_MS = 15_000L
        private const val STARTUP_STALL_REINIT_THRESHOLD = 2
        private const val SCAN_STALL_HARD_RESTART_MS = 90_000L
        private const val TRANSPORT_STALL_HARD_RESTART_MS = 45_000L
        private const val TRANSPORT_STALL_WARMUP_MS = 45_000L
        private const val TRANSPORT_STALL_CONFIRM_MS = 6_000L
        private const val ENV_BLOCKED_DEBOUNCE_MS = 4_000L
        private const val ENV_BLOCKED_DEBOUNCE_MAX_ATTEMPTS = 2
        private const val RECOVERING_WINDOW_MS = 60_000L
        private const val TRAFFIC_RECENT_WINDOW_MS = 10_000L
        private const val SCAN_FRESH_WINDOW_MS = 60_000L
        private const val ADVERTISE_FRESH_WINDOW_MS = 5 * 60_000L
        private const val SCAN_SKIPPED_WINDOW_MS = 30_000L
        private const val STALL_DETECTED_LOG_COOLDOWN_MS = 30_000L
        private val MESH_RUNNING = java.util.concurrent.atomic.AtomicBoolean(false)
        private val SERVICE_RUNNING = java.util.concurrent.atomic.AtomicBoolean(false)
        private val SERVICE_INSTANCE_COUNTER = java.util.concurrent.atomic.AtomicLong(0)

        fun startMesh(context: Context, caller: String = "unknown", reason: String = "start_request") {
            if (SERVICE_RUNNING.get() && MESH_RUNNING.get()) {
                ActivityLog.add(
                    label = "BLE service start request",
                    source = "mesh",
                    message = "caller=$caller reason=$reason action=start skipped=already_running service_running=true mesh_running=true",
                )
                return
            }
            if (!canStartMesh(context)) {
                ActivityLog.add(
                    label = "BLE service start request",
                    source = "mesh",
                    message = "caller=$caller reason=$reason action=start skipped=cannot_start_now service_running=${SERVICE_RUNNING.get()} mesh_running=${MESH_RUNNING.get()}",
                )
                return
            }
            val intent = Intent(context, BleForegroundService::class.java).apply {
                action = ACTION_START_MESH
                putExtra(EXTRA_CALLER, caller)
                putExtra(EXTRA_REASON, reason)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopMesh(context: Context, caller: String = "unknown", reason: String = "stop_request") {
            val intent = Intent(context, BleForegroundService::class.java).apply {
                action = ACTION_STOP_MESH
                putExtra(EXTRA_CALLER, caller)
                putExtra(EXTRA_REASON, reason)
            }
            context.startService(intent)
        }

        fun resetMesh(context: Context, caller: String = "unknown", reason: String = "reset_request") {
            if (!canStartMesh(context)) {
                ActivityLog.add(
                    label = "BLE service reset request",
                    source = "mesh",
                    message = "caller=$caller reason=$reason action=reset skipped=cannot_start_now service_running=${SERVICE_RUNNING.get()} mesh_running=${MESH_RUNNING.get()}",
                )
                return
            }
            val intent = Intent(context, BleForegroundService::class.java).apply {
                action = ACTION_RESET_MESH
                putExtra(EXTRA_CALLER, caller)
                putExtra(EXTRA_REASON, reason)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun isMeshRunning(): Boolean {
            return MESH_RUNNING.get()
        }

        fun isServiceRunning(): Boolean {
            return SERVICE_RUNNING.get()
        }

        fun setMeshRunning(value: Boolean) {
            MESH_RUNNING.set(value)
        }

        fun canStartMesh(context: Context): Boolean {
            if (!app.peermesh.PeerMeshPrefs.isBleMeshUserEnabled(context)) return false
            val hasPerms = BlePermissionHelper.requiredPermissions().all { perm ->
                ContextCompat.checkSelfPermission(context, perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (!hasPerms) return false
            if (!isLocationEnabledForMesh(context)) return false
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = manager.adapter ?: return false
            return adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
        }

        private fun isLocationEnabledForMesh(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            return LocationManagerCompat.isLocationEnabled(locationManager)
        }
    }

    private fun ensureForeground() {
        if (foregroundStarted) return
        startForeground(NOTIFICATION_ID, createNotification())
        foregroundStarted = true
        logNotificationState()
    }

    private fun logNotificationState() {
        val status = NotificationGate.status(this, CHANNEL_ID)
        val state = when {
            status.enabled -> "enabled"
            status.needsPermission -> "disabled_runtime_perm_missing"
            status.channelDisabled -> "disabled_channel"
            else -> "disabled_os_level"
        }
        val text = notificationStatusText()
        ActivityLog.add(
            label = "BLE notification status",
            source = "mesh",
            message = "instance=$serviceInstanceId status=$state enabled=${status.enabled} needs_permission=${status.needsPermission} channel_disabled=${status.channelDisabled} text=${text.replace(" ", "_")}",
        )
        when (state) {
            "enabled" -> Log.i(TAG, "notif_enabled")
            "disabled_runtime_perm_missing" -> Log.w(TAG, "notif_disabled_runtime_perm_missing")
            "disabled_channel" -> Log.w(TAG, "notif_disabled_channel")
            else -> Log.w(TAG, "notif_disabled_os_level")
        }
    }

    private fun registerStateReceiversIfNeeded() {
        if (receiverRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                bluetoothReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
        val locationFilter = IntentFilter().apply {
            addAction(android.location.LocationManager.MODE_CHANGED_ACTION)
            addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(locationReceiver, locationFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(locationReceiver, locationFilter)
        }
        receiverRegistered = true
        ActivityLog.add(
            label = "BLE state receiver registered",
            source = "mesh",
            message = "bluetooth=true location=true",
        )
    }

    private fun hasActiveTransportWork(): Boolean {
        val transport = meshTransport ?: return false
        return runCatching { transport.hasPendingDataTraffic() }.getOrDefault(false)
    }

    private fun scheduleEnvBlockedDebounce(
        trigger: String,
        bluetoothEnabled: Boolean,
        locationEnabled: Boolean,
    ): Boolean {
        if (!isRunning) return false
        if (!hasActiveTransportWork()) return false
        val token = System.currentTimeMillis()
        envBlockedDebounceTokenMs = token
        envBlockedDebounceAttempts += 1
        logServiceEvent(
            label = "BLE env blocked debounce start",
            message = "trigger=$trigger bluetoothEnabled=$bluetoothEnabled locationEnabled=$locationEnabled permissionsOk=${hasBlePermissions()} activeWork=true graceMs=$ENV_BLOCKED_DEBOUNCE_MS",
        )
        statsHandler.postDelayed({
            if (envBlockedDebounceTokenMs != token) return@postDelayed
            val stillBluetoothEnabled = isBluetoothEnabled()
            val stillLocationEnabled = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                isLocationEnabled()
            } else {
                true
            }
            if (stillBluetoothEnabled && stillLocationEnabled) {
                logServiceEvent(
                    label = "BLE env blocked debounce cancelled",
                    message = "trigger=$trigger bluetoothEnabled=$stillBluetoothEnabled locationEnabled=$stillLocationEnabled reason=env_recovered",
                )
                return@postDelayed
            }
            if (hasActiveTransportWork() && envBlockedDebounceAttempts < ENV_BLOCKED_DEBOUNCE_MAX_ATTEMPTS) {
                logServiceEvent(
                    label = "BLE env blocked debounce cancelled",
                    message = "trigger=$trigger bluetoothEnabled=$stillBluetoothEnabled locationEnabled=$stillLocationEnabled reason=active_work_still_present attempt=$envBlockedDebounceAttempts",
                )
                // Give the active GATT/write lane one more short window before stopping.
                scheduleEnvBlockedDebounce(
                    trigger = "env_blocked_debounce_recheck:$trigger",
                    bluetoothEnabled = stillBluetoothEnabled,
                    locationEnabled = stillLocationEnabled,
                )
                return@postDelayed
            }
            envBlockedDebounceAttempts = 0
            logServiceEvent(
                label = "BLE env blocked debounce stop",
                message = "trigger=$trigger bluetoothEnabled=$stillBluetoothEnabled locationEnabled=$stillLocationEnabled permissionsOk=${hasBlePermissions()} activeWork=false",
            )
            stopMeshAndService()
        }, ENV_BLOCKED_DEBOUNCE_MS)
        return true
    }

    private fun evaluateMeshState(forceHardRestart: Boolean, trigger: String) {
        val bluetoothEnabled = isBluetoothEnabled()
        val locationEnabled = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) isLocationEnabled() else true
        val canStart = canStartMesh(this)
        val stableDeviceId = BleConfig.advertisedDeviceId ?: "unknown"
        logServiceEvent(
            label = "BLE service evaluate state",
            message = "trigger=$trigger force_hard_restart=$forceHardRestart is_running=$isRunning foreground=$foregroundStarted service_running=${SERVICE_RUNNING.get()} mesh_running=${MESH_RUNNING.get()} bt_enabled=$bluetoothEnabled location_enabled=$locationEnabled can_start=$canStart stableDeviceId=$stableDeviceId advertisedDeviceId=$stableDeviceId helloPeerId=$stableDeviceId",
        )
        if (!foregroundStarted) return
        if (bluetoothEnabled && locationEnabled) {
            envBlockedDebounceTokenMs = 0L
            envBlockedDebounceAttempts = 0
        }
        if (!bluetoothEnabled || !locationEnabled) {
            if (scheduleEnvBlockedDebounce(trigger, bluetoothEnabled, locationEnabled)) {
                logServiceEvent(
                    label = "BLE service evaluate decision",
                    message = "trigger=$trigger action=delay_stop reason=env_blocked_debounce",
                )
                updateNotification()
                return
            }
            logServiceEvent(
                label = "BLE service evaluate decision",
                message = "trigger=$trigger action=stop_service reason=env_blocked",
            )
            stopMeshAndService()
            return
        }
        val now = System.currentTimeMillis()
        if (lastBluetoothOnAtMs > 0L) {
            val ageMs = now - lastBluetoothOnAtMs
            if (ageMs in 0 until BT_ON_START_DELAY_MS && !btOnDelayScheduled) {
                btOnDelayScheduled = true
                logServiceEvent(
                    label = "BLE service evaluate decision",
                    message = "trigger=$trigger action=delay_restart reason=bt_on_grace age_ms=$ageMs",
                )
                statsHandler.postDelayed({
                    btOnDelayScheduled = false
                    evaluateMeshState(forceHardRestart = true, trigger = "bt_on_delay_elapsed")
                }, BT_ON_START_DELAY_MS - ageMs)
                return
            }
        }
        if (!canStart) {
            logServiceEvent(
                label = "BLE service evaluate decision",
                message = "trigger=$trigger action=stop_mesh reason=cannot_start",
            )
            stopMeshInternal()
            updateNotification()
            return
        }
        if (forceHardRestart && now - lastRecoveryAtMs >= RECOVERY_COOLDOWN_MS && isRunning) {
            logServiceEvent(
                label = "BLE service evaluate decision",
                message = "trigger=$trigger action=hard_restart reason=forced_recovery",
            )
            lastRecoveryAtMs = now
            BleMeshStatus.lastStallRecoveryAtMs = now
            Log.i(TAG, "Hard-restarting BLE mesh after environment recovery")
            resetTransportStallState()
            meshTransport?.hardRestart()
            MESH_RUNNING.set(true)
            BleMeshStatus.notifyChanged()
            startTrafficUpdates()
            meshTransport?.let { AppBatcher.releaseDeferred(it, reason = "mesh_recovered_hard_restart") }
            OutboundBatchDispatcher.kick(this, reason = "mesh_recovered_hard_restart")
            return
        }
        if (!isRunning) {
            val stableDeviceId = BleConfig.advertisedDeviceId ?: "unknown"
            logServiceEvent(
                label = "BLE service evaluate decision",
                message = "trigger=$trigger action=start_transport stableDeviceId=$stableDeviceId advertisedDeviceId=$stableDeviceId helloPeerId=$stableDeviceId",
            )
            resetTransportStallState()
            meshTransport?.start()
            isRunning = true
            MESH_RUNNING.set(true)
            BleMeshStatus.notifyChanged()
            startTrafficUpdates()
            meshTransport?.let { AppBatcher.releaseDeferred(it, reason = "mesh_recovered") }
            OutboundBatchDispatcher.kick(this, reason = "mesh_recovered")
            Log.i(TAG, "BLE mesh started")
            updateNotification()
        } else {
            logServiceEvent(
                label = "BLE service evaluate decision",
                message = "trigger=$trigger action=noop reason=already_running",
            )
        }
    }

    private fun blockedReasonText(): String? {
        return when {
            !hasBlePermissions() -> getString(R.string.mesh_start_need_permission)
            !isLocationEnabled() -> getString(R.string.mesh_start_need_location)
            !isBluetoothEnabled() -> getString(R.string.mesh_start_need_bluetooth)
            else -> null
        }
    }

    private fun isScanFresh(nowMs: Long): Boolean {
        val last = if (BleMeshStatus.lastScanCallbackAtMs > 0L) {
            BleMeshStatus.lastScanCallbackAtMs
        } else {
            BleMeshStatus.lastScanStartAtMs
        }
        if (last == 0L) return false
        return nowMs - last < SCAN_FRESH_WINDOW_MS
    }

    private fun isAdvertiseFresh(nowMs: Long): Boolean {
        val last = BleMeshStatus.lastAdvertiseStartedAtMs
        if (last == 0L) return false
        return nowMs - last < ADVERTISE_FRESH_WINDOW_MS
    }

    private fun hasBlePermissions(): Boolean {
        return BlePermissionHelper.requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(this, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
    }

    private fun isTransportStalled(nowMs: Long): Boolean {
        if (!isRunning) return false
        if (!isScanFresh(nowMs)) return false
        val transportStartAtMs = BleMeshStatus.lastTransportStartAtMs
        if (transportStartAtMs > 0L && nowMs - transportStartAtMs < TRANSPORT_STALL_WARMUP_MS) {
            return false
        }
        val nearby = meshTransport?.nearbyDeviceCount(nowMs) ?: 0
        if (nearby <= 0) return false
        val hasPendingData = meshTransport?.hasPendingDataTraffic() == true
        if (!hasPendingData) return false
        val progressAgeMs = transportProgressAgeMs(nowMs)
        if (progressAgeMs < TRANSPORT_STALL_HARD_RESTART_MS) return false
        val lastDetected = BleMeshStatus.lastStallDetectedAtMs
        if (nowMs - lastDetected >= STALL_DETECTED_LOG_COOLDOWN_MS) {
            BleMeshStatus.lastStallDetectedAtMs = nowMs
            ActivityLog.add(
                label = "BLE mesh transport stalled",
                source = "mesh",
                message = "nearby=$nearby has_pending_data=$hasPendingData last_progress_age_ms=$progressAgeMs",
            )
        }
        return true
    }

    private fun isStartupStalled(nowMs: Long): Boolean {
        if (!isRunning) return false
        if (!startupStallMonitoringActive) return false
        if (isScanFresh(nowMs) || isAdvertiseFresh(nowMs)) return false
        if (BleMeshStatus.lastTransportProgressAtMs > 0L || BleMeshStatus.lastGattConnectedAtMs > 0L) {
            return false
        }
        if (!BleMeshStatus.lastMeshStartStage.equals("called") &&
            !BleMeshStatus.lastMeshStartStage.equals("started")
        ) {
            return false
        }
        val startupAgeMs = startupAgeMs(nowMs)
        if (startupAgeMs < STARTUP_STALL_HARD_RESTART_MS) return false
        val lastDetected = BleMeshStatus.lastStartupStallDetectedAtMs
        if (nowMs - lastDetected >= STALL_DETECTED_LOG_COOLDOWN_MS) {
            BleMeshStatus.lastStartupStallDetectedAtMs = nowMs
            ActivityLog.add(
                label = "BLE mesh startup stalled",
                source = "mesh",
                message = "stage=${BleMeshStatus.lastMeshStartStage ?: "unknown"} reason=${BleMeshStatus.lastMeshStartReason ?: "none"} startup_age_ms=$startupAgeMs",
            )
        }
        return true
    }

    private fun maybeResetStartupRecoveryState() {
        val now = System.currentTimeMillis()
        if (isScanFresh(now)) {
            scanStallRecoveryPending = false
        }
        if (isTransportHealthy(now)) {
            transportStallRecoveryPending = false
            transportStallConfirmTokenMs = 0L
        }
        val startupHealthy = isScanFresh(now) ||
            isAdvertiseFresh(now) ||
            BleMeshStatus.lastTransportProgressAtMs > 0L ||
            BleMeshStatus.lastGattConnectedAtMs > 0L
        if (!startupHealthy) return
        startupStallMonitoringActive = false
        consecutiveStartupStallRecoveries = 0
    }

    private fun resetTransportStallState() {
        val now = System.currentTimeMillis()
        startupStallMonitoringActive = true
        BleMeshStatus.lastTransportStartAtMs = now
        BleMeshStatus.lastTransportProgressAtMs = 0L
        BleMeshStatus.lastGattConnectedAtMs = 0L
        BleMeshStatus.lastStallDetectedAtMs = 0L
    }

    private fun isTransportHealthy(nowMs: Long): Boolean {
        val lastProgress = BleMeshStatus.lastTransportProgressAtMs
        if (lastProgress > 0L && nowMs - lastProgress < TRANSPORT_STALL_HARD_RESTART_MS) {
            return true
        }
        val lastConnected = BleMeshStatus.lastGattConnectedAtMs
        if (lastConnected > 0L && nowMs - lastConnected < TRANSPORT_STALL_HARD_RESTART_MS) {
            return true
        }
        return false
    }

    private fun transportProgressAgeMs(nowMs: Long): Long {
        val lastProgress = BleMeshStatus.lastTransportProgressAtMs
        if (lastProgress > 0L) {
            return nowMs - lastProgress
        }
        val lastConnected = BleMeshStatus.lastGattConnectedAtMs
        if (lastConnected > 0L) {
            return nowMs - lastConnected
        }
        val lastScan = if (BleMeshStatus.lastScanCallbackAtMs > 0L) {
            BleMeshStatus.lastScanCallbackAtMs
        } else {
            BleMeshStatus.lastScanStartAtMs
        }
        return if (lastScan > 0L) nowMs - lastScan else Long.MAX_VALUE
    }

    private fun startupAgeMs(nowMs: Long): Long {
        val startedAtMs = BleMeshStatus.lastMeshStartAtMs
        return if (startedAtMs > 0L) nowMs - startedAtMs else Long.MAX_VALUE
    }

}
