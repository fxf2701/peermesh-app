package app.peermesh

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import app.peermesh.ble.BleMeshStatus
import app.peermesh.config.EnvironmentLoader
import app.peermesh.relay.ManagedRelayAccessCoordinator
import app.peermesh.relay.RelaySyncUiEvaluator
import app.peermesh.relay.RelaySyncService
import app.peermesh.relay.RelaySyncSettings
import app.peermesh.transport.relay.RelayTrafficStats
import app.peermesh.transport.relay.TrafficSnapshot
import app.peermesh.transport.ble.BleTrafficStats
import com.google.android.material.navigation.NavigationView
import java.util.Date

class StatisticsActivity : AppCompatActivity() {
    private val statusHandler = Handler(Looper.getMainLooper())
    private var relayStatusRunnable: Runnable? = null
    private var meshStatusListener: (() -> Unit)? = null
    private var meshStatusRefresh: Runnable? = null
    private var meshStateReceiver: BroadcastReceiver? = null
    private var statusRunnable: Runnable? = null
    private var lastRelaySnapshot: TrafficSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statistics)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)

        NetworkServiceToggleUi.bind(this, findViewById(R.id.network_mesh_enabled), true) { updateNetworkStatus() }
        NetworkServiceToggleUi.bind(this, findViewById(R.id.network_cloud_enabled), false) { updateNetworkStatus() }
        headerTitle.text = getString(R.string.page_title_statistics)
        headerMenu.setOnClickListener {
            drawer.openDrawer(GravityCompat.END)
        }

        val headerView = navView.getHeaderView(0)
        ViewCompat.setOnApplyWindowInsetsListener(headerView) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            view.setPadding(
                view.paddingLeft,
                topInset + resources.getDimensionPixelSize(R.dimen.drawer_statusbar_padding),
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        PeerMeshFeatureUi.applyDrawerCapabilities(navView)
        navView.setCheckedItem(R.id.menu_statistics)
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_home -> startActivity(Intent(this, MainActivity::class.java))
                R.id.menu_nearby_rooms -> startActivity(Intent(this, NearbyRoomsActivity::class.java))
                R.id.menu_trails -> PeerMeshFeatureUi.openTrailOrPaywall(this)
                R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.menu_test -> startActivity(Intent(this, TestActivity::class.java))
                R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))
                R.id.menu_statistics -> {}
            }
            drawer.closeDrawer(GravityCompat.END)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
        NetworkServiceToggleUi.bind(this, findViewById(R.id.network_mesh_enabled), true) { updateNetworkStatus() }
        NetworkServiceToggleUi.bind(this, findViewById(R.id.network_cloud_enabled), false) { updateNetworkStatus() }
        updateNetworkStatus()
        startMeshStatusUpdates()
        startRelayStatusUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopRelayStatusUpdates()
        stopMeshStatusUpdates()
        StatusIndicatorUpdater.stop(this)
    }

    private fun updateNetworkStatus() {
        updateMeshNetworkStatus()
        updateInternetRelayStatus()
    }

    private fun updateMeshNetworkStatus() {
        val snapshot = BleTrafficStats.snapshot()
        val nowMs = System.currentTimeMillis()
        val bluetoothReady = isBluetoothReady()
        val nearbyCount = nearbyPeerCount(nowMs)
        val lastActivity = formatLastActivity(snapshot.lastScanAtMs)
        val meshState = StatusIndicatorUpdater.bleStateForUi(this)

        val statusLabel = findViewById<TextView>(R.id.mesh_status_label)
        val statusSummary = findViewById<TextView>(R.id.mesh_status_summary)
        val statusDot = findViewById<TextView>(R.id.mesh_status_dot)

        val status = when (meshState) {
            StatusIndicatorUpdater.State.ON -> StatusUi(
                label = getString(R.string.network_status_active),
                summary = getString(R.string.network_status_mesh_active_summary),
                color = StatusIndicatorUpdater.colorForState(this, meshState),
            )
            StatusIndicatorUpdater.State.PAUSED -> StatusUi(
                label = if (isMeshRestarting(nowMs) || isMeshScanning(nowMs)) {
                    getString(R.string.network_status_searching)
                } else {
                    getString(R.string.network_status_waiting)
                },
                summary = if (isMeshRestarting(nowMs)) {
                    getString(R.string.network_status_mesh_restarting_summary)
                } else if (isMeshScanning(nowMs)) {
                    getString(R.string.network_status_mesh_searching_summary)
                } else {
                    getString(R.string.network_status_mesh_waiting_summary)
                },
                color = StatusIndicatorUpdater.colorForState(this, meshState),
            )
            StatusIndicatorUpdater.State.ERROR -> StatusUi(
                label = getString(R.string.network_status_offline),
                summary = getString(R.string.network_status_mesh_error_summary),
                color = StatusIndicatorUpdater.colorForState(this, meshState),
            )
            StatusIndicatorUpdater.State.OFF -> StatusUi(
                label = if (!bluetoothReady) {
                    getString(R.string.network_status_offline)
                } else {
                    getString(R.string.network_status_waiting)
                },
                summary = if (!bluetoothReady) {
                    getString(R.string.network_status_mesh_bluetooth_off)
                } else {
                    getString(R.string.network_status_mesh_off_summary)
                },
                color = StatusIndicatorUpdater.colorForState(this, meshState),
            )
        }

        statusLabel.text = status.label
        statusSummary.text = status.summary
        statusDot.setTextColor(status.color)

        findViewById<TextView>(R.id.mesh_nearby_peers_value).text =
            resources.getQuantityString(
                R.plurals.network_status_nearby_peers_count,
                nearbyCount,
                nearbyCount
            )

        findViewById<TextView>(R.id.mesh_last_activity_value).text = lastActivity

        findViewById<TextView>(R.id.mesh_bluetooth_value).text =
            if (bluetoothReady) {
                getString(R.string.network_status_available)
            } else {
                getString(R.string.network_status_unavailable)
            }

        findViewById<TextView>(R.id.mesh_relay_mode_value).text =
            if (bluetoothReady) {
                getString(R.string.network_status_mesh_relay_enabled)
            } else {
                getString(R.string.network_status_mesh_relay_paused)
            }
    }

    private fun updateInternetRelayStatus() {
        val relayAvailable = PeerMeshCapabilities.canUseRelaySync
        val relayEnabled = relayAvailable && RelaySyncSettings.isEnabled(this)
        val internetOnline = isInternetOnline()
        val environment = EnvironmentLoader.load()
        val relayUrls = RelaySyncSettings.resolveRelayBaseUrls(
            this,
            environment.relayBaseUrl,
        )
        val managedConfigured = ManagedRelayAccessCoordinator(this, environment).isConfigured()
        val hasRelayNode = relayUrls.isNotEmpty()
        val hasConfiguredPath = RelaySyncUiEvaluator.hasConfiguredPath(
            directRelayCount = relayUrls.size,
            managedConfigured = managedConfigured,
        )
        val relaySyncRunning = relayEnabled && RelaySyncService.isSyncRunning()
        val relayConnected = relayEnabled && internetOnline && hasRecentRelaySuccess()

        val status = when {
            !relayAvailable -> StatusUi(
                label = getString(R.string.network_status_unavailable),
                summary = getString(R.string.network_status_relay_unavailable_summary),
                color = ContextCompat.getColor(this, R.color.status_off),
            )
            !relayEnabled -> StatusUi(
                label = getString(R.string.network_status_off),
                summary = getString(R.string.network_status_relay_off_summary),
                color = ContextCompat.getColor(this, R.color.status_off),
            )
            relayConnected -> StatusUi(
                label = getString(R.string.network_status_connected),
                summary = getString(R.string.network_status_relay_connected_summary),
                color = ContextCompat.getColor(this, R.color.status_on),
            )
            internetOnline && hasConfiguredPath -> StatusUi(
                label = getString(R.string.network_status_sync_ready_label),
                summary = if (managedConfigured && !hasRelayNode) {
                    getString(R.string.network_status_relay_registry_managed_summary)
                } else {
                    getString(R.string.network_status_relay_sync_ready_summary)
                },
                color = ContextCompat.getColor(this, R.color.status_on),
            )
            internetOnline -> StatusUi(
                label = getString(R.string.network_status_internet_available),
                summary = getString(R.string.network_status_relay_internet_available_summary),
                color = ContextCompat.getColor(this, R.color.status_paused),
            )
            else -> StatusUi(
                label = getString(R.string.network_status_offline_mode),
                summary = getString(R.string.network_status_relay_offline_summary),
                color = ContextCompat.getColor(this, R.color.status_error),
            )
        }

        val statusLabel = findViewById<TextView>(R.id.relay_status_label)
        val statusSummary = findViewById<TextView>(R.id.relay_status_summary)
        val statusDot = findViewById<TextView>(R.id.relay_status_dot)

        statusLabel.text = status.label
        statusSummary.text = status.summary
        statusDot.setTextColor(status.color)

        findViewById<TextView>(R.id.relay_internet_value).text =
            if (internetOnline) {
                getString(R.string.network_status_available)
            } else {
                getString(R.string.network_status_unavailable)
            }

        findViewById<TextView>(R.id.relay_sync_value).text =
            when {
                relayConnected -> getString(R.string.network_status_sync_connected)
                relayEnabled && internetOnline && hasRelayNode -> resources.getQuantityString(
                    R.plurals.network_status_sync_ready_with_relays,
                    relayUrls.size,
                    relayUrls.size,
                )
                relayEnabled && internetOnline && managedConfigured ->
                    getString(R.string.network_status_sync_registry_managed)
                relayEnabled && internetOnline -> getString(R.string.network_status_sync_no_relay)
                relayEnabled -> getString(R.string.network_status_sync_waiting_for_internet)
                else -> getString(R.string.network_status_sync_off)
            }

        findViewById<TextView>(R.id.relay_delivery_value).text =
            if (relayConnected || (relaySyncRunning && hasConfiguredPath)) {
                getString(R.string.network_status_delivery_relay_ready)
            } else {
                getString(R.string.network_status_delivery_local_first)
            }

        updateRelayTraffic()
    }

    private fun nearbyPeerCount(nowMs: Long): Int {
        return try {
            app.peermesh.ble.BleMeshStatus.nearbyTotalForUi(nowMs) {
                app.peermesh.ble.BleMeshRuntime.get(this).nearbyDeviceCount(nowMs)
            }
        } catch (_: SecurityException) {
            0
        } catch (_: Exception) {
            0
        }
    }

    private fun isBluetoothReady(): Boolean {
        return try {
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter
            val hasBleFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            hasBleFeature && adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isInternetOnline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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

    private fun hasRecentRelaySuccess(): Boolean {
        val lastSuccessMs = RelaySyncSettings.lastSuccessMs(this)
        if (lastSuccessMs <= 0L) return false
        return System.currentTimeMillis() - lastSuccessMs <= RELAY_CONNECTED_MAX_AGE_MS
    }

    private fun updateRelayTraffic() {
        val trafficView = findViewById<TextView>(R.id.relay_traffic_value)
        val current = RelayTrafficStats.snapshot()
        val last = lastRelaySnapshot
        lastRelaySnapshot = current

        if (current.uploaded <= 0L && current.downloaded <= 0L) {
            trafficView.visibility = View.GONE
            return
        }

        val upRate = if (last == null) {
            0L
        } else {
            val deltaMs = (current.timestampMs - last.timestampMs).coerceAtLeast(1L)
            val delta = (current.uploaded - last.uploaded).coerceAtLeast(0L)
            delta * 1000L / deltaMs
        }
        val downRate = if (last == null) {
            0L
        } else {
            val deltaMs = (current.timestampMs - last.timestampMs).coerceAtLeast(1L)
            val delta = (current.downloaded - last.downloaded).coerceAtLeast(0L)
            delta * 1000L / deltaMs
        }

        trafficView.visibility = View.VISIBLE
        trafficView.text = getString(
            R.string.network_status_relay_traffic_format,
            formatBytes(current.downloaded),
            formatRate(downRate),
            formatBytes(current.uploaded),
            formatRate(upRate),
        )
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

    private fun formatLastActivity(lastActivityMs: Long): String {
        if (lastActivityMs <= 0L) return getString(R.string.network_status_no_activity_yet)
        val nowMs = System.currentTimeMillis()
        val elapsedSeconds = ((nowMs - lastActivityMs) / 1000L).coerceAtLeast(0L)

        return when {
            elapsedSeconds < 10L -> getString(R.string.network_status_just_now)
            elapsedSeconds < 60L -> getString(
                R.string.network_status_seconds_ago,
                elapsedSeconds
            )
            elapsedSeconds < 60L * 60L -> getString(
                R.string.network_status_minutes_ago,
                elapsedSeconds / 60L
            )
            else -> DateFormat.getTimeFormat(this).format(Date(lastActivityMs))
        }
    }

    private fun startMeshStatusUpdates() {
        if (meshStatusListener != null) return
        val refresh = Runnable { updateMeshNetworkStatus() }
        val listener: () -> Unit = {
            statusHandler.removeCallbacks(refresh)
            statusHandler.postDelayed(refresh, MESH_STATUS_UI_DEBOUNCE_MS)
        }
        BleMeshStatus.addListener(listener)
        meshStatusRefresh = refresh
        meshStatusListener = listener

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateMeshNetworkStatus()
            }
        }
        val filter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(android.location.LocationManager.MODE_CHANGED_ACTION)
            addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        meshStateReceiver = receiver
    }

    private fun stopMeshStatusUpdates() {
        meshStatusRefresh?.let { statusHandler.removeCallbacks(it) }
        meshStatusRefresh = null
        meshStatusListener?.let { BleMeshStatus.removeListener(it) }
        meshStatusListener = null
        meshStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        meshStateReceiver = null
    }

    private fun startRelayStatusUpdates() {
        if (relayStatusRunnable != null) return
        val runnable = Runnable {
            updateInternetRelayStatus()
            relayStatusRunnable?.let { statusHandler.postDelayed(it, STATUS_UPDATE_MS) }
        }
        relayStatusRunnable = runnable
        statusHandler.postDelayed(runnable, STATUS_UPDATE_MS)
    }

    private fun stopRelayStatusUpdates() {
        relayStatusRunnable?.let { statusHandler.removeCallbacks(it) }
        relayStatusRunnable = null
        lastRelaySnapshot = null
    }

    private fun isMeshScanning(nowMs: Long): Boolean {
        val lastScanAtMs = if (BleMeshStatus.lastScanCallbackAtMs > 0L) {
            BleMeshStatus.lastScanCallbackAtMs
        } else {
            BleMeshStatus.lastScanStartAtMs
        }
        if (lastScanAtMs > 0L && nowMs - lastScanAtMs < BLE_SCAN_FRESH_WINDOW_MS) {
            return true
        }
        val lastAdvertiseAtMs = BleMeshStatus.lastAdvertiseStartedAtMs
        return lastAdvertiseAtMs > 0L && nowMs - lastAdvertiseAtMs < BLE_ADVERTISE_FRESH_WINDOW_MS
    }

    private fun isMeshRestarting(nowMs: Long): Boolean {
        return nowMs - BleMeshStatus.lastAutoRecoverAtMs < BLE_RECOVERING_WINDOW_MS ||
            nowMs - BleMeshStatus.lastStartupRecoveryAtMs < BLE_RECOVERING_WINDOW_MS ||
            nowMs - BleMeshStatus.lastStallRecoveryAtMs < BLE_RECOVERING_WINDOW_MS
    }

    private data class StatusUi(
        val label: String,
        val summary: String,
        val color: Int,
    )

    companion object {
        private const val STATUS_UPDATE_MS = 2_000L
        private const val MESH_STATUS_UI_DEBOUNCE_MS = 150L
        private const val BLE_RECOVERING_WINDOW_MS = 60_000L
        private const val BLE_SCAN_FRESH_WINDOW_MS = 60_000L
        private const val BLE_ADVERTISE_FRESH_WINDOW_MS = 5 * 60_000L
        private const val RELAY_CONNECTED_MAX_AGE_MS = 5 * 60 * 1000L
    }
}
