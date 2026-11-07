package app.peermesh

import android.animation.ObjectAnimator
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.ble.BleMeshRuntime
import app.peermesh.data.debug.ActivityLog
import app.peermesh.nearby.ActiveNearbyRoomSessionManager
import app.peermesh.nearby.BleModeController
import app.peermesh.nearby.NearbyReadinessHelper
import app.peermesh.nearby.NearbyRoomBleCoordinator
import app.peermesh.nearby.NearbyRoomPendingJoinIntentManager
import app.peermesh.nearby.NearbyForegroundRuntimeCoordinator
import app.peermesh.nearby.NearbyRoomPolicy
import app.peermesh.nearby.NearbyRoomRepository
import com.google.android.material.navigation.NavigationView
import java.util.concurrent.atomic.AtomicBoolean

class NearbyRoomsActivity : AppCompatActivity() {
    private sealed interface NearbyDiscoveryUiState {
        data object Searching : NearbyDiscoveryUiState
        data class Results(val roomsCount: Int) : NearbyDiscoveryUiState
        data object Empty : NearbyDiscoveryUiState
        data class Paused(val reasons: List<app.peermesh.nearby.NearbyBlockingReason>) : NearbyDiscoveryUiState
        data class Error(val message: String? = null) : NearbyDiscoveryUiState
    }

    private val repository by lazy { NearbyRoomRepository(applicationContext) }
    private val localUserProfileStore by lazy { LocalUserProfileStore(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInFlight = AtomicBoolean(false)
    private var refreshRunnable: Runnable? = null
    private var pendingDiscoveryRefreshRunnable: Runnable? = null
    private var gestureStartY: Float? = null
    private var lastManualRefreshAtMs: Long = 0L
    private var onboardingSelectedAvatarId: String? = null
    private var pendingCreateAfterSetup = false
    private var discoveryUiState: NearbyDiscoveryUiState = NearbyDiscoveryUiState.Empty
    private var lastKnownRoomCount = 0
    private var hasLocalOwnedActiveRoom = false
    private var setupStatusPulseAnimator: ObjectAnimator? = null
    private var statusReceiverRegistered = false
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            refreshNearbyDiscoveryReadiness()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_rooms)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        val introBlock = findViewById<LinearLayout>(R.id.nearby_rooms_intro_block)
        val empty = findViewById<LinearLayout>(R.id.nearby_rooms_empty)
        val list = findViewById<RecyclerView>(R.id.nearby_rooms_list)
        val create = findViewById<Button>(R.id.nearby_rooms_create)
        val emptyCreate = findViewById<Button>(R.id.nearby_rooms_empty_create)
        val loading = findViewById<NearbyRoomsLoadingDotsView>(R.id.nearby_rooms_loading)
        val onboarding = findViewById<ScrollView>(R.id.nearby_identity_onboarding)
        val onboardingDisplayNameInput = findViewById<EditText>(R.id.nearby_identity_display_name_input)
        val onboardingDisplayNameError = findViewById<TextView>(R.id.nearby_identity_display_name_error)
        val onboardingAvatarPreview = findViewById<TextView>(R.id.nearby_identity_avatar_preview)
        val onboardingAvatarValue = findViewById<TextView>(R.id.nearby_identity_avatar_value)
        val onboardingAvatarPreviewContainer = findViewById<FrameLayout>(R.id.nearby_identity_avatar_preview_container)
        val onboardingAvatarError = findViewById<TextView>(R.id.nearby_identity_avatar_error)
        val onboardingAvatarButton = findViewById<Button>(R.id.nearby_identity_avatar_button)
        val onboardingContinue = findViewById<Button>(R.id.nearby_identity_continue)

        headerTitle.text = getString(R.string.page_title_nearby_rooms)
        val adapter = NearbyRoomAdapter { room ->
            val attemptId = NearbyForegroundRuntimeCoordinator.beginJoining(
                context = applicationContext,
                roomId = room.room_id,
                reason = "discovery_room_tap",
            )
            if (room.is_local_owner) {
                NearbyRoomPendingJoinIntentManager.clear(
                    applicationContext,
                    reason = "opened_local_room",
                )
            } else {
                NearbyRoomPendingJoinIntentManager.recordManualJoinIntent(
                    context = applicationContext,
                    roomId = room.room_id,
                    source = "discovery_room_open",
                )
            }
            startActivity(Intent(this, NearbyRoomActivity::class.java).apply {
                putExtra(NearbyRoomActivity.EXTRA_ROOM_ID, room.room_id)
                putExtra(NearbyRoomActivity.EXTRA_JOIN_ATTEMPT_ID, attemptId)
            })
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        attachEdgeRefreshGesture(list, adapter, introBlock, empty, loading)

        headerMenu.setOnClickListener {
            drawer.openDrawer(GravityCompat.END)
        }
        PeerMeshFeatureUi.applyDrawerCapabilities(navView)
        navView.setCheckedItem(R.id.menu_nearby_rooms)
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_home -> startActivity(Intent(this, MainActivity::class.java))
                R.id.menu_nearby_rooms -> {}
                R.id.menu_trails -> PeerMeshFeatureUi.openTrailOrPaywall(this)
                R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.menu_statistics -> startActivity(Intent(this, StatisticsActivity::class.java))
                R.id.menu_about -> startActivity(Intent(this, AboutActivity::class.java))
                R.id.menu_test -> startActivity(Intent(this, TestActivity::class.java))
            }
            drawer.closeDrawer(Gravity.END)
            true
        }

        create.setOnClickListener { showCreateDialog(adapter, introBlock, empty) }
        emptyCreate.setOnClickListener { showCreateDialog(adapter, introBlock, empty) }

        val existingProfile = localUserProfileStore.load()
        onboardingSelectedAvatarId = existingProfile?.avatarId
        onboardingDisplayNameInput.setText(existingProfile?.displayName.orEmpty())
        val onboardingWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderOnboarding(
                    displayNameInput = onboardingDisplayNameInput,
                    displayNameError = onboardingDisplayNameError,
                    avatarPreviewContainer = onboardingAvatarPreviewContainer,
                    avatarPreview = onboardingAvatarPreview,
                    avatarValue = onboardingAvatarValue,
                    avatarError = onboardingAvatarError,
                    continueButton = onboardingContinue,
                )
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        onboardingDisplayNameInput.addTextChangedListener(onboardingWatcher)
        onboardingAvatarButton.setOnClickListener {
            LocalUserProfileUi.showAvatarPicker(this, onboardingSelectedAvatarId) { avatarId ->
                onboardingSelectedAvatarId = avatarId
                renderOnboarding(
                    displayNameInput = onboardingDisplayNameInput,
                    displayNameError = onboardingDisplayNameError,
                    avatarPreviewContainer = onboardingAvatarPreviewContainer,
                    avatarPreview = onboardingAvatarPreview,
                    avatarValue = onboardingAvatarValue,
                    avatarError = onboardingAvatarError,
                    continueButton = onboardingContinue,
                )
            }
        }
        findViewById<View>(R.id.nearby_identity_avatar_row).setOnClickListener {
            onboardingAvatarButton.performClick()
        }
        onboardingContinue.setOnClickListener {
            val displayName = onboardingDisplayNameInput.text?.toString().orEmpty()
            val avatarId = onboardingSelectedAvatarId
            val displayNameErrorCode = LocalUserProfileValidator.validateDisplayName(displayName)
            if (displayNameErrorCode != null || avatarId.isNullOrBlank()) {
                renderOnboarding(
                    displayNameInput = onboardingDisplayNameInput,
                    displayNameError = onboardingDisplayNameError,
                    avatarPreviewContainer = onboardingAvatarPreviewContainer,
                    avatarPreview = onboardingAvatarPreview,
                    avatarValue = onboardingAvatarValue,
                    avatarError = onboardingAvatarError,
                    continueButton = onboardingContinue,
                )
                return@setOnClickListener
            }
            val profile = localUserProfileStore.save(displayName, avatarId)
            Thread { repository.syncLocalUserProfile(profile) }.start()
            onboarding.visibility = android.view.View.GONE
            refreshNearbyDiscoveryReadiness()
        }
        renderOnboarding(
            displayNameInput = onboardingDisplayNameInput,
            displayNameError = onboardingDisplayNameError,
            avatarPreviewContainer = onboardingAvatarPreviewContainer,
            avatarPreview = onboardingAvatarPreview,
            avatarValue = onboardingAvatarValue,
            avatarError = onboardingAvatarError,
            continueButton = onboardingContinue,
        )
    }

    override fun onResume() {
        super.onResume()
        NearbyForegroundRuntimeCoordinator.enterDiscovery(applicationContext, "discovery_on_resume")
        StatusIndicatorUpdater.start(this)
        registerStatusReceiverIfNeeded()
        val onboarding = findViewById<ScrollView>(R.id.nearby_identity_onboarding)
        if (localUserProfileStore.load() == null) {
            stopRefreshLoop()
            stopPendingDiscoveryRefresh()
            ActiveNearbyRoomSessionManager.leaveDiscovery(applicationContext)
            NearbyRoomBleCoordinator.clearActiveRoomAdvertisement(applicationContext)
            onboarding.visibility = android.view.View.VISIBLE
            findViewById<NearbyRoomsLoadingDotsView>(R.id.nearby_rooms_loading).setLoadingVisible(false)
            return
        }
        onboarding.visibility = android.view.View.GONE
        refreshNearbyDiscoveryReadiness()
    }

    override fun onPause() {
        super.onPause()
        NearbyForegroundRuntimeCoordinator.leaveDiscovery(applicationContext, "discovery_on_pause")
        stopRefreshLoop()
        stopPendingDiscoveryRefresh()
        ActiveNearbyRoomSessionManager.leaveDiscovery(applicationContext)
        stopSetupStatusPulse()
        findViewById<NearbyRoomsLoadingDotsView>(R.id.nearby_rooms_loading).setLoadingVisible(false)
        unregisterStatusReceiverIfNeeded()
        StatusIndicatorUpdater.stop(this)
    }

    private fun requestRoomsRefresh(
        adapter: NearbyRoomAdapter,
        introBlock: LinearLayout,
        empty: LinearLayout,
        loading: NearbyRoomsLoadingDotsView,
        create: Button,
        createHelper: TextView,
    ) {
        if (!refreshInFlight.compareAndSet(false, true)) {
            ActivityLog.add(
                label = "Nearby rooms refresh skipped",
                source = "nearby",
                message = "reason=in_flight",
            )
            return
        }
        ActivityLog.add(
            label = "Nearby rooms refresh db start",
            source = "nearby",
            message = "source=local_db",
        )
        Thread {
            runCatching {
                NearbyRoomBleCoordinator.flushBufferedDiscovery(applicationContext)
                val activeRooms = repository.listActiveRooms()
                val rooms = repository.listVisibleRooms()
                val hasLocalOwnedActiveRoom = activeRooms.any { it.is_local_owner }
                NearbyRoomBleCoordinator.enqueueMetadataRefresh(
                    applicationContext,
                    activeRooms.map { it.room_id },
                )
                Triple(rooms, activeRooms, hasLocalOwnedActiveRoom)
            }.onSuccess { (rooms, activeRooms, hasLocalOwnedActiveRoom) ->
                runOnUiThread {
                    adapter.submitList(rooms)
                    updateDiscoveryUi(
                        roomCount = rooms.size,
                        hasLocalOwnedActiveRoom = hasLocalOwnedActiveRoom,
                        introBlock = introBlock,
                        empty = empty,
                        create = create,
                        createHelper = createHelper,
                    )
                    updateDiscoveryState(
                        if (rooms.isEmpty()) NearbyDiscoveryUiState.Empty else NearbyDiscoveryUiState.Results(rooms.size)
                    )
                    loading.setLoadingVisible(false)
                    refreshInFlight.set(false)
                    ActivityLog.add(
                        label = "Nearby rooms refresh db done",
                        source = "nearby",
                        message = "source=local_db rooms=${rooms.size} active_rooms=${activeRooms.size} local_owner_active=$hasLocalOwnedActiveRoom",
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    loading.setLoadingVisible(false)
                    refreshInFlight.set(false)
                    updateDiscoveryState(NearbyDiscoveryUiState.Error(error.message))
                }
            }
        }.start()
    }

    private fun triggerDiscoveryRefresh(
        adapter: NearbyRoomAdapter,
        introBlock: LinearLayout,
        empty: LinearLayout,
        loading: NearbyRoomsLoadingDotsView,
        createHelper: TextView,
        isManual: Boolean,
    ) {
        val readiness = NearbyReadinessHelper.evaluate(applicationContext)
        if (!readiness.canDiscover) {
            stopRefreshLoop()
            stopPendingDiscoveryRefresh()
            loading.setLoadingVisible(false)
            updateDiscoveryState(NearbyDiscoveryUiState.Paused(readiness.blockingReasons))
            return
        }
        if (!BleModeController.shouldProcessDiscoveryMetadata(applicationContext)) {
            loading.setLoadingVisible(false)
            ActivityLog.add(
                label = "Nearby rooms refresh skipped",
                source = "nearby",
                message = "reason=discovery_disabled manual=$isManual",
            )
            updateDiscoveryState(NearbyDiscoveryUiState.Error())
            return
        }
        if (isManual) {
            val nowMs = System.currentTimeMillis()
            val remainingMs = MANUAL_REFRESH_COOLDOWN_MS - (nowMs - lastManualRefreshAtMs)
            if (remainingMs > 0L) {
                loading.setLoadingVisible(false)
                val remainingSeconds = ((remainingMs + 999L) / 1000L).toInt()
                toast("Please wait ${remainingSeconds}s to reload nearby rooms.")
                ActivityLog.add(
                    label = "Nearby rooms refresh skipped",
                    source = "nearby",
                    message = "reason=manual_cooldown remaining_ms=$remainingMs",
                )
                return
            }
            lastManualRefreshAtMs = nowMs
        }
        ActivityLog.add(
            label = "Nearby rooms refresh trigger",
            source = "nearby",
            message = "manual=$isManual burst_ms=$DISCOVERY_BURST_DURATION_MS settle_ms=$DISCOVERY_RESULT_SETTLE_DELAY_MS",
        )
        updateDiscoveryState(NearbyDiscoveryUiState.Searching)
        loading.setLoadingVisible(true)
        runCatching {
            BleMeshRuntime.get(applicationContext).requestDiscoveryBurst(DISCOVERY_BURST_DURATION_MS)
        }.onFailure {
            loading.setLoadingVisible(false)
            updateDiscoveryState(NearbyDiscoveryUiState.Error(it.message))
            return
        }
        stopPendingDiscoveryRefresh()
        pendingDiscoveryRefreshRunnable = Runnable {
            requestRoomsRefresh(
                adapter,
                introBlock,
                empty,
                loading,
                findViewById(R.id.nearby_rooms_create),
                createHelper,
            )
        }.also {
            handler.postDelayed(it, DISCOVERY_RESULT_SETTLE_DELAY_MS)
        }
    }

    private fun startRefreshLoop() {
        stopRefreshLoop()
        val adapter = findViewById<RecyclerView>(R.id.nearby_rooms_list).adapter as? NearbyRoomAdapter ?: return
        val introBlock = findViewById<LinearLayout>(R.id.nearby_rooms_intro_block)
        val empty = findViewById<LinearLayout>(R.id.nearby_rooms_empty)
        val loading = findViewById<NearbyRoomsLoadingDotsView>(R.id.nearby_rooms_loading)
        val createHelper = findViewById<TextView>(R.id.nearby_rooms_create_helper)
        refreshRunnable = object : Runnable {
            override fun run() {
                triggerDiscoveryRefresh(adapter, introBlock, empty, loading, createHelper, isManual = false)
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }.also { handler.postDelayed(it, REFRESH_INTERVAL_MS) }
    }

    private fun stopRefreshLoop() {
        refreshRunnable?.let(handler::removeCallbacks)
        refreshRunnable = null
    }

    private fun stopPendingDiscoveryRefresh() {
        pendingDiscoveryRefreshRunnable?.let(handler::removeCallbacks)
        pendingDiscoveryRefreshRunnable = null
        refreshInFlight.set(false)
    }

    private fun updateDiscoveryUi(
        roomCount: Int,
        hasLocalOwnedActiveRoom: Boolean,
        introBlock: LinearLayout,
        empty: LinearLayout,
        create: Button,
        createHelper: TextView,
    ) {
        lastKnownRoomCount = roomCount
        this.hasLocalOwnedActiveRoom = hasLocalOwnedActiveRoom
        renderPrimaryDiscoveryContent(introBlock, empty, create, createHelper)
    }

    private fun showCreateDialog(
        adapter: NearbyRoomAdapter,
        introBlock: LinearLayout,
        empty: LinearLayout,
    ) {
        val readiness = NearbyReadinessHelper.evaluate(applicationContext)
        if (!readiness.canCreateRoom) {
            pendingCreateAfterSetup = true
            showCreateSetupDialog(readiness)
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.nearby_room_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.nearby_room_create_title)
            .setMessage(R.string.nearby_room_create_warning)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.nearby_rooms_create, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = input.text?.toString().orEmpty()
                        if (!NearbyRoomPolicy.isValidRoomName(name)) {
                            toast(getString(R.string.nearby_room_name_required))
                            return@setOnClickListener
                        }
                        Thread {
                            val room = runCatching {
                                repository.createRoom(
                                    name = name,
                                    ownerDisplayName = localUserProfileStore.load()?.displayName,
                                )
                            }
                            runOnUiThread {
                                val createdRoom = room.getOrNull()
                                if (createdRoom == null) {
                                    val message = if (room.exceptionOrNull() is IllegalStateException) {
                                        getString(R.string.nearby_room_create_existing_owner_room)
                                    } else {
                                        getString(R.string.nearby_room_create_failed)
                                    }
                                    toast(message)
                                    return@runOnUiThread
                                }
                                dialog.dismiss()
                                toast(getString(R.string.nearby_room_created))
                                requestRoomsRefresh(
                                    adapter = adapter,
                                    introBlock = introBlock,
                                    empty = empty,
                                    loading = findViewById(R.id.nearby_rooms_loading),
                                    create = findViewById(R.id.nearby_rooms_create),
                                    createHelper = findViewById(R.id.nearby_rooms_create_helper),
                                )
                                NearbyRoomPendingJoinIntentManager.clear(
                                    applicationContext,
                                    reason = "created_local_room",
                                )
                                startActivity(Intent(this, NearbyRoomActivity::class.java).apply {
                                    putExtra(NearbyRoomActivity.EXTRA_ROOM_ID, createdRoom.room_id)
                                })
                            }
                        }.start()
                    }
                }
                dialog.show()
            }
    }

    private fun startDiscoveryUi(
        adapter: NearbyRoomAdapter,
        introBlock: LinearLayout,
        empty: LinearLayout,
        loading: NearbyRoomsLoadingDotsView,
    ) {
        ActiveNearbyRoomSessionManager.enterDiscovery(applicationContext)
        NearbyRoomBleCoordinator.clearActiveRoomAdvertisement(applicationContext)
        triggerDiscoveryRefresh(
            adapter = adapter,
            introBlock = introBlock,
            empty = empty,
            loading = loading,
            createHelper = findViewById(R.id.nearby_rooms_create_helper),
            isManual = false,
        )
        startRefreshLoop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NEARBY_DISCOVERY_PERM || requestCode == REQ_NEARBY_CREATE_PERM) {
            refreshNearbyDiscoveryReadiness()
        }
    }

    private fun refreshNearbyDiscoveryReadiness() {
        val adapter = findViewById<RecyclerView>(R.id.nearby_rooms_list).adapter as NearbyRoomAdapter
        val introBlock = findViewById<LinearLayout>(R.id.nearby_rooms_intro_block)
        val empty = findViewById<LinearLayout>(R.id.nearby_rooms_empty)
        val loading = findViewById<NearbyRoomsLoadingDotsView>(R.id.nearby_rooms_loading)
        val readiness = NearbyReadinessHelper.evaluate(applicationContext)
        if (readiness.canDiscover) {
            startDiscoveryUi(adapter, introBlock, empty, loading)
            if (pendingCreateAfterSetup && readiness.canCreateRoom) {
                pendingCreateAfterSetup = false
                showCreateDialog(adapter, introBlock, empty)
            }
        } else {
            stopRefreshLoop()
            stopPendingDiscoveryRefresh()
            ActiveNearbyRoomSessionManager.leaveDiscovery(applicationContext)
            NearbyRoomBleCoordinator.clearActiveRoomAdvertisement(applicationContext)
            loading.setLoadingVisible(false)
            updateDiscoveryState(NearbyDiscoveryUiState.Paused(readiness.blockingReasons))
        }
    }

    private fun updateDiscoveryState(state: NearbyDiscoveryUiState) {
        discoveryUiState = state
        val card = findViewById<View>(R.id.nearby_rooms_setup_card)
        card.visibility = View.VISIBLE
        val statusDot = findViewById<View>(R.id.nearby_rooms_setup_status_dot)
        val title = findViewById<TextView>(R.id.nearby_rooms_setup_title)
        val body = findViewById<TextView>(R.id.nearby_rooms_setup_body)
        val detail = findViewById<TextView>(R.id.nearby_rooms_setup_checklist)
        val grant = findViewById<Button>(R.id.nearby_rooms_setup_grant_permission)
        val bluetooth = findViewById<Button>(R.id.nearby_rooms_setup_enable_bluetooth)
        val location = findViewById<Button>(R.id.nearby_rooms_setup_enable_location)
        val introBlock = findViewById<LinearLayout>(R.id.nearby_rooms_intro_block)
        val empty = findViewById<LinearLayout>(R.id.nearby_rooms_empty)
        val create = findViewById<Button>(R.id.nearby_rooms_create)
        val createHelper = findViewById<TextView>(R.id.nearby_rooms_create_helper)
        val emptyBody = findViewById<TextView>(R.id.nearby_rooms_empty_body)
        val emptyTip = findViewById<TextView>(R.id.nearby_rooms_empty_tip)

        detail.visibility = View.GONE
        grant.visibility = View.GONE
        bluetooth.visibility = View.GONE
        location.visibility = View.GONE
        emptyBody.text = getString(R.string.nearby_rooms_empty_body)
        emptyTip.visibility = View.VISIBLE

        when (state) {
            NearbyDiscoveryUiState.Searching -> {
                title.text = getString(R.string.nearby_discovery_active_title)
                body.text = getString(R.string.nearby_discovery_searching_body)
                detail.text = getString(R.string.nearby_discovery_searching_detail)
                detail.visibility = View.VISIBLE
            }
            is NearbyDiscoveryUiState.Results -> {
                title.text = getString(R.string.nearby_discovery_active_title)
                body.text = resources.getQuantityString(
                    R.plurals.nearby_discovery_rooms_found,
                    state.roomsCount,
                    state.roomsCount,
                )
                detail.text = getString(R.string.nearby_discovery_results_detail)
                detail.visibility = View.VISIBLE
            }
            NearbyDiscoveryUiState.Empty -> {
                title.text = getString(R.string.nearby_discovery_active_title)
                body.text = getString(R.string.nearby_discovery_searching_body)
                detail.text = getString(R.string.nearby_discovery_searching_detail)
                detail.visibility = View.VISIBLE
                emptyBody.text = buildString {
                    append(getString(R.string.nearby_rooms_empty_body))
                    append("\n\n")
                    append(getString(R.string.nearby_rooms_empty_secondary))
                }
            }
            is NearbyDiscoveryUiState.Paused -> {
                val readiness = NearbyReadinessHelper.evaluate(applicationContext)
                when {
                    NearbyReadinessHelper.missingPermissionsForDiscovery(applicationContext).isNotEmpty() -> {
                        title.text = getString(R.string.nearby_discovery_paused_title)
                        body.text = getString(R.string.nearby_discovery_paused_permission_body)
                        grant.visibility = View.VISIBLE
                        grant.setOnClickListener { requestNearbyPermissionsForDiscovery() }
                    }
                    state.reasons.contains(app.peermesh.nearby.NearbyBlockingReason.BLUETOOTH_DISABLED) -> {
                        title.text = getString(R.string.nearby_discovery_paused_title)
                        body.text = getString(R.string.nearby_discovery_paused_bluetooth_body)
                        bluetooth.visibility = View.VISIBLE
                        bluetooth.setOnClickListener { openBluetoothSettings() }
                    }
                    readiness.locationRequired && state.reasons.any {
                        it == app.peermesh.nearby.NearbyBlockingReason.LOCATION_PERMISSION ||
                            it == app.peermesh.nearby.NearbyBlockingReason.LOCATION_SERVICES
                    } -> {
                        title.text = getString(R.string.nearby_discovery_paused_title)
                        body.text = getString(R.string.nearby_discovery_paused_location_body)
                        if (!readiness.locationServicesEnabled) {
                            location.visibility = View.VISIBLE
                            location.setOnClickListener { openLocationSettings() }
                        } else if (!readiness.locationPermissionGranted) {
                            grant.visibility = View.VISIBLE
                            grant.setOnClickListener { requestNearbyPermissionsForDiscovery() }
                        }
                    }
                    else -> {
                        title.text = getString(R.string.nearby_discovery_paused_title)
                        body.text = getString(R.string.nearby_rooms_setup_body)
                    }
                }
            }
            is NearbyDiscoveryUiState.Error -> {
                title.text = getString(R.string.nearby_discovery_error_title)
                body.text = getString(R.string.nearby_discovery_error_body)
                bluetooth.visibility = View.VISIBLE
                bluetooth.text = getString(R.string.nearby_discovery_retry)
                bluetooth.setOnClickListener { refreshNearbyDiscoveryReadiness() }
            }
        }
        renderSetupStatusDot(statusDot, state)
        renderPrimaryDiscoveryContent(introBlock, empty, create, createHelper)
    }

    private fun renderPrimaryDiscoveryContent(
        introBlock: LinearLayout,
        empty: LinearLayout,
        create: Button,
        createHelper: TextView,
    ) {
        val hasRooms = lastKnownRoomCount > 0
        val activeDiscovery = discoveryUiState == NearbyDiscoveryUiState.Searching ||
            discoveryUiState == NearbyDiscoveryUiState.Empty ||
            discoveryUiState is NearbyDiscoveryUiState.Results
        introBlock.visibility = if (activeDiscovery && hasRooms) View.VISIBLE else View.GONE
        empty.visibility = if (activeDiscovery && !hasRooms) View.VISIBLE else View.GONE

        val showTopCreate = activeDiscovery && hasRooms && !hasLocalOwnedActiveRoom
        create.visibility = if (showTopCreate) View.VISIBLE else View.GONE
        createHelper.visibility = if (showTopCreate) View.VISIBLE else View.GONE
    }

    private fun renderSetupStatusDot(statusDot: View, state: NearbyDiscoveryUiState) {
        val colorRes = when (state) {
            NearbyDiscoveryUiState.Searching,
            NearbyDiscoveryUiState.Empty,
            is NearbyDiscoveryUiState.Results -> R.color.status_on
            is NearbyDiscoveryUiState.Paused -> R.color.status_paused
            is NearbyDiscoveryUiState.Error -> R.color.status_error
        }
        statusDot.background.mutate().setTint(ContextCompat.getColor(this, colorRes))
        if (state == NearbyDiscoveryUiState.Searching) {
            startSetupStatusPulse(statusDot)
        } else {
            stopSetupStatusPulse()
            statusDot.alpha = 1f
            statusDot.scaleX = 1f
            statusDot.scaleY = 1f
        }
    }

    private fun startSetupStatusPulse(statusDot: View) {
        if (setupStatusPulseAnimator != null) return
        setupStatusPulseAnimator = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 0.45f, 1f, 0.45f).apply {
            duration = 1200L
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopSetupStatusPulse() {
        setupStatusPulseAnimator?.cancel()
        setupStatusPulseAnimator = null
    }

    private fun showCreateSetupDialog(state: app.peermesh.nearby.NearbyReadinessState) {
        val message = buildString {
            append(getString(R.string.nearby_room_setup_required_body))
            if (state.locationRequired && (!state.locationPermissionGranted || !state.locationServicesEnabled)) {
                append("\n\n")
                append(getString(R.string.nearby_room_setup_required_location_body))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.nearby_room_setup_required_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingCreateAfterSetup = false
            }
            .setPositiveButton(R.string.nearby_room_setup_continue) { _, _ ->
                continueNearbyCreateSetup()
            }
            .show()
    }

    private fun continueNearbyCreateSetup() {
        val readiness = NearbyReadinessHelper.evaluate(applicationContext)
        val missingPermissions = NearbyReadinessHelper.missingPermissionsForCreate(applicationContext)
        when {
            missingPermissions.isNotEmpty() -> {
                ActivityCompat.requestPermissions(this, missingPermissions, REQ_NEARBY_CREATE_PERM)
            }
            !readiness.bluetoothEnabled -> openBluetoothSettings()
            readiness.locationRequired && !readiness.locationServicesEnabled -> openLocationSettings()
        }
    }

    private fun requestNearbyPermissionsForDiscovery() {
        val missingPermissions = NearbyReadinessHelper.missingPermissionsForDiscovery(applicationContext)
        if (missingPermissions.isEmpty()) return
        ActivityCompat.requestPermissions(this, missingPermissions, REQ_NEARBY_DISCOVERY_PERM)
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun registerStatusReceiverIfNeeded() {
        if (statusReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(android.location.LocationManager.MODE_CHANGED_ACTION)
            addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        statusReceiverRegistered = true
    }

    private fun unregisterStatusReceiverIfNeeded() {
        if (!statusReceiverRegistered) return
        unregisterReceiver(statusReceiver)
        statusReceiverRegistered = false
    }

    private fun renderOnboarding(
        displayNameInput: EditText,
        displayNameError: TextView,
        avatarPreviewContainer: FrameLayout,
        avatarPreview: TextView,
        avatarValue: TextView,
        avatarError: TextView,
        continueButton: Button,
    ) {
        val displayName = displayNameInput.text?.toString().orEmpty()
        val displayNameErrorCode = LocalUserProfileValidator.validateDisplayName(displayName)
        val resolvedName = LocalUserProfileValidator.sanitizeDisplayName(displayName)
            .ifBlank { getString(R.string.nearby_user_fallback) }
        LocalUserProfileUi.bindAvatarPreview(
            activity = this,
            avatarId = onboardingSelectedAvatarId,
            displayName = resolvedName,
            emojiView = avatarPreview,
            labelView = avatarValue,
        )
        val avatarColor = AvatarUtils.avatarColorForKey(onboardingSelectedAvatarId ?: resolvedName)
        avatarPreviewContainer.background.setTint(avatarColor)
        avatarPreview.setTextColor(AvatarUtils.avatarTextColor(avatarColor))
        displayNameError.text = when (displayNameErrorCode) {
            "required" -> getString(R.string.profile_display_name_required)
            "too_short" -> getString(R.string.profile_display_name_too_short)
            "too_long" -> getString(R.string.profile_display_name_too_long)
            else -> ""
        }
        displayNameError.visibility = if (displayNameErrorCode == null) android.view.View.GONE else android.view.View.VISIBLE
        avatarError.text = getString(R.string.profile_avatar_required)
        avatarError.visibility = if (onboardingSelectedAvatarId.isNullOrBlank()) android.view.View.VISIBLE else android.view.View.GONE
        continueButton.isEnabled = displayNameErrorCode == null && !onboardingSelectedAvatarId.isNullOrBlank()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun attachEdgeRefreshGesture(
        list: RecyclerView,
        adapter: NearbyRoomAdapter,
        introBlock: LinearLayout,
        empty: LinearLayout,
        loading: NearbyRoomsLoadingDotsView,
    ) {
        list.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val startY = gestureStartY
                    gestureStartY = null
                    if (startY != null) {
                        val deltaY = event.y - startY
                        val isAtTop = !list.canScrollVertically(-1)
                        val isAtBottom = !list.canScrollVertically(1)
                        val pullDownAtTop = isAtTop && deltaY > EDGE_REFRESH_THRESHOLD_PX
                        val pushUpAtBottom = isAtBottom && deltaY < -EDGE_REFRESH_THRESHOLD_PX
                        if (pullDownAtTop || pushUpAtBottom) {
                            ActivityLog.add(
                                label = "Nearby rooms refresh gesture",
                                source = "nearby",
                                message = "edge=${if (pullDownAtTop) "top" else "bottom"} delta_y=${deltaY.toInt()}",
                            )
                            triggerDiscoveryRefresh(
                                adapter,
                                introBlock,
                                empty,
                                loading,
                                findViewById(R.id.nearby_rooms_create_helper),
                                isManual = true,
                            )
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    gestureStartY = null
                }
            }
            false
        }
    }

    companion object {
        private const val REQ_NEARBY_DISCOVERY_PERM = 4101
        private const val REQ_NEARBY_CREATE_PERM = 4102
        private const val REFRESH_INTERVAL_MS = 60_000L
        private const val EDGE_REFRESH_THRESHOLD_PX = 72f
        private const val MANUAL_REFRESH_COOLDOWN_MS = 30_000L
        private const val DISCOVERY_BURST_DURATION_MS = 4_000L
        private const val DISCOVERY_RESULT_SETTLE_DELAY_MS = 4_500L
    }
}
