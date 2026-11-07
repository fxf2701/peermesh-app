package app.peermesh

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.CheckBox
import android.widget.FrameLayout
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import app.peermesh.ble.BlePermissionHelper
import app.peermesh.crash.CrashPromptController
import com.google.android.material.navigation.NavigationView
import app.peermesh.relay.RelaySyncService
import app.peermesh.relay.RelaySyncSettings
import app.peermesh.relay.RelaySyncLifecycleCoordinator
import app.peermesh.relay.RelaySyncWorkManager
import app.peermesh.relay.SyncMode

class SettingsActivity : AppCompatActivity() {
    private val localUserProfileStore by lazy { LocalUserProfileStore(applicationContext) }
    private val nearbyRoomRepository by lazy { app.peermesh.nearby.NearbyRoomRepository(applicationContext) }
    private var selectedAvatarId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)

        headerTitle.text = getString(R.string.page_title_settings)
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
        navView.setCheckedItem(R.id.menu_settings)
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_home -> startActivity(Intent(this, MainActivity::class.java))
                R.id.menu_nearby_rooms -> startActivity(Intent(this, NearbyRoomsActivity::class.java))
                R.id.menu_trails -> PeerMeshFeatureUi.openTrailOrPaywall(this)
                R.id.menu_test -> startActivity(Intent(this, TestActivity::class.java))
                R.id.menu_statistics -> startActivity(Intent(this, StatisticsActivity::class.java))
                R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))
                R.id.menu_settings -> {}
            }
            drawer.closeDrawer(GravityCompat.END)
            true
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val tooltip = findViewById<View>(R.id.battery_tooltip)
        val batterySettings = findViewById<Button>(R.id.btn_battery_settings)
        val bleStatus = findViewById<TextView>(R.id.ble_permission_status)
        val bleSettingsHint = findViewById<TextView>(R.id.ble_permission_settings_hint)
        val bleLocationStatus = findViewById<TextView>(R.id.ble_location_status)
        val bleLocationSettings = findViewById<Button>(R.id.ble_location_settings)
        val bleGrant = findViewById<Button>(R.id.ble_permission_grant)
        val notificationStatus = findViewById<TextView>(R.id.notification_status)
        val notificationSettings = findViewById<Button>(R.id.notification_settings_button)
        val statusBleSupported = findViewById<TextView>(R.id.status_ble_supported)
        val statusBtEnabled = findViewById<TextView>(R.id.status_bt_enabled)
        val statusBleScanner = findViewById<TextView>(R.id.status_ble_scanner)
        val statusBleAdvertiser = findViewById<TextView>(R.id.status_ble_advertiser)
        val statusBlePermissions = findViewById<TextView>(R.id.status_ble_permissions)
        val statusLocationPermission = findViewById<TextView>(R.id.status_location_permission)
        val statusNearbyPermission = findViewById<TextView>(R.id.status_nearby_permission)
        val statusLocationEnabled = findViewById<TextView>(R.id.status_location_enabled)
        val statusNetworkOnline = findViewById<TextView>(R.id.status_network_online)
        val statusNotifications = findViewById<TextView>(R.id.status_notifications)
        NetworkServiceToggleUi.bind(this, findViewById(R.id.settings_mesh_enabled), true)
        NetworkServiceToggleUi.bind(this, findViewById(R.id.settings_cloud_enabled), false)
        val cloudToBleBridgeEnabled = findViewById<CompoundButton>(R.id.settings_cloud_to_ble_bridge_enabled)
        val syncGroup = findViewById<RadioGroup>(R.id.sync_mode_group)
        val themeGroup = findViewById<RadioGroup>(R.id.theme_group)
        val keepScreenOn = findViewById<CheckBox>(R.id.keep_screen_on_toggle)
        val settingsCrashPromptToggle = findViewById<CheckBox>(R.id.settings_crash_prompt_toggle)
        val settingsFeedbackButton = findViewById<Button>(R.id.settings_feedback_button)
        val profileDisplayNameInput = findViewById<EditText>(R.id.settings_profile_display_name_input)
        val profileDisplayNameError = findViewById<TextView>(R.id.settings_profile_display_name_error)
        val profileAvatarPreviewContainer = findViewById<FrameLayout>(R.id.settings_profile_avatar_preview_container)
        val profileAvatarPreview = findViewById<TextView>(R.id.settings_profile_avatar_preview)
        val profileAvatarValue = findViewById<TextView>(R.id.settings_profile_avatar_value)
        val profileAvatarButton = findViewById<Button>(R.id.settings_profile_avatar_button)
        val profileAvatarError = findViewById<TextView>(R.id.settings_profile_avatar_error)
        val profileSaveButton = findViewById<Button>(R.id.settings_profile_save_button)

        val hidden = prefs.getBoolean(KEY_TOOLTIP_HIDDEN, false)
        tooltip.visibility = if (hidden) View.GONE else View.VISIBLE
        batterySettings.visibility = if (hidden) View.GONE else View.VISIBLE

        batterySettings.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        bleGrant.setOnClickListener {
            if (shouldShowBleRationale()) {
                openAppSettings()
                bleSettingsHint.visibility = View.VISIBLE
            } else {
                ActivityCompat.requestPermissions(this, BlePermissionHelper.requiredPermissions(), REQ_BLE_PERM)
            }
        }
        bleLocationSettings.setOnClickListener {
            openLocationSettings()
        }
        notificationSettings.setOnClickListener {
            handleNotificationCta()
        }

        updateBleStatus(bleStatus, bleSettingsHint, bleLocationStatus, bleLocationSettings, bleGrant)
        updateNotificationStatus(notificationStatus, notificationSettings)
        updateDeviceStatus(
            statusBleSupported,
            statusBtEnabled,
            statusBleScanner,
            statusBleAdvertiser,
            statusBlePermissions,
            statusLocationPermission,
            statusNearbyPermission,
            statusLocationEnabled,
            statusNetworkOnline,
            statusNotifications,
        )

        syncGroup.isEnabled = PeerMeshCapabilities.canUseRelaySync
        for (i in 0 until syncGroup.childCount) {
            syncGroup.getChildAt(i).isEnabled = PeerMeshCapabilities.canUseRelaySync
        }
        cloudToBleBridgeEnabled.isChecked = RelaySyncSettings.isCloudToBleBridgeEnabled(this)
        cloudToBleBridgeEnabled.isEnabled = true
        cloudToBleBridgeEnabled.setOnCheckedChangeListener { _, isChecked ->
            RelaySyncSettings.setCloudToBleBridgeEnabled(this, isChecked)
        }
        when (RelaySyncSettings.load(this)) {
            SyncMode.BATTERY_SAVER -> syncGroup.check(R.id.settings_sync_mode_battery)
            else -> syncGroup.check(R.id.settings_sync_mode_fast)
        }
        syncGroup.setOnCheckedChangeListener { _, checkedId ->
            if (!PeerMeshCapabilities.canUseRelaySync) return@setOnCheckedChangeListener
            val newMode = when (checkedId) {
                R.id.settings_sync_mode_battery -> SyncMode.BATTERY_SAVER
                else -> SyncMode.NORMAL
            }
            RelaySyncSettings.save(this, newMode)
            RelaySyncLifecycleCoordinator.onUserRequestedImmediateSync(this)
        }

        app.peermesh.transport.ble.BleConfig.forwardOpaqueMode = true
        val currentTheme = ThemeConfig.load(this)
        if (currentTheme == ThemeConfig.AppTheme.PEER_MESH_LIGHT) {
            themeGroup.check(R.id.theme_light)
        } else {
            themeGroup.check(R.id.theme_dark)
        }
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val nextTheme = if (checkedId == R.id.theme_light) {
                ThemeConfig.AppTheme.PEER_MESH_LIGHT
            } else {
                ThemeConfig.AppTheme.PEER_MESH_AMBER
            }
            ThemeConfig.save(this, nextTheme)
            recreate()
        }

        val keepOnEnabled = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        keepScreenOn.isChecked = keepOnEnabled
        applyKeepScreenOn(keepOnEnabled)
        keepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, isChecked).apply()
            applyKeepScreenOn(isChecked)
        }

        settingsCrashPromptToggle.isChecked = CrashPromptController.isPromptEnabled(this)
        settingsCrashPromptToggle.setOnCheckedChangeListener { _, isChecked ->
            CrashPromptController.setPromptEnabled(this, isChecked)
        }
        settingsFeedbackButton.setOnClickListener {
            startActivity(Intent(this, FeedbackActivity::class.java))
        }

        val existingProfile = localUserProfileStore.load()
        selectedAvatarId = existingProfile?.avatarId
        if (profileDisplayNameInput.text.isNullOrEmpty()) {
            profileDisplayNameInput.setText(existingProfile?.displayName.orEmpty())
        }
        val profileWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderProfileSection(
                    input = profileDisplayNameInput,
                    displayNameError = profileDisplayNameError,
                    avatarPreviewContainer = profileAvatarPreviewContainer,
                    avatarPreview = profileAvatarPreview,
                    avatarValue = profileAvatarValue,
                    avatarError = profileAvatarError,
                    saveButton = profileSaveButton,
                )
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        profileDisplayNameInput.addTextChangedListener(profileWatcher)
        profileAvatarButton.setOnClickListener {
            LocalUserProfileUi.showAvatarPicker(this, selectedAvatarId) { avatarId ->
                selectedAvatarId = avatarId
                renderProfileSection(
                    input = profileDisplayNameInput,
                    displayNameError = profileDisplayNameError,
                    avatarPreviewContainer = profileAvatarPreviewContainer,
                    avatarPreview = profileAvatarPreview,
                    avatarValue = profileAvatarValue,
                    avatarError = profileAvatarError,
                    saveButton = profileSaveButton,
                )
            }
        }
        findViewById<View>(R.id.settings_profile_avatar_row).setOnClickListener {
            profileAvatarButton.performClick()
        }
        profileSaveButton.setOnClickListener {
            val displayName = profileDisplayNameInput.text?.toString().orEmpty()
            val displayNameErrorCode = LocalUserProfileValidator.validateDisplayName(displayName)
            val avatarId = selectedAvatarId
            if (displayNameErrorCode != null || avatarId.isNullOrBlank()) {
                renderProfileSection(
                    input = profileDisplayNameInput,
                    displayNameError = profileDisplayNameError,
                    avatarPreviewContainer = profileAvatarPreviewContainer,
                    avatarPreview = profileAvatarPreview,
                    avatarValue = profileAvatarValue,
                    avatarError = profileAvatarError,
                    saveButton = profileSaveButton,
                )
                return@setOnClickListener
            }
            val profile = localUserProfileStore.save(displayName, avatarId)
            Thread { nearbyRoomRepository.syncLocalUserProfile(profile) }.start()
            toast(getString(R.string.profile_saved))
            renderProfileSection(
                input = profileDisplayNameInput,
                displayNameError = profileDisplayNameError,
                avatarPreviewContainer = profileAvatarPreviewContainer,
                avatarPreview = profileAvatarPreview,
                avatarValue = profileAvatarValue,
                avatarError = profileAvatarError,
                saveButton = profileSaveButton,
            )
        }
        renderProfileSection(
            input = profileDisplayNameInput,
            displayNameError = profileDisplayNameError,
            avatarPreviewContainer = profileAvatarPreviewContainer,
            avatarPreview = profileAvatarPreview,
            avatarValue = profileAvatarValue,
            avatarError = profileAvatarError,
            saveButton = profileSaveButton,
        )
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
        NetworkServiceToggleUi.bind(this, findViewById(R.id.settings_mesh_enabled), true)
        NetworkServiceToggleUi.bind(this, findViewById(R.id.settings_cloud_enabled), false)
        updateBleStatus(
            findViewById(R.id.ble_permission_status),
            findViewById(R.id.ble_permission_settings_hint),
            findViewById(R.id.ble_location_status),
            findViewById(R.id.ble_location_settings),
            findViewById(R.id.ble_permission_grant),
        )
        updateNotificationStatus(
            findViewById(R.id.notification_status),
            findViewById(R.id.notification_settings_button)
        )
        val profile = localUserProfileStore.load()
        selectedAvatarId = profile?.avatarId ?: selectedAvatarId
        val profileDisplayNameInput = findViewById<EditText>(R.id.settings_profile_display_name_input)
        if (profileDisplayNameInput.text.isNullOrBlank() && profile != null) {
            profileDisplayNameInput.setText(profile.displayName)
        }
        renderProfileSection(
            input = profileDisplayNameInput,
            displayNameError = findViewById(R.id.settings_profile_display_name_error),
            avatarPreviewContainer = findViewById(R.id.settings_profile_avatar_preview_container),
            avatarPreview = findViewById(R.id.settings_profile_avatar_preview),
            avatarValue = findViewById(R.id.settings_profile_avatar_value),
            avatarError = findViewById(R.id.settings_profile_avatar_error),
            saveButton = findViewById(R.id.settings_profile_save_button),
        )
        updateDeviceStatus(
            findViewById(R.id.status_ble_supported),
            findViewById(R.id.status_bt_enabled),
            findViewById(R.id.status_ble_scanner),
            findViewById(R.id.status_ble_advertiser),
            findViewById(R.id.status_ble_permissions),
            findViewById(R.id.status_location_permission),
            findViewById(R.id.status_nearby_permission),
            findViewById(R.id.status_location_enabled),
            findViewById(R.id.status_network_online),
            findViewById(R.id.status_notifications),
        )
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_PERM) {
            updateBleStatus(
                findViewById(R.id.ble_permission_status),
                findViewById(R.id.ble_permission_settings_hint),
                findViewById(R.id.ble_location_status),
                findViewById(R.id.ble_location_settings),
                findViewById(R.id.ble_permission_grant),
            )
        }
        if (requestCode == REQ_NOTIF_PERM) {
            updateNotificationStatus(
                findViewById(R.id.notification_status),
                findViewById(R.id.notification_settings_button)
            )
            updateDeviceStatus(
                findViewById(R.id.status_ble_supported),
                findViewById(R.id.status_bt_enabled),
                findViewById(R.id.status_ble_scanner),
                findViewById(R.id.status_ble_advertiser),
                findViewById(R.id.status_ble_permissions),
                findViewById(R.id.status_location_permission),
                findViewById(R.id.status_nearby_permission),
                findViewById(R.id.status_location_enabled),
                findViewById(R.id.status_network_online),
                findViewById(R.id.status_notifications),
            )
        }
    }

    private fun updateBleStatus(
        statusView: TextView,
        settingsHint: TextView,
        locationStatus: TextView,
        locationSettings: Button,
        grantButton: Button,
    ) {
        val granted = hasBlePermissions()
        statusView.text = if (granted) {
            getString(R.string.ble_permission_granted)
        } else {
            getString(R.string.ble_permission_denied)
        }
        grantButton.visibility = if (granted) View.GONE else View.VISIBLE
        settingsHint.visibility = if (!granted && shouldShowBleRationale()) View.VISIBLE else View.GONE
        val needLocation = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S &&
            !isLocationEnabled()
        locationStatus.visibility = if (needLocation) View.VISIBLE else View.GONE
        locationSettings.visibility = if (needLocation) View.VISIBLE else View.GONE
    }

    private fun hasBlePermissions(): Boolean {
        return BlePermissionHelper.requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNearbyPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
            ).all { perm ->
                ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            false
        }
    }

    private fun shouldShowBleRationale(): Boolean {
        return BlePermissionHelper.requiredPermissions().any { perm ->
            ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            openAppSettings()
        }
    }

    private fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        startActivity(intent)
    }

    private fun isLocationEnabled(): Boolean {
        val manager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun updateNotificationStatus(statusView: TextView, settingsButton: Button) {
        val status = NotificationGate.status(this)
        if (status.enabled) {
            statusView.visibility = View.GONE
            settingsButton.visibility = View.GONE
            return
        }
        statusView.visibility = View.VISIBLE
        settingsButton.visibility = View.VISIBLE
        statusView.text = when (status.reason) {
            NotificationGate.Reason.PERMISSION_MISSING -> getString(R.string.notifications_permission_missing)
            NotificationGate.Reason.CHANNEL_DISABLED -> getString(R.string.notifications_channel_disabled)
            NotificationGate.Reason.OS_DISABLED -> getString(R.string.notifications_disabled_hint)
            NotificationGate.Reason.NONE -> getString(R.string.notifications_disabled_hint)
        }
        settingsButton.text = if (status.needsPermission) {
            getString(R.string.notifications_enable)
        } else {
            getString(R.string.open_notification_settings)
        }
    }

    private fun updateDeviceStatus(
        bleSupportedView: TextView,
        btEnabledView: TextView,
        bleScannerView: TextView,
        bleAdvertiserView: TextView,
        blePermissionsView: TextView,
        locationPermissionView: TextView,
        nearbyPermissionView: TextView,
        locationView: TextView,
        networkView: TextView,
        notificationView: TextView,
    ) {
        val bleSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val manager = getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = manager.adapter
        val btEnabled = adapter?.isEnabled == true
        val scanner = adapter?.bluetoothLeScanner != null
        val advertiser = adapter?.bluetoothLeAdvertiser != null
        val blePerms = hasBlePermissions()
        val locationPerm = hasLocationPermission()
        val nearbyPerm = hasNearbyPermission()
        val locationEnabled = isLocationEnabled()
        val networkOnline = isNetworkOnline()
        val notificationsEnabled = NotificationGate.status(this).enabled

        bleSupportedView.text = getString(
            R.string.status_ble_supported_format,
            if (bleSupported) getString(R.string.status_yes) else getString(R.string.status_no)
        )
        btEnabledView.text = getString(
            R.string.status_bt_enabled_format,
            if (btEnabled) getString(R.string.location_on) else getString(R.string.location_off)
        )
        bleScannerView.text = getString(
            R.string.status_ble_scanner_format,
            if (scanner) getString(R.string.status_yes) else getString(R.string.status_no)
        )
        bleAdvertiserView.text = getString(
            R.string.status_ble_advertiser_format,
            if (advertiser) getString(R.string.status_yes) else getString(R.string.status_no)
        )
        blePermissionsView.text = getString(
            R.string.status_ble_permissions_format,
            if (blePerms) getString(R.string.status_yes) else getString(R.string.status_no)
        )
        locationPermissionView.text = getString(
            R.string.status_location_permission_format,
            when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
                    getString(R.string.location_not_required)
                locationPerm -> getString(R.string.status_yes)
                else -> getString(R.string.status_no)
            }
        )
        nearbyPermissionView.text = getString(
            R.string.status_nearby_permission_format,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (nearbyPerm) getString(R.string.status_yes) else getString(R.string.status_no)
            } else {
                getString(R.string.location_not_required)
            }
        )
        locationView.text = getString(
            R.string.status_location_enabled_format,
            if (locationEnabled) getString(R.string.location_on) else getString(R.string.location_off)
        )
        networkView.text = getString(
            R.string.status_network_online_format,
            if (networkOnline) getString(R.string.status_yes) else getString(R.string.status_no)
        )
        notificationView.text = getString(
            R.string.status_notifications_format,
            if (notificationsEnabled) getString(R.string.status_yes) else getString(R.string.status_no)
        )
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun isNetworkOnline(): Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            manager.activeNetworkInfo?.isConnected == true
        }
    }

    private fun handleNotificationCta() {
        val status = NotificationGate.status(this)
        if (status.needsPermission) {
            NotificationGate.requestPermission(this, REQ_NOTIF_PERM)
            return
        }
        NotificationGate.openAppNotificationSettings(this)
    }

    private fun renderProfileSection(
        input: EditText,
        displayNameError: TextView,
        avatarPreviewContainer: FrameLayout,
        avatarPreview: TextView,
        avatarValue: TextView,
        avatarError: TextView,
        saveButton: Button,
    ) {
        val draftName = input.text?.toString().orEmpty()
        val displayNameErrorCode = LocalUserProfileValidator.validateDisplayName(draftName)
        val resolvedName = LocalUserProfileValidator.sanitizeDisplayName(draftName)
            .ifBlank { getString(R.string.nearby_user_fallback) }
        LocalUserProfileUi.bindAvatarPreview(
            activity = this,
            avatarId = selectedAvatarId,
            displayName = resolvedName,
            emojiView = avatarPreview,
            labelView = avatarValue,
        )
        avatarPreviewContainer.background.setTint(
            AvatarUtils.avatarColorForKey(selectedAvatarId ?: resolvedName)
        )
        avatarPreview.setTextColor(AvatarUtils.avatarTextColor(AvatarUtils.avatarColorForKey(selectedAvatarId ?: resolvedName)))
        displayNameError.text = when (displayNameErrorCode) {
            "required" -> getString(R.string.profile_display_name_required)
            "too_short" -> getString(R.string.profile_display_name_too_short)
            "too_long" -> getString(R.string.profile_display_name_too_long)
            else -> ""
        }
        displayNameError.visibility = if (displayNameErrorCode == null) View.GONE else View.VISIBLE
        avatarError.text = getString(R.string.profile_avatar_required)
        avatarError.visibility = if (selectedAvatarId.isNullOrBlank()) View.VISIBLE else View.GONE

        val savedProfile = localUserProfileStore.load()
        val sanitizedName = LocalUserProfileValidator.sanitizeDisplayName(draftName)
        val savedDisplayName = savedProfile?.displayName
        val savedAvatarId = savedProfile?.avatarId
        val changed = savedDisplayName != sanitizedName || savedAvatarId != selectedAvatarId
        saveButton.isEnabled = displayNameErrorCode == null && !selectedAvatarId.isNullOrBlank() && changed
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PREFS_NAME = PeerMeshPrefs.NAME
        private const val KEY_TOOLTIP_HIDDEN = "battery_tooltip_hidden"
        private const val REQ_BLE_PERM = 3001
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val REQ_NOTIF_PERM = 3102
    }
}
