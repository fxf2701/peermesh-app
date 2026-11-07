package app.peermesh

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.animation.ValueAnimator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.navigation.NavigationView
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.provider.Settings
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.appcompat.app.AlertDialog
import app.peermesh.data.db.AppDatabaseProvider
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.common.api.ResolvableApiException
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class TrailActivity : AppCompatActivity() {
    private val autoTrailAvailable: Boolean
        get() = PeerMeshCapabilities.canUseAutoTrail
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null
    private var adapterSavedCache: List<TrailRecord>? = null
    private var pendingManualStart: Boolean = false
    private var locationPromptShown: Boolean = false
    private var lastLocationEnabled: Boolean? = null
    private var locationReceiverRegistered: Boolean = false
    private var isForeground: Boolean = false
    private var suppressEnableToggle: Boolean = false
    private var suppressBgAutoToggle: Boolean = false
    private var backgroundInfoShown: Boolean = false
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val nowEnabled = TrailRecorder.isGpsEnabled(this@TrailActivity)
            val previous = lastLocationEnabled
            lastLocationEnabled = nowEnabled
            if (previous == true && !nowEnabled) {
                if (isForeground &&
                    (TrailRecorder.isEnabled(this@TrailActivity) || TrailRecorder.isRecording())
                ) {
                    showLocationDisabledWarning()
                }
            }
        }
    }
    private var settingsExpandedHeight: Int = 0
    private var settingsCollapsed: Boolean = false
    private var handleTouchStartY: Float = 0f
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        if (!PeerMeshProGate.require(this, PeerMeshProGate.FEATURE_TRAIL)) {
            finish()
            return
        }
        setContentView(R.layout.activity_trails)
        locationPromptShown = savedInstanceState?.getBoolean(KEY_LOCATION_PROMPT_SHOWN) ?: false

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<android.widget.ImageButton>(R.id.header_menu)
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        headerTitle.text = getString(R.string.trail_title)

        headerMenu.setOnClickListener {
            drawer.openDrawer(GravityCompat.END)
        }

        navView.setCheckedItem(R.id.menu_trails)
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_home -> startActivity(android.content.Intent(this, MainActivity::class.java))
                R.id.menu_nearby_rooms -> startActivity(android.content.Intent(this, NearbyRoomsActivity::class.java))
                R.id.menu_settings -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
                R.id.menu_statistics -> startActivity(android.content.Intent(this, StatisticsActivity::class.java))
                R.id.menu_test -> startActivity(android.content.Intent(this, TestActivity::class.java))
                R.id.menu_about -> startActivity(android.content.Intent(this, AboutActivity::class.java))
                R.id.menu_trails -> {}
            }
            drawer.closeDrawer(GravityCompat.END)
            true
        }

        val status = findViewById<TextView>(R.id.trail_status)
        val enableToggle = findViewById<Switch>(R.id.trail_enable_toggle)
        val modeGroup = findViewById<RadioGroup>(R.id.trail_mode_group)
        val bgAutoToggle = findViewById<Switch>(R.id.trail_bg_auto_toggle)
        val bgAutoHint = findViewById<TextView>(R.id.trail_bg_auto_hint)
        val autoModeOption = findViewById<android.widget.RadioButton>(R.id.trail_mode_auto)
        val autoModeHint = findViewById<TextView>(R.id.trail_mode_auto_hint_text)
        val bgAutoRow = findViewById<View>(R.id.trail_bg_auto_row)
        val notificationsBanner = findViewById<View>(R.id.trail_notifications_banner)
        val notificationsText = findViewById<TextView>(R.id.trail_notifications_text)
        val notificationsAction = findViewById<Button>(R.id.trail_notifications_action)
        val startBtn = findViewById<Button>(R.id.trail_start)
        val listView = findViewById<RecyclerView>(R.id.trail_list)
        val settingsContainer = findViewById<android.widget.LinearLayout>(R.id.trail_settings_container)
        val settingsHandle = findViewById<FrameLayout>(R.id.trail_settings_handle)

        lateinit var adapter: TrailAdapter
        adapter = TrailAdapter(
            onDelete = { record ->
                Thread {
                    TrailStore.delete(this, record.id)
                    TrailRecorder.suppressAutoStart(10_000L)
                    runOnUiThread { loadTrails(adapter, true) }
                }.start()
            },
            onExport = { record ->
                Thread {
                    val file = exportTrail(record)
                    if (file == null) {
                        runOnUiThread { toast(getString(R.string.trail_export_failed)) }
                        return@Thread
                    }
                    runOnUiThread { shareTrailFile(file) }
                }.start()
            },
            onView = { record ->
                val intent = Intent(this, TrailPlaybackActivity::class.java).apply {
                    putExtra(TrailPlaybackActivity.EXTRA_TRAIL_ID, record.id)
                }
                startActivity(intent)
            },
            onStopActive = {
                blockExportAfterStop()
                TrailRecorder.stopManual(this)
                updateStatus(status)
                updateButtons(startBtn)
                loadTrails(adapter, true)
            }
        )
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        enableToggle.isChecked = TrailRecorder.isEnabled(this)
        modeGroup.check(
            if (TrailRecorder.getMode(this) == TrailRecordingMode.AUTOMATIC) {
                R.id.trail_mode_auto
            } else {
                R.id.trail_mode_manual
            }
        )
        bgAutoToggle.isChecked = TrailRecorder.getBackgroundAuto(this)
        bgAutoToggle.isEnabled = autoTrailAvailable && (TrailRecorder.isEnabled(this) || TrailRecorder.isRecording())
        if (!autoTrailAvailable) {
            TrailRecorder.setMode(this, TrailRecordingMode.MANUAL)
            TrailRecorder.setBackgroundAuto(this, false)
            autoModeOption.visibility = View.GONE
            autoModeHint.visibility = View.GONE
            bgAutoRow.visibility = View.GONE
            bgAutoHint.visibility = View.GONE
        }
        updateStatus(status)
        updateButtons(startBtn)
        enforceLocationRequirements(modeGroup, bgAutoToggle, startBtn)
        updateBackgroundLocationHint(bgAutoHint)
        updateNotificationBanner(notificationsBanner, notificationsText, notificationsAction)

        enableToggle.setOnCheckedChangeListener { _, checked ->
            if (suppressEnableToggle) {
                suppressEnableToggle = false
                return@setOnCheckedChangeListener
            }
            if (checked && !hasLocationPermission()) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                    REQ_LOCATION_PERM,
                )
                enableToggle.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (checked && !TrailRecorder.isGpsEnabled(this)) {
                showTrailLocationPrompt(
                    onOpenSettings = { openLocationSettings() },
                    onSkip = { }
                )
            }
            if (!checked) {
                if (TrailRecorder.isRecording()) {
                    showEndRecordingDialog(
                        onEnd = {
                            stopActiveTrailFromUi(status, startBtn)
                            TrailRecorder.setBackgroundAuto(this, false)
                            TrailRecorder.setEnabled(this, false)
                            disableBackgroundAuto(bgAutoToggle, bgAutoHint)
                            updateStatus(status)
                            updateButtons(startBtn)
                            enforceLocationRequirements(modeGroup, bgAutoToggle, startBtn)
                        },
                        onCancel = {
                            suppressEnableToggle = true
                            enableToggle.isChecked = true
                        }
                    )
                    return@setOnCheckedChangeListener
                }
                TrailRecorder.setEnabled(this, false)
                disableBackgroundAuto(bgAutoToggle, bgAutoHint)
                updateStatus(status)
                updateButtons(startBtn)
                enforceLocationRequirements(modeGroup, bgAutoToggle, startBtn)
                return@setOnCheckedChangeListener
            }
            TrailRecorder.setEnabled(this, true)
            bgAutoToggle.isEnabled = true
            updateStatus(status)
            updateButtons(startBtn)
            enforceLocationRequirements(modeGroup, bgAutoToggle, startBtn)
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (autoTrailAvailable && checkedId == R.id.trail_mode_auto) {
                TrailRecordingMode.AUTOMATIC
            } else {
                TrailRecordingMode.MANUAL
            }
            TrailRecorder.setMode(this, mode)
            updateStatus(status)
            updateButtons(startBtn)
            enforceLocationRequirements(modeGroup, bgAutoToggle, startBtn)
        }

        bgAutoToggle.setOnCheckedChangeListener { _, checked ->
            if (suppressBgAutoToggle) {
                suppressBgAutoToggle = false
                return@setOnCheckedChangeListener
            }

            // Background auto-recording runs via Foreground Service + notification.
            // We only require foreground location permission (fine/coarse), not ACCESS_BACKGROUND_LOCATION.
            if (checked && !hasLocationPermission()) {
                suppressBgAutoToggle = true
                bgAutoToggle.isChecked = false
                requestLocationPermission()
                return@setOnCheckedChangeListener
            }

            if (checked && !TrailRecorder.isGpsEnabled(this)) {
                showTrailLocationPrompt(
                    onOpenSettings = {
                        TrailRecorder.setBackgroundAuto(this, true)
                        updateBackgroundLocationHint(bgAutoHint)
                        enforceLocationRequirements(modeGroup, bgAutoToggle, startBtn)
                        openLocationSettings()
                    },
                    onSkip = {
                        TrailRecorder.setBackgroundAuto(this, true)
                        updateBackgroundLocationHint(bgAutoHint)
                        enforceLocationRequirements(modeGroup, bgAutoToggle, startBtn)
                        showLocationDisabledWarning()
                    }
                )
                return@setOnCheckedChangeListener
            }

            TrailRecorder.setBackgroundAuto(this, checked)
            enforceLocationRequirements(modeGroup, bgAutoToggle, startBtn)
            updateBackgroundLocationHint(bgAutoHint)
            if (checked && !backgroundInfoShown) {
                backgroundInfoShown = true
                showBackgroundRecordingInfoDialog()
            }
        }
        startBtn.setOnClickListener {
            if (TrailRecorder.getMode(this) == TrailRecordingMode.MANUAL) {
                beginManualTrailStart(status, startBtn)
            }
        }

        settingsContainer.post {
            if (settingsExpandedHeight == 0) {
                settingsExpandedHeight = settingsContainer.measuredHeight
            }
        }
        settingsHandle.setOnClickListener {
            toggleTrailSettings(settingsContainer)
        }
        settingsHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    handleTouchStartY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val deltaY = event.rawY - handleTouchStartY
                    val threshold = ViewConfiguration.get(this).scaledTouchSlop * 4
                    if (deltaY <= -threshold) {
                        collapseTrailSettings(settingsContainer)
                        return@setOnTouchListener true
                    }
                    if (deltaY >= threshold) {
                        expandTrailSettings(settingsContainer)
                        return@setOnTouchListener true
                    }
                    false
                }
                android.view.MotionEvent.ACTION_CANCEL -> false
                else -> false
            }
        }

        loadTrails(adapter, true)
        showTrailLocationPromptIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
        isForeground = true
        registerLocationReceiverIfNeeded()
        lastLocationEnabled = TrailRecorder.isGpsEnabled(this)
        resetTrailUiStateIfActorChanged()
        val adapter = (findViewById<RecyclerView>(R.id.trail_list)).adapter as TrailAdapter
        TrailRecorder.setOnTrailSaved {
            val adapterNow = (findViewById<RecyclerView>(R.id.trail_list)).adapter as TrailAdapter
            loadTrails(adapterNow, true)
        }
        TrailRecorder.addSavingListener(trailSavingListener)
        loadTrails(adapter, true)
        enforceLocationRequirements(
            findViewById(R.id.trail_mode_group),
            findViewById(R.id.trail_bg_auto_toggle),
            findViewById(R.id.trail_start),
        )
        updateBackgroundLocationHint(findViewById(R.id.trail_bg_auto_hint))
        updateNotificationBanner(
            findViewById(R.id.trail_notifications_banner),
            findViewById(R.id.trail_notifications_text),
            findViewById(R.id.trail_notifications_action),
        )
        if (pendingManualStart) {
            handlePendingManualStart()
        }
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        val r = object : Runnable {
                override fun run() {
                    loadTrails(adapter, false)
                    enforceLocationRequirements(
                        findViewById(R.id.trail_mode_group),
                        findViewById(R.id.trail_bg_auto_toggle),
                        findViewById(R.id.trail_start),
                    )
                    updateBackgroundLocationHint(findViewById(R.id.trail_bg_auto_hint))
                    updateNotificationBanner(
                        findViewById(R.id.trail_notifications_banner),
                        findViewById(R.id.trail_notifications_text),
                        findViewById(R.id.trail_notifications_action),
                    )
                    refreshHandler.postDelayed(this, 5000L)
                }
            }
        refreshRunnable = r
        refreshHandler.postDelayed(r, 5000L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LOCATION_PROMPT_SHOWN, locationPromptShown)
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
        isForeground = false
        unregisterLocationReceiverIfNeeded()
        TrailRecorder.setOnTrailSaved(null)
        TrailRecorder.removeSavingListener(trailSavingListener)
        refreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        refreshRunnable = null
    }

    private fun resetTrailUiStateIfActorChanged() {
        val prefs = getSharedPreferences("ui_actor_guard", MODE_PRIVATE)
        val key = "trail_last_actor"
        val current = DeviceIdProvider.getActiveActor(this)
        val last = prefs.getString(key, null)
        if (last != null && last != current) {
            val adapter = (findViewById<RecyclerView>(R.id.trail_list)).adapter as? TrailAdapter
            adapter?.submit(emptyList(), activeState(), isSaving)
        }
        prefs.edit().putString(key, current).apply()
    }

    private fun loadTrails(adapter: TrailAdapter, reloadSaved: Boolean) {
        Thread {
            val shouldReload = reloadSaved || adapterSavedCache == null
            val trails = if (shouldReload) TrailStore.load(this) else emptyList()
            runOnUiThread {
                if (shouldReload) {
                    adapterSavedCache = trails
                }
                val activeId = TrailRecorder.currentTrailId()
                val currentSaved = (adapterSavedCache ?: emptyList()).filter { it.id != activeId }
                adapter.submit(currentSaved, activeState(), isSaving)
            }
        }.start()
    }

    private var isSaving: Boolean = false
    private var exportBlockUntilMs: Long = 0L
    private val exportBlockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val trailSavingListener: (Boolean) -> Unit = { saving ->
        isSaving = saving
        val adapter = (findViewById<RecyclerView>(R.id.trail_list)).adapter as? TrailAdapter
        if (adapter != null) {
            val now = System.currentTimeMillis()
            if (!saving && now < exportBlockUntilMs) {
                exportBlockHandler.postDelayed(
                    { loadTrails(adapter, false) },
                    exportBlockUntilMs - now
                )
            } else {
                loadTrails(adapter, false)
            }
        }
    }

    private fun blockExportAfterStop() {
        exportBlockUntilMs = System.currentTimeMillis() + MIN_EXPORT_BLOCK_MS
        isSaving = true
    }


    private fun updateStatus(view: TextView) {
        val enabled = TrailRecorder.isEnabled(this)
        val mode = TrailRecorder.getMode(this)
        val recording = TrailRecorder.isRecording()
        view.text = when {
            !enabled -> getString(R.string.trail_status_disabled)
            autoTrailAvailable && mode == TrailRecordingMode.AUTOMATIC && recording -> getString(R.string.trail_status_auto_recording)
            autoTrailAvailable && mode == TrailRecordingMode.AUTOMATIC -> getString(R.string.trail_status_auto)
            recording -> getString(R.string.trail_status_manual_recording)
            else -> getString(R.string.trail_status_manual)
        }
    }

    private fun updateButtons(start: Button) {
        val enabled = TrailRecorder.isEnabled(this)
        val mode = TrailRecorder.getMode(this)
        val recording = TrailRecorder.isRecording()
        val manual = mode == TrailRecordingMode.MANUAL
        val locationPermReady = TrailRecorder.isLocationPermissionReady(this)
        start.isEnabled = enabled && manual && !recording && locationPermReady
    }

    private fun beginManualTrailStart(status: TextView, startBtn: Button) {
        if (!TrailRecorder.isLocationPermissionReady(this)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQ_LOCATION_PERM,
            )
            return
        }
        if (!TrailRecorder.isGpsEnabled(this)) {
            showTrailLocationRequiredForStart()
            return
        }
        showManualTrailAudienceDialog(status, startBtn)
    }

    private fun showManualTrailAudienceDialog(status: TextView, startBtn: Button) {
        AlertDialog.Builder(this)
            .setTitle(R.string.trail_manual_audience_title)
            .setItems(
                arrayOf(
                    getString(R.string.trail_manual_audience_private),
                    getString(R.string.trail_manual_audience_group),
                    getString(R.string.trail_manual_audience_members),
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        startPrivateManualTrail(status, startBtn)
                    }
                    1 -> {
                        if (!PeerMeshProGate.require(this, PeerMeshProGate.FEATURE_SHARED_LIVE_TRAIL)) {
                            return@setItems
                        }
                        val gid = ActiveGroupManager.getActiveGroup(this)
                        if (gid.isNullOrBlank()) {
                            toast(getString(R.string.trail_manual_share_group_missing))
                            return@setItems
                        }
                        startManualTrailWithAudienceChecked(
                            gid = gid,
                            config = LiveTrailShareManager.ShareConfig(
                                enabled = true,
                                scope = LiveTrailShareManager.SCOPE_GROUP,
                                members = emptyList(),
                            ),
                            status = status,
                            startBtn = startBtn,
                        )
                    }
                    2 -> {
                        if (!PeerMeshProGate.require(this, PeerMeshProGate.FEATURE_SHARED_LIVE_TRAIL)) {
                            return@setItems
                        }
                        val gid = ActiveGroupManager.getActiveGroup(this)
                        if (gid.isNullOrBlank()) {
                            toast(getString(R.string.trail_manual_share_group_missing))
                            return@setItems
                        }
                        showManualTrailMemberPicker(gid) { chosen ->
                            if (chosen.isEmpty()) {
                                toast(getString(R.string.trail_manual_audience_members_empty))
                                return@showManualTrailMemberPicker
                            }
                            startManualTrailWithAudienceChecked(
                                gid = gid,
                                config = LiveTrailShareManager.ShareConfig(
                                    enabled = true,
                                    scope = LiveTrailShareManager.SCOPE_MEMBERS,
                                    members = chosen,
                                ),
                                status = status,
                                startBtn = startBtn,
                            )
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startPrivateManualTrail(status: TextView, startBtn: Button) {
        TrailRecorder.startManual(this)
        updateStatus(status)
        updateButtons(startBtn)
    }

    private fun startManualTrailWithAudienceChecked(
        gid: String,
        config: LiveTrailShareManager.ShareConfig,
        status: TextView,
        startBtn: Button,
    ) {
        Thread {
            val removed = isRemovedFromGroup(gid)
            runOnUiThread {
                if (removed) {
                    toast(getString(R.string.group_removed_trail_share_blocked))
                    return@runOnUiThread
                }
                LiveTrailShareManager.prepareManualTrailShare(this, gid, config)
                TrailRecorder.startManual(this)
                updateStatus(status)
                updateButtons(startBtn)
            }
        }.start()
    }

    private fun isRemovedFromGroup(gid: String): Boolean {
        val db = AppDatabaseProvider.get(applicationContext)
        return app.peermesh.data.membership.GroupMembershipPolicy.isPeerRemoved(
            db,
            gid,
            DeviceIdProvider.getActiveActor(this),
        )
    }

    private fun showManualTrailMemberPicker(gid: String, onSelected: (List<String>) -> Unit) {
        Thread {
            val db = app.peermesh.data.db.AppDatabaseProvider.get(this)
            val actorDid = DeviceIdProvider.getActiveActor(this)
            val group = db.groupDao().findGroup(gid)
            val activeActorIds = GroupActiveMembers.actorIds(
                db = db,
                gid = gid,
                ownerDid = group?.owner_did,
                currentActorDid = actorDid,
            )
            val profiles = db.userProfileDao().listByGid(gid)
                .filter { it.actor_did in activeActorIds }
                .filter { it.actor_did != actorDid }
            runOnUiThread {
                if (profiles.isEmpty()) {
                    toast(getString(R.string.trail_manual_audience_members_empty))
                    return@runOnUiThread
                }
                val labels = profiles.map { profile ->
                    val name = profile.display_name.ifBlank { shortAuthorId(profile.actor_did) }
                    val avatar = avatarForProfile(profile.avatar_id, name)
                    "$avatar $name"
                }
                val chosen = mutableSetOf<String>()
                AlertDialog.Builder(this)
                    .setTitle(R.string.trail_manual_members_title)
                    .setMultiChoiceItems(labels.toTypedArray(), null) { _, which, isChecked ->
                        val did = profiles[which].actor_did
                        if (isChecked) chosen.add(did) else chosen.remove(did)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        onSelected(chosen.toList())
                    }
                    .show()
            }
        }.start()
    }

    private fun shortAuthorId(actorDid: String): String {
        return if (actorDid.length <= 6) actorDid else actorDid.takeLast(6)
    }

    private fun avatarForProfile(avatarId: String?, displayName: String): String {
        return avatarId?.let { AvatarUtils.avatarEmojiForId(this, it) } ?: AvatarUtils.avatarInitials(displayName)
    }

    private fun toggleTrailSettings(container: android.widget.LinearLayout) {
        if (settingsCollapsed) {
            expandTrailSettings(container)
        } else {
            collapseTrailSettings(container)
        }
    }

    private fun collapseTrailSettings(container: android.widget.LinearLayout) {
        if (settingsCollapsed) return
        val startHeight = container.height
        val animator = ValueAnimator.ofInt(startHeight, 0).apply {
            duration = 300L
            addUpdateListener { valueAnimator ->
                val value = valueAnimator.animatedValue as Int
                container.layoutParams = container.layoutParams.apply { height = value }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    container.visibility = android.view.View.GONE
                    settingsCollapsed = true
                    val handle = findViewById<FrameLayout>(R.id.trail_settings_handle)
                    (handle.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                        params.topMargin = 0
                        params.bottomMargin = (4 * resources.displayMetrics.density).toInt()
                        handle.layoutParams = params
                    }
                }
            })
        }
        animator.start()
    }

    private fun expandTrailSettings(container: android.widget.LinearLayout) {
        if (!settingsCollapsed) return
        container.visibility = android.view.View.VISIBLE
        if (settingsExpandedHeight == 0) {
            container.measure(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            settingsExpandedHeight = container.measuredHeight
        }
        val animator = ValueAnimator.ofInt(0, settingsExpandedHeight).apply {
            duration = 300L
            addUpdateListener { valueAnimator ->
                val value = valueAnimator.animatedValue as Int
                container.layoutParams = container.layoutParams.apply { height = value }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    container.layoutParams = container.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    settingsCollapsed = false
                    val handle = findViewById<FrameLayout>(R.id.trail_settings_handle)
                    (handle.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                        params.topMargin = (8 * resources.displayMetrics.density).toInt()
                        params.bottomMargin = (8 * resources.displayMetrics.density).toInt()
                        handle.layoutParams = params
                    }
                }
            })
        }
        animator.start()
    }

    private fun enforceLocationRequirements(
        modeGroup: RadioGroup,
        bgAutoToggle: Switch,
        startBtn: Button,
    ) {
        val locationPermReady = TrailRecorder.isLocationPermissionReady(this)
        val gpsEnabled = TrailRecorder.isGpsEnabled(this)
        val autoOption = modeGroup.findViewById<android.widget.RadioButton>(R.id.trail_mode_auto)
        if (!autoTrailAvailable) {
            if (modeGroup.checkedRadioButtonId == R.id.trail_mode_auto) {
                modeGroup.check(R.id.trail_mode_manual)
            }
            autoOption?.isEnabled = false
            bgAutoToggle.isChecked = false
            bgAutoToggle.isEnabled = false
            TrailRecorder.setMode(this, TrailRecordingMode.MANUAL)
            TrailRecorder.setBackgroundAuto(this, false)
            updateButtons(startBtn)
            return
        }
        if (!locationPermReady || !gpsEnabled) {
            if (modeGroup.checkedRadioButtonId == R.id.trail_mode_auto) {
                modeGroup.check(R.id.trail_mode_manual)
                TrailRecorder.setMode(this, TrailRecordingMode.MANUAL)
            }
            autoOption?.isEnabled = false
            bgAutoToggle.isChecked = false
            TrailRecorder.setBackgroundAuto(this, false)
        } else {
            autoOption?.isEnabled = true
        }
        updateButtons(startBtn)
    }

    private fun activeState(): TrailActiveState? {
        if (!TrailRecorder.isRecording()) return null
        val start = TrailRecorder.currentStartSeconds()
        if (start <= 0L) return null
        val elapsed = (System.currentTimeMillis() / 1000L) - start
        val label = formatElapsed(elapsed)
        return TrailActiveState(label, TrailRecorder.currentPointCount(), TrailRecorder.currentDistanceMeters())
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

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun exportTrail(record: TrailRecord): java.io.File? {
        val dir = trailExportDir()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val safeName = record.name.replace("\\s+".toRegex(), "_")
        val file = java.io.File(dir, "trail_${safeName}_$stamp.json")
        val db = app.peermesh.data.db.AppDatabaseProvider.get(this)
        val points = waitForTrailPoints(db, record.id)
        return runCatching {
            val json = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                kotlinx.serialization.json.JsonObject(
                    mapOf(
                        "id" to kotlinx.serialization.json.JsonPrimitive(record.id),
                        "name" to kotlinx.serialization.json.JsonPrimitive(record.name),
                        "mode" to kotlinx.serialization.json.JsonPrimitive(record.mode),
                        "start_t" to kotlinx.serialization.json.JsonPrimitive(record.start_t),
                        "end_t" to kotlinx.serialization.json.JsonPrimitive(record.end_t),
                        "point_count" to kotlinx.serialization.json.JsonPrimitive(record.point_count),
                        "dist_m" to kotlinx.serialization.json.JsonPrimitive(record.dist_m),
                        "unstable" to kotlinx.serialization.json.JsonPrimitive(record.unstable),
                        "unstable_note" to (record.unstable_note?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) }
                            ?: kotlinx.serialization.json.JsonNull),
                        "points" to kotlinx.serialization.json.JsonArray(
                            points.map {
                                kotlinx.serialization.json.JsonObject(
                                    mapOf(
                                        "lat" to kotlinx.serialization.json.JsonPrimitive(it.lat),
                                        "lon" to kotlinx.serialization.json.JsonPrimitive(it.lon),
                                        "t" to kotlinx.serialization.json.JsonPrimitive(it.t),
                                        "acc" to (it.acc?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) }
                                            ?: kotlinx.serialization.json.JsonNull),
                                        "spd" to (it.spd?.let { v -> kotlinx.serialization.json.JsonPrimitive(v) }
                                            ?: kotlinx.serialization.json.JsonNull),
                                        "mm" to kotlinx.serialization.json.JsonPrimitive(it.mm),
                                    )
                                )
                            }
                        )
                    )
                )
            )
            file.writeText(json)
            file
        }.getOrNull()
    }

    private fun trailExportDir(): java.io.File {
        val base = getExternalFilesDir(null) ?: filesDir
        val dir = java.io.File(base, "trail_exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun waitForTrailPoints(
        db: app.peermesh.data.db.AppDatabase,
        trailId: String,
    ): List<app.peermesh.data.db.entity.TrailPointEntity> {
        val start = System.currentTimeMillis()
        var points = db.trailDao().listPoints(trailId)
        if (points.isNotEmpty()) return points
        while (System.currentTimeMillis() - start < EXPORT_WAIT_MS) {
            if (!TrailRecorder.isSaving()) {
                Thread.sleep(150)
            } else {
                Thread.sleep(250)
            }
            points = db.trailDao().listPoints(trailId)
            if (points.isNotEmpty()) return points
        }
        return points
    }

    private fun shareTrailFile(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.trail_export)))
    }

    private fun handlePendingManualStart() {
        if (!pendingManualStart) return
        if (TrailRecorder.isGpsEnabled(this)) {
            pendingManualStart = false
            TrailRecorder.startManual(this)
            updateStatus(findViewById(R.id.trail_status))
            updateButtons(findViewById(R.id.trail_start))
            return
        }
        pendingManualStart = false
        toast(getString(R.string.trail_location_required_to_start))
    }

    private fun enableLocation() {
        if (TrailRecorder.isGpsEnabled(this)) {
            handlePendingManualStart()
            return
        }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS) {
            val request = LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                10_000L
            ).build()
            val settingsRequest = LocationSettingsRequest.Builder()
                .addLocationRequest(request)
                .setAlwaysShow(true)
                .build()
            val client = LocationServices.getSettingsClient(this)
            client.checkLocationSettings(settingsRequest)
                .addOnSuccessListener { handlePendingManualStart() }
                .addOnFailureListener { ex ->
                    if (ex is ResolvableApiException) {
                        try {
                            ex.startResolutionForResult(this, REQ_ENABLE_LOCATION)
                        } catch (_: Exception) {
                            openLocationSettings()
                        }
                    } else {
                        openLocationSettings()
                    }
                }
        } else {
            openLocationSettings()
        }
    }

    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun showTrailLocationPromptIfNeeded() {
        if (locationPromptShown) return
        if (TrailRecorder.isGpsEnabled(this)) return
        locationPromptShown = true
        showTrailLocationPrompt(
            onOpenSettings = { openLocationSettings() },
            onSkip = { }
        )
    }

    private fun showTrailLocationRequiredForStart() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.trail_location_required_title))
            .setMessage(getString(R.string.trail_location_required_body))
            .setPositiveButton(getString(R.string.open_location_settings)) { _, _ ->
                pendingManualStart = true
                enableLocation()
            }
            .setNegativeButton(getString(R.string.action_not_now), null)
            .show()
    }

    private fun showTrailLocationPrompt(
        onOpenSettings: () -> Unit,
        onSkip: () -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.trail_location_prompt_title))
            .setMessage(getString(R.string.trail_location_prompt_body))
            .setPositiveButton(getString(R.string.open_location_settings)) { _, _ ->
                onOpenSettings()
            }
            .setNegativeButton(getString(R.string.action_not_now)) { _, _ ->
                onSkip()
            }
            .show()
    }

    private fun showLocationDisabledWarning() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.trail_location_disabled_title))
            .setMessage(getString(R.string.trail_location_disabled_body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showEndRecordingDialog(
        onEnd: () -> Unit,
        onCancel: () -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.trail_end_recording_title))
            .setMessage(getString(R.string.trail_end_recording_body))
            .setPositiveButton(getString(R.string.trail_end_recording_action)) { _, _ ->
                onEnd()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                onCancel()
            }
            .show()
    }

    private fun disableBackgroundAuto(toggle: Switch, hint: TextView) {
        TrailRecorder.setBackgroundAuto(this, false)
        suppressBgAutoToggle = true
        toggle.isChecked = false
        toggle.isEnabled = false
        updateBackgroundLocationHint(hint)
    }

    private fun stopActiveTrailFromUi(statusView: TextView, startButton: Button) {
        blockExportAfterStop()
        TrailRecorder.stopManual(this)
        updateStatus(statusView)
        updateButtons(startButton)
        val adapterNow = (findViewById<RecyclerView>(R.id.trail_list)).adapter as? TrailAdapter
        if (adapterNow != null) {
            loadTrails(adapterNow, true)
        }
    }

    private fun registerLocationReceiverIfNeeded() {
        if (locationReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(android.location.LocationManager.MODE_CHANGED_ACTION)
        }
        registerReceiver(locationReceiver, filter)
        locationReceiverRegistered = true
    }

    private fun unregisterLocationReceiverIfNeeded() {
        if (!locationReceiverRegistered) return
        unregisterReceiver(locationReceiver)
        locationReceiverRegistered = false
    }

    private fun updateBackgroundLocationHint(view: TextView) {
        val enabled = TrailRecorder.getBackgroundAuto(this)
        view.text = if (enabled) {
            getString(R.string.trail_bg_auto_hint_on)
        } else {
            getString(R.string.trail_bg_auto_hint_off)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        return manager.activeNetworkInfo?.isConnected == true
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION_PERM) {
            val granted = grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            val toggle = findViewById<Switch>(R.id.trail_enable_toggle)
            toggle.isChecked = granted
            if (granted) {
                TrailRecorder.setEnabled(this, true)
                updateStatus(findViewById(R.id.trail_status))
                updateButtons(findViewById(R.id.trail_start))
                enforceLocationRequirements(
                    findViewById(R.id.trail_mode_group),
                    findViewById(R.id.trail_bg_auto_toggle),
                    findViewById(R.id.trail_start),
                )
            } else {
                TrailRecorder.handlePermissionMissing(this)
                enforceLocationRequirements(
                    findViewById(R.id.trail_mode_group),
                    findViewById(R.id.trail_bg_auto_toggle),
                    findViewById(R.id.trail_start),
                )
            }

            // Background auto-recording runs via Foreground Service + notification.
            // No ACCESS_BACKGROUND_LOCATION permission is requested/handled.
            updateBackgroundLocationHint(findViewById(R.id.trail_bg_auto_hint))
        }
        if (requestCode == REQ_NOTIF_PERM) {
            updateNotificationBanner(
                findViewById(R.id.trail_notifications_banner),
                findViewById(R.id.trail_notifications_text),
                findViewById(R.id.trail_notifications_action),
            )
        }
    }

    private fun showBackgroundRecordingInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.trail_bg_auto_info_title))
            .setMessage(getString(R.string.trail_bg_auto_info_body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            REQ_LOCATION_PERM,
        )
    }

    private fun updateNotificationBanner(container: View, body: TextView, action: Button) {
        val status = NotificationGate.status(this, TrailForegroundService.CHANNEL_ID)
        if (status.enabled) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        body.text = getString(R.string.notifications_off_banner)
        action.text = if (status.needsPermission) {
            getString(R.string.notifications_enable)
        } else {
            getString(R.string.open_notification_settings)
        }
        action.setOnClickListener {
            val current = NotificationGate.status(this, TrailForegroundService.CHANNEL_ID)
            if (current.needsPermission) {
                NotificationGate.requestPermission(this, REQ_NOTIF_PERM)
            } else if (current.channelDisabled) {
                NotificationGate.openChannelSettings(this, TrailForegroundService.CHANNEL_ID)
            } else {
                NotificationGate.openAppNotificationSettings(this)
            }
        }
    }

    companion object {
        private const val EXPORT_WAIT_MS = 4_000L
        private const val MIN_EXPORT_BLOCK_MS = 400L
        private const val KEY_LOCATION_PROMPT_SHOWN = "key_trail_location_prompt_shown"
        private const val REQ_LOCATION_PERM = 4001
        private const val REQ_NOTIF_PERM = 4002
        // (removed) REQ_BG_LOCATION_PERM: background location permission no longer requested
        private const val REQ_ENABLE_LOCATION = 4003
    }
}
