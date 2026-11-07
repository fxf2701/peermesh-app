package app.peermesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.peermesh.ble.BleForegroundService
import app.peermesh.ble.BleMeshStatus
import app.peermesh.ble.BleMeshWorkManager
import app.peermesh.config.EnvironmentLoader
import app.peermesh.relay.ManagedRelayAccessCoordinator
import app.peermesh.relay.RelaySyncSettings
import app.peermesh.relay.RelaySyncUiEvaluator
import java.util.WeakHashMap

/**
 * Retains automatic Nearby recovery and system-requirement prompts after the
 * production app-bar transport controls were removed. It no longer owns views
 * or exposes manual transport actions.
 */
object StatusIndicatorUpdater {
    private var lastLocationReminderMs: Long = 0L
    private var lastBleStopWarningMs: Long = 0L

    enum class State { ON, OFF, PAUSED, ERROR }

    fun bleStateForUi(context: Context): State {
        if (!BleForegroundService.isMeshRunning()) return State.OFF
        val nowMs = System.currentTimeMillis()
        if (isBleMeshError(nowMs)) return State.ERROR
        val nearby = BleMeshStatus.nearbyTotalForUi(nowMs) {
            app.peermesh.ble.BleMeshRuntime.get(context).nearbyDeviceCount(nowMs)
        }
        if (nearby > 0) return State.ON
        if (isBleMeshTransitional(nowMs)) return State.PAUSED
        return State.ERROR
    }

    fun colorForState(context: Context, state: State): Int {
        return ContextCompat.getColor(
            context,
            when (state) {
                State.ON -> R.color.status_on
                State.PAUSED -> R.color.status_paused
                State.ERROR -> R.color.status_error
                State.OFF -> R.color.status_off
            },
        )
    }

    fun start(activity: androidx.appcompat.app.AppCompatActivity) {
        if (observers.containsKey(activity)) return
        ensureAutomaticNearbyRuntime(activity)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                activity.runOnUiThread {
                    when (intent.action) {
                        android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            val state = intent.getIntExtra(
                                android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                                android.bluetooth.BluetoothAdapter.ERROR,
                            )
                            when (state) {
                                android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                                    if (BleForegroundService.isServiceRunning() || BleForegroundService.isMeshRunning()) {
                                        showBluetoothUnavailableDialog(activity)
                                    }
                                }
                                android.bluetooth.BluetoothAdapter.STATE_ON -> ensureAutomaticNearbyRuntime(activity)
                            }
                        }
                        android.location.LocationManager.MODE_CHANGED_ACTION,
                        android.location.LocationManager.PROVIDERS_CHANGED_ACTION -> {
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                                if (!isLocationEnabled(activity)) {
                                    if (BleForegroundService.isServiceRunning() || BleForegroundService.isMeshRunning()) {
                                        showLocationRequiredDialog(activity)
                                    }
                                } else {
                                    ensureAutomaticNearbyRuntime(activity)
                                }
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(android.location.LocationManager.MODE_CHANGED_ACTION)
            addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(receiver, filter)
        }
        observers[activity] = receiver
    }

    fun stop(activity: androidx.appcompat.app.AppCompatActivity) {
        val receiver = observers.remove(activity) ?: return
        runCatching { activity.unregisterReceiver(receiver) }
    }

    fun showLocationRequiredDialog(activity: androidx.appcompat.app.AppCompatActivity) {
        if (activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || isLocationEnabled(activity)) return
        val now = System.currentTimeMillis()
        if (now - lastLocationReminderMs < LOCATION_REMINDER_COOLDOWN_MS) return
        lastLocationReminderMs = now
        AlertDialog.Builder(activity)
            .setTitle(locationDialogTitle(activity))
            .setMessage(R.string.ble_location_dialog_body)
            .setPositiveButton(R.string.action_turn_on_location) { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    fun showBluetoothUnavailableDialog(activity: androidx.appcompat.app.AppCompatActivity) {
        if (activity.isFinishing || isBluetoothEnabled(activity)) return
        val now = System.currentTimeMillis()
        if (now - lastBleStopWarningMs < BLE_STOP_WARNING_COOLDOWN_MS) return
        lastBleStopWarningMs = now
        val body = if (isCloudPathAvailable(activity)) {
            R.string.ble_mesh_stop_bluetooth_cloud_active_body
        } else {
            R.string.ble_mesh_stop_bluetooth_cloud_unavailable_body
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.ble_mesh_stop_bluetooth_title)
            .setMessage(body)
            .setPositiveButton(R.string.action_open_bluetooth) { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    private fun ensureAutomaticNearbyRuntime(context: Context) {
        if (!PeerMeshPrefs.isBleMeshUserEnabled(context)) return
        if (BleForegroundService.isServiceRunning()) return
        if (!BleForegroundService.canStartMesh(context)) return
        BleForegroundService.startMesh(
            context,
            caller = "StatusIndicatorUpdater.ensureAutomaticNearbyRuntime",
            reason = "system_requirements_ready",
        )
        BleMeshWorkManager.enqueue(context)
    }

    private fun locationDialogTitle(activity: androidx.appcompat.app.AppCompatActivity): Int {
        val communicationScreen = activity is GroupChatActivity ||
            activity is NearbyRoomsActivity ||
            activity is NearbyRoomActivity
        return if (communicationScreen && !isCloudPathAvailable(activity)) {
            R.string.nearby_communication_paused_title
        } else {
            R.string.ble_location_dialog_title
        }
    }

    private fun isBleMeshTransitional(nowMs: Long): Boolean {
        if (nowMs - BleMeshStatus.lastAutoRecoverAtMs < BLE_RECOVERING_WINDOW_MS) return true
        if (nowMs - BleMeshStatus.lastStartupRecoveryAtMs < BLE_RECOVERING_WINDOW_MS) return true
        if (nowMs - BleMeshStatus.lastStallRecoveryAtMs < BLE_RECOVERING_WINDOW_MS) return true
        if (isBleMeshScanFresh(nowMs) || isBleMeshAdvertiseFresh(nowMs)) return true
        val lastStartAtMs = BleMeshStatus.lastMeshStartAtMs
        if (lastStartAtMs > 0L && nowMs - lastStartAtMs < BLE_STARTUP_STALL_MS) return true
        if (BleMeshStatus.lastAdaptiveState == "idle" &&
            nowMs - BleMeshStatus.lastAdaptiveStateAtMs < BLE_RECOVERING_WINDOW_MS
        ) return true
        return !BleMeshStatus.lastScanStartSkippedReason.isNullOrBlank() &&
            nowMs - BleMeshStatus.lastScanStartSkippedAtMs < BLE_SCAN_SKIPPED_WINDOW_MS
    }

    private fun isBleMeshError(nowMs: Long): Boolean {
        if (BleMeshStatus.lastStartupStallDetectedAtMs > BleMeshStatus.lastStartupRecoveryAtMs &&
            nowMs - BleMeshStatus.lastStartupStallDetectedAtMs < BLE_ERROR_WINDOW_MS
        ) return true
        if (BleMeshStatus.lastStallDetectedAtMs > BleMeshStatus.lastStallRecoveryAtMs &&
            nowMs - BleMeshStatus.lastStallDetectedAtMs < BLE_ERROR_WINDOW_MS
        ) return true
        val startedAt = BleMeshStatus.lastMeshStartAtMs
        return startedAt > 0L && nowMs - startedAt >= BLE_STARTUP_STALL_MS &&
            !isBleMeshTransitional(nowMs) &&
            !isBleMeshScanFresh(nowMs) &&
            !isBleMeshAdvertiseFresh(nowMs)
    }

    private fun isBleMeshScanFresh(nowMs: Long): Boolean {
        val last = BleMeshStatus.lastScanCallbackAtMs.takeIf { it > 0L }
            ?: BleMeshStatus.lastScanStartAtMs
        return last > 0L && nowMs - last < BLE_SCAN_FRESH_WINDOW_MS
    }

    private fun isBleMeshAdvertiseFresh(nowMs: Long): Boolean {
        return BleMeshStatus.lastAdvertiseStartedAtMs > 0L &&
            nowMs - BleMeshStatus.lastAdvertiseStartedAtMs < BLE_ADVERTISE_FRESH_WINDOW_MS
    }

    private fun isBluetoothEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        return manager.adapter?.isEnabled == true
    }

    private fun isLocationEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun isCloudPathAvailable(context: Context): Boolean {
        if (!PeerMeshCapabilities.canUseRelaySync || !RelaySyncSettings.isEnabled(context)) return false
        if (!isOnline(context)) return false
        val environment = EnvironmentLoader.load()
        val relayUrls = RelaySyncSettings.resolveRelayBaseUrls(context, environment.relayBaseUrl)
        return RelaySyncUiEvaluator.hasConfiguredPath(
            directRelayCount = relayUrls.size,
            managedConfigured = ManagedRelayAccessCoordinator(context, environment).isConfigured(),
        )
    }

    private fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            manager.activeNetworkInfo?.isConnected == true
        }
    }

    private val observers = WeakHashMap<androidx.appcompat.app.AppCompatActivity, BroadcastReceiver>()

    private const val BLE_ERROR_WINDOW_MS = 60_000L
    private const val BLE_RECOVERING_WINDOW_MS = 60_000L
    private const val BLE_SCAN_FRESH_WINDOW_MS = 60_000L
    private const val BLE_ADVERTISE_FRESH_WINDOW_MS = 5 * 60_000L
    private const val BLE_SCAN_SKIPPED_WINDOW_MS = 30_000L
    private const val BLE_STARTUP_STALL_MS = 15_000L
    private const val LOCATION_REMINDER_COOLDOWN_MS = 30_000L
    private const val BLE_STOP_WARNING_COOLDOWN_MS = 30_000L
}
