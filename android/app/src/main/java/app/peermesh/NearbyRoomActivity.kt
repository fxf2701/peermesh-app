package app.peermesh

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.data.db.entity.NearbyRoomParticipantEntity
import app.peermesh.core.groupinvitation.GroupInvitationProtocol
import app.peermesh.nearby.ActiveNearbyRoomSessionManager
import app.peermesh.nearby.BleModeController
import app.peermesh.nearby.NearbyRoomBleCoordinator
import app.peermesh.nearby.NearbyRoomExpiryManager
import app.peermesh.nearby.NearbyRoomOwnerMessageRetryCoordinator
import app.peermesh.nearby.NearbyRoomPendingJoinIntentManager
import app.peermesh.nearby.NearbyRoomPeerSnapshotStore
import app.peermesh.nearby.NearbyRoomPolicy
import app.peermesh.nearby.NearbyReadinessHelper
import app.peermesh.nearby.NearbyReadinessState
import app.peermesh.nearby.NearbyRoomRepository
import app.peermesh.nearby.NearbyRoomRelayRecoveryManager
import app.peermesh.nearby.NearbyRoomTransportManager
import app.peermesh.nearby.NearbyRoomUiNoticeStore
import app.peermesh.nearby.NearbyPrivateGroupInvitationLocalAudienceRole
import app.peermesh.nearby.NearbyGroupInvitationLocalVisibilityResult
import app.peermesh.nearby.presentation.NearbyPrivateGroupInvitationPresentationContext
import app.peermesh.nearby.presentation.NearbyPrivateGroupInvitationPresentationMapper
import app.peermesh.nearby.presentation.NearbyPrivateGroupInvitationTimelineProjection
import app.peermesh.nearby.presentation.NearbyPublicGroupInvitationPresentationMapper
import app.peermesh.nearby.presentation.NearbyPublicGroupInvitationTimelineProjection
import app.peermesh.nearby.presentation.NearbyRoomTimelineItem
import app.peermesh.nearby.presentation.PrivateInvitationAction
import app.peermesh.nearby.presentation.PrivateInvitationAuthorizedAudience
import app.peermesh.nearby.NearbyForegroundRuntimeCoordinator
import app.peermesh.nearby.NearbyForegroundRuntimeStateMachine
import app.peermesh.moderation.ModerationRepository

class NearbyRoomActivity : AppCompatActivity() {
    private val repository by lazy { NearbyRoomRepository(applicationContext) }
    private val transportManager by lazy { NearbyRoomTransportManager(applicationContext, repository) }
    private val moderationRepository by lazy { ModerationRepository(applicationContext) }
    private val localUserProfileStore by lazy { LocalUserProfileStore(applicationContext) }
    private val privateInvitationPresentationMapper = NearbyPrivateGroupInvitationPresentationMapper()
    private val publicInvitationPresentationMapper = NearbyPublicGroupInvitationPresentationMapper()
    private val expiryManager = NearbyRoomExpiryManager()
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var lastKeyRequestAtMs: Long = 0L
    private var joinAccessRetryUntilMs: Long = 0L
    private var joinAccessTimeoutHandled = false
    private var latestAccessState = RoomAccessState(
        isLocalOwner = false,
        isKeyReady = false,
        isMetadataReady = false,
        isChatAccessReady = false,
    )
    private var closeNoticeDialogShown = false
    private var expiredDialogShown = false
    private var closedRoomDialogHandled = false
    private var lastMetadataRefreshEnqueueAtMs: Long = 0L
    private var selectedReplyMessageId: String? = null
    private var selectedReplyMessagePreview: NearbyRoomMessageEntity? = null
    private var selectedEditMessageId: String? = null
    private var selectedEditMessagePreview: NearbyRoomMessageEntity? = null
    private var replyJumpScrollListener: RecyclerView.OnScrollListener? = null
    private var runtimeAttemptId: String? = null
    private var runtimeSessionId: String? = null
    private var runtimeReleased = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_room)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        if (roomId.isNullOrBlank()) {
            finish()
            return
        }
        val runtimeState = NearbyForegroundRuntimeCoordinator.currentState()
        when {
            runtimeState is NearbyForegroundRuntimeStateMachine.State.Joining && runtimeState.roomId == roomId -> {
                runtimeAttemptId = runtimeState.attemptId
            }
            runtimeState is NearbyForegroundRuntimeStateMachine.State.ActiveRoom && runtimeState.roomId == roomId -> {
                runtimeSessionId = runtimeState.sessionId
            }
            runtimeState is NearbyForegroundRuntimeStateMachine.State.Joining ||
                runtimeState is NearbyForegroundRuntimeStateMachine.State.ActiveRoom -> {
                ActivityLog.add(
                    label = "Nearby foreground runtime",
                    source = "nearby",
                    message = "action=stale_activity_ignored room_id=$roomId active_state=${runtimeState.javaClass.simpleName}",
                )
                finish()
                return
            }
            else -> {
                runtimeAttemptId = NearbyForegroundRuntimeCoordinator.beginJoining(
                    context = applicationContext,
                    roomId = roomId,
                    reason = "room_activity_create",
                    attemptId = intent.getStringExtra(EXTRA_JOIN_ATTEMPT_ID)
                        ?: savedInstanceState?.getString(STATE_JOIN_ATTEMPT_ID)
                        ?: java.util.UUID.randomUUID().toString(),
                )
            }
        }
        if (!intent.getBooleanExtra(EXTRA_AUTO_RESUMED_JOIN, false)) {
            Thread {
                repository.getRoom(roomId)?.let { room ->
                    if (!room.is_local_owner) {
                        NearbyRoomPendingJoinIntentManager.recordManualJoinIntent(
                            context = applicationContext,
                            roomId = roomId,
                            source = "room_activity_open",
                        )
                    }
                }
            }.start()
        }

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val expiry = findViewById<TextView>(R.id.nearby_room_expiry)
        val participantSummary = findViewById<TextView>(R.id.nearby_room_participant_summary)
        val empty = findViewById<TextView>(R.id.nearby_room_messages_empty)
        val list = findViewById<RecyclerView>(R.id.nearby_room_messages_list)
        val input = findViewById<EditText>(R.id.nearby_room_message_input)
        val send = findViewById<ImageButton>(R.id.nearby_room_message_send)
        val state = findViewById<TextView>(R.id.nearby_room_state_line)
        val composeModeBar = findViewById<View>(R.id.nearby_room_compose_mode_bar)
        val composeModeLabel = findViewById<TextView>(R.id.nearby_room_compose_mode_label)
        val composeModeClose = findViewById<ImageButton>(R.id.nearby_room_compose_mode_close)
        val accessLoadingOverlay = findViewById<FrameLayout>(R.id.nearby_room_access_loading_overlay)
        val accessLoadingDots = findViewById<NearbyRoomsLoadingDotsView>(R.id.nearby_room_access_loading_dots)

        lateinit var adapter: NearbyRoomMessageAdapter
        adapter = NearbyRoomMessageAdapter(
            currentDeviceId = repository.localDeviceId(),
            onReplyRequested = { message -> startReply(message, input) },
            onReplyPreviewClicked = { messageId -> scrollToMessage(messageId, list, adapter) },
            onBlockRequested = { message ->
                showBlockPeerDialog(
                    roomId = roomId,
                    message = message,
                    adapter = adapter,
                    emptyView = empty,
                    listView = list,
                    input = input,
                    composeModeBar = composeModeBar,
                )
            },
            onMuteRequested = { message ->
                val targetDeviceId = message.owner_device_id ?: message.sender_device_id
                val displayName = LocalUserProfileUi.resolveDisplayName(
                    rawDisplayName = message.sender_display_name,
                    isLocalUser = targetDeviceId == repository.localDeviceId(),
                    localProfile = localUserProfileStore.load(),
                )
                confirmNearbyRoomMute(
                    roomId = roomId,
                    peerId = targetDeviceId,
                    displayName = displayName,
                    onUpdated = { loadMessages(roomId, adapter, empty, list) },
                )
            },
            onReportMessageRequested = { message -> showReportMessageDialog(roomId, message, adapter, empty, list, input, composeModeBar) },
            onReportPeerRequested = { message ->
                val targetDeviceId = message.owner_device_id ?: message.sender_device_id
                showReportPeerDialog(roomId, targetDeviceId, adapter, empty, list, input, composeModeBar)
            },
            onEditRequested = { message -> startEdit(message, input, composeModeBar, composeModeLabel) },
            onDeleteRequested = { message ->
                confirmDeleteMessage(
                    roomId = roomId,
                    message = message,
                    adapter = adapter,
                    emptyView = empty,
                    listView = list,
                    input = input,
                    composeModeBar = composeModeBar,
                )
            },
            onPublicInvitationOpen = { messageId ->
                startActivity(NearbyRoomInvitationsActivity.intent(this, roomId, messageId))
            },
            onPublicInvitationJoin = { messageId ->
                NearbyPublicGroupInvitationJoin.open(this, roomId, messageId)
            },
            onPublicInvitationHide = { messageId ->
                Thread {
                    val result = repository.setPublicGroupInvitationHiddenLocally(
                        roomId = roomId,
                        messageId = messageId,
                        hidden = true,
                    )
                    runOnUiThread {
                        when (result) {
                            NearbyGroupInvitationLocalVisibilityResult.Updated,
                            NearbyGroupInvitationLocalVisibilityResult.AlreadyInRequestedState,
                            NearbyGroupInvitationLocalVisibilityResult.MessageNotFound -> Unit
                            NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation,
                            NearbyGroupInvitationLocalVisibilityResult.NotAuthorizedViewer,
                            NearbyGroupInvitationLocalVisibilityResult.InvitationInvalid ->
                                toast(getString(R.string.nearby_public_invitation_hide_failed))
                        }
                        loadMessages(roomId, adapter, empty, list)
                    }
                }.start()
            },
            onPublicInvitationUnhide = { messageId ->
                Thread {
                    val result = repository.setPublicGroupInvitationHiddenLocally(
                        roomId = roomId,
                        messageId = messageId,
                        hidden = false,
                    )
                    runOnUiThread {
                        when (result) {
                            NearbyGroupInvitationLocalVisibilityResult.Updated,
                            NearbyGroupInvitationLocalVisibilityResult.AlreadyInRequestedState,
                            NearbyGroupInvitationLocalVisibilityResult.MessageNotFound -> Unit
                            NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation,
                            NearbyGroupInvitationLocalVisibilityResult.NotAuthorizedViewer,
                            NearbyGroupInvitationLocalVisibilityResult.InvitationInvalid ->
                                toast(getString(R.string.nearby_public_invitation_unhide_failed))
                        }
                        loadMessages(roomId, adapter, empty, list)
                    }
                }.start()
            },
            onPrivateInvitationAction = { messageId, senderId, action ->
                handlePrivateInvitationAction(
                    roomId = roomId,
                    messageId = messageId,
                    senderId = senderId,
                    action = action,
                    adapter = adapter,
                    emptyView = empty,
                    listView = list,
                    input = input,
                    composeModeBar = composeModeBar,
                )
            },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        composeModeClose.setOnClickListener {
            clearComposeMode(input, composeModeBar)
        }
        composeModeBar.setOnClickListener {
            selectedReplyMessageId?.let { messageId -> scrollToMessage(messageId, list, adapter) }
        }

        headerMenu.setOnClickListener {
            showRoomMenu(roomId)
        }
        send.setOnClickListener {
            val readiness = NearbyReadinessHelper.evaluate(applicationContext)
            if (!readiness.canUseRoom) {
                refreshNearbyReadinessUi(readiness)
                toast(
                    getString(
                        if (readiness.locationRequired && !readiness.locationServicesEnabled) {
                            R.string.nearby_room_send_paused_location
                        } else {
                            R.string.nearby_room_send_paused
                        }
                    )
                )
                return@setOnClickListener
            }
            val body = input.text?.toString().orEmpty()
            if (!NearbyRoomPolicy.isValidMessageBody(body)) {
                toast(getString(R.string.nearby_room_message_required))
                return@setOnClickListener
            }
            val editingPreview = selectedEditMessagePreview
            val sanitizedBody = NearbyRoomPolicy.sanitizeMessageBody(body)
            if (editingPreview != null && editingPreview.body == sanitizedBody) {
                clearComposeMode(input, composeModeBar)
                return@setOnClickListener
            }
            Thread {
                if (!BleModeController.shouldProcessActiveRoomTransport(applicationContext, roomId)) {
                    runOnUiThread {
                        toast(getString(R.string.nearby_room_inactive_state))
                    }
                    return@Thread
                }
                ensureSecurityState(roomId)
                val editingMessageId = selectedEditMessageId
                val message = if (editingMessageId != null) {
                    repository.editLocalMessage(roomId, editingMessageId, body)
                } else {
                    repository.postLocalMessage(roomId, body, replyToMessageId = selectedReplyMessageId)
                }
                val room = repository.getRoom(roomId)
                val ownerTargetDeviceIds = if (room?.is_local_owner == true) {
                    NearbyRoomPeerSnapshotStore.nearbyDeviceIdsForRoom(roomId, limit = OWNER_SEND_TARGET_LIMIT)
                        .filterNot { it == repository.localDeviceId() }
                        .toSet()
                        .takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                val sent = if (message != null) {
                    transportManager.sendEncryptedMessage(
                        message = message,
                        targetDeviceIds = ownerTargetDeviceIds,
                    )
                } else {
                    false
                }
                if (message != null && sent) {
                    repository.markMessageSent(message.room_id, message.message_id)
                    NearbyRoomRelayRecoveryManager.onMessageAvailable(
                        context = applicationContext,
                        message = message,
                        lastHopSenderDeviceId = null,
                    )
                } else if (message != null && room?.is_local_owner == true) {
                    NearbyRoomOwnerMessageRetryCoordinator.enqueue(
                        context = applicationContext,
                        roomId = message.room_id,
                        messageId = message.message_id,
                    )
                }
                val localMutedInRoom = message == null && repository.isLocalDeviceMutedInRoom(roomId)
                runOnUiThread {
                    if (message == null) {
                        toast(
                            if (localMutedInRoom) {
                                getString(R.string.nearby_room_muted_send_blocked)
                            } else {
                                getString(
                                    if (editingMessageId != null) {
                                        R.string.nearby_room_edit_failed
                                    } else {
                                        R.string.nearby_room_send_failed
                                    }
                                )
                            }
                        )
                    } else if (!sent && room?.is_local_owner != true) {
                        toast(
                            getString(
                                if (editingMessageId != null) {
                                    R.string.nearby_room_edit_failed
                                } else {
                                    R.string.nearby_room_send_failed
                                }
                            )
                        )
                        loadRoom(roomId, headerTitle, expiry, participantSummary, state)
                        loadMessages(roomId, adapter, empty, list)
                    } else {
                        input.setText("")
                        clearComposeMode(input, composeModeBar)
                        NearbyRoomBleCoordinator.refreshActiveRoomAdvertisement(applicationContext, roomId)
                        loadRoom(roomId, headerTitle, expiry, participantSummary, state)
                        loadMessages(roomId, adapter, empty, list)
                    }
                }
            }.start()
        }

        state.visibility = View.GONE
        renderComposeMode(composeModeBar, composeModeLabel)
        loadRoom(roomId, headerTitle, expiry, participantSummary, state)
        loadMessages(roomId, adapter, empty, list)
        refreshAccessState(roomId, accessLoadingOverlay, accessLoadingDots)
        refreshNearbyReadinessUi(NearbyReadinessHelper.evaluate(applicationContext))
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
        intent.getStringExtra(EXTRA_ROOM_ID)?.let { roomId ->
            ActiveNearbyRoomSessionManager.enterRoom(applicationContext, roomId)
            NearbyRoomBleCoordinator.refreshActiveRoomAdvertisement(applicationContext, roomId)
            Thread { ensureSecurityState(roomId, force = true) }.start()
            refreshAccessState(
                roomId = roomId,
                overlay = findViewById(R.id.nearby_room_access_loading_overlay),
                loadingDots = findViewById(R.id.nearby_room_access_loading_dots),
            )
            refreshNearbyReadinessUi(NearbyReadinessHelper.evaluate(applicationContext))
        }
        startTimer()
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
        stopTimer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        runtimeAttemptId?.let { outState.putString(STATE_JOIN_ATTEMPT_ID, it) }
        runtimeSessionId?.let { outState.putString(STATE_ROOM_SESSION_ID, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        replyJumpScrollListener?.let { findViewById<RecyclerView>(R.id.nearby_room_messages_list).removeOnScrollListener(it) }
        replyJumpScrollListener = null
        if (!isChangingConfigurations) {
            intent.getStringExtra(EXTRA_ROOM_ID)?.let { roomId ->
                releaseNearbyRuntime(roomId, "room_activity_destroyed")
                ActiveNearbyRoomSessionManager.leaveRoom(applicationContext, roomId)
                NearbyRoomBleCoordinator.clearActiveRoomAdvertisement(applicationContext)
            }
        }
        super.onDestroy()
    }

    private fun startTimer() {
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: return
        val expiry = findViewById<TextView>(R.id.nearby_room_expiry)
        val participantSummary = findViewById<TextView>(R.id.nearby_room_participant_summary)
        val state = findViewById<TextView>(R.id.nearby_room_state_line)
        val headerTitle = findViewById<TextView>(R.id.header_title)
        val empty = findViewById<TextView>(R.id.nearby_room_messages_empty)
        val list = findViewById<RecyclerView>(R.id.nearby_room_messages_list)
        val accessLoadingOverlay = findViewById<FrameLayout>(R.id.nearby_room_access_loading_overlay)
        val accessLoadingDots = findViewById<NearbyRoomsLoadingDotsView>(R.id.nearby_room_access_loading_dots)
        val adapter = list.adapter as? NearbyRoomMessageAdapter
        timerRunnable = object : Runnable {
            override fun run() {
                loadRoom(roomId, headerTitle, expiry, participantSummary, state)
                if (adapter != null) {
                    loadMessages(roomId, adapter, empty, list)
                }
                refreshAccessState(roomId, accessLoadingOverlay, accessLoadingDots)
                refreshNearbyReadinessUi(NearbyReadinessHelper.evaluate(applicationContext))
                val accessState = latestAccessState
                if (accessState.isKeyReady) {
                    joinAccessRetryUntilMs = 0L
                    joinAccessTimeoutHandled = false
                } else if (!accessState.isLocalOwner &&
                    !joinAccessTimeoutHandled &&
                    joinAccessRetryUntilMs > 0L &&
                    System.currentTimeMillis() > joinAccessRetryUntilMs
                ) {
                    joinAccessTimeoutHandled = true
                    runOnUiThread {
                        toast(getString(R.string.nearby_room_access_timeout))
                        navigateToDiscoveryOnClose(roomId)
                    }
                    return
                }
                val shouldForceRetry = !accessState.isKeyReady && System.currentTimeMillis() <= joinAccessRetryUntilMs
                Thread { ensureSecurityState(roomId, force = shouldForceRetry) }.start()
                handler.postDelayed(this, 1000L)
            }
        }.also { handler.post(it) }
    }

    private fun stopTimer() {
        timerRunnable?.let(handler::removeCallbacks)
        timerRunnable = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NEARBY_ROOM_PERM) {
            refreshNearbyReadinessUi(NearbyReadinessHelper.evaluate(applicationContext))
        }
    }

    private fun refreshNearbyReadinessUi(readiness: NearbyReadinessState) {
        val banner = findViewById<LinearLayout>(R.id.nearby_room_readiness_banner)
        val bannerText = findViewById<TextView>(R.id.nearby_room_readiness_banner_text)
        val bannerAction = findViewById<android.widget.Button>(R.id.nearby_room_readiness_banner_action)
        val input = findViewById<EditText>(R.id.nearby_room_message_input)
        val send = findViewById<ImageButton>(R.id.nearby_room_message_send)
        val paused = !readiness.canUseRoom

        banner.visibility = if (paused) View.VISIBLE else View.GONE
        input.isEnabled = !paused
        send.isEnabled = !paused
        send.alpha = if (paused) 0.45f else 1f

        if (!paused) {
            bannerAction.visibility = View.GONE
            return
        }

        when {
            NearbyReadinessHelper.missingPermissionsForRoom(applicationContext).isNotEmpty() -> {
                bannerText.text = getString(R.string.nearby_room_connection_paused_bluetooth)
                bannerAction.visibility = View.VISIBLE
                bannerAction.text = getString(R.string.nearby_setup_grant_permission)
                bannerAction.setOnClickListener { requestNearbyRoomPermissions() }
            }
            !readiness.bluetoothEnabled -> {
                bannerText.text = getString(R.string.nearby_room_connection_paused_bluetooth)
                bannerAction.visibility = View.VISIBLE
                bannerAction.text = getString(R.string.nearby_setup_enable_bluetooth)
                bannerAction.setOnClickListener { openBluetoothSettings() }
            }
            readiness.locationRequired && !readiness.locationServicesEnabled -> {
                bannerText.text = getString(R.string.nearby_room_connection_paused_location)
                bannerAction.visibility = View.VISIBLE
                bannerAction.text = getString(R.string.nearby_setup_turn_on_location)
                bannerAction.setOnClickListener { openLocationSettings() }
            }
            else -> {
                bannerText.text = getString(R.string.nearby_room_connection_paused_bluetooth)
                bannerAction.visibility = View.GONE
            }
        }
    }

    private fun requestNearbyRoomPermissions() {
        val permissions = NearbyReadinessHelper.missingPermissionsForRoom(applicationContext)
        if (permissions.isEmpty()) return
        ActivityCompat.requestPermissions(this, permissions, REQ_NEARBY_ROOM_PERM)
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun loadRoom(
        roomId: String,
        headerTitle: TextView,
        expiry: TextView,
        participantSummary: TextView,
        state: TextView,
    ) {
        Thread {
            val room = repository.getRoom(roomId)
            val participants = repository.listParticipants(roomId)
            maybeEnqueueRoomMetadataRefresh(room)
            runOnUiThread {
                if (room == null) {
                    if (NearbyRoomUiNoticeStore.consumeOwnerClosedNotice(applicationContext, roomId)) {
                        showOwnerClosedDialog(roomId)
                    } else if (closeNoticeDialogShown || closedRoomDialogHandled) {
                        return@runOnUiThread
                    } else {
                        finish()
                    }
                    return@runOnUiThread
                }
                if (room.state == NearbyRoomEntity.STATE_CLOSED) {
                    if (room.is_local_owner) {
                        navigateToDiscoveryOnClose(roomId)
                    } else {
                        showOwnerClosedDialog(roomId)
                    }
                    return@runOnUiThread
                }
                if (room.state == NearbyRoomEntity.STATE_EXPIRED) {
                    showExpiredDialog(roomId)
                    return@runOnUiThread
                }
                NearbyRoomBleCoordinator.refreshActiveRoomAdvertisement(applicationContext, roomId)
                headerTitle.text = room.name
                val participantCount = room.participant_count ?: participants.size
                val nearbyPeople = resources.getQuantityString(
                    R.plurals.nearby_room_people_nearby,
                    participantCount,
                    participantCount,
                )
                participantSummary.text = getString(
                    R.string.nearby_room_mesh_status_summary,
                    nearbyPeople,
                    expiryManager.formatRemaining(room),
                )
                when (room.state) {
                    NearbyRoomEntity.STATE_CLOSED -> {
                        expiry.text = getString(R.string.nearby_room_invite_cta)
                        state.visibility = View.VISIBLE
                    }
                    NearbyRoomEntity.STATE_EXPIRED -> {
                        expiry.text = getString(R.string.nearby_room_invite_cta)
                        state.visibility = View.VISIBLE
                    }
                    else -> {
                        expiry.text = getString(R.string.nearby_room_invite_cta)
                        state.visibility = View.GONE
                    }
                }
                if (room.state != NearbyRoomEntity.STATE_ACTIVE) {
                    state.visibility = View.VISIBLE
                    state.text = getString(R.string.nearby_room_inactive_state)
                }
                if (latestAccessState.isKeyReady) {
                    joinAccessRetryUntilMs = 0L
                }
            }
        }.start()
    }

    private fun maybeEnqueueRoomMetadataRefresh(room: NearbyRoomEntity?) {
        if (room == null) return
        if (room.is_local_owner) return
        if (room.state != NearbyRoomEntity.STATE_ACTIVE) return
        if (room.metadata_status == NearbyRoomEntity.METADATA_RESOLVED) return

        val nowMs = System.currentTimeMillis()
        val lastAttemptAtMs = maxOf(room.metadata_last_fetched_at ?: 0L, lastMetadataRefreshEnqueueAtMs)
        if (lastAttemptAtMs > 0L &&
            nowMs - lastAttemptAtMs < NearbyRoomRepository.METADATA_FETCH_RETRY_INTERVAL_MS
        ) {
            ActivityLog.add(
                label = "Nearby room metadata refresh skipped",
                source = "nearby",
                message =
                    "room_id=${room.room_id} reason=room_screen_throttle status=${room.metadata_status} last_attempt_at_ms=$lastAttemptAtMs",
            )
            return
        }

        lastMetadataRefreshEnqueueAtMs = nowMs
        NearbyRoomBleCoordinator.enqueueMetadataRefresh(applicationContext, listOf(room.room_id))
    }

    private fun loadMessages(
        roomId: String,
        adapter: NearbyRoomMessageAdapter,
        empty: TextView,
        list: RecyclerView,
    ) {
        Thread {
            val messages = repository.listMessages(roomId)
            val room = repository.getRoom(roomId)
            val participants = repository.listParticipants(roomId)
            val mutedPeerIds = repository.listMutedPeerIdsInRoom(roomId)
            val localProfile = localUserProfileStore.load()
            val participantProfiles = participants.associate { participant ->
                participant.device_id to NearbyRoomMessageAdapter.ParticipantProfileUi(
                    displayName = participant.display_name,
                    avatarId = participant.avatar_id,
                )
            }
            val timelineItems = messages.mapNotNull { message ->
                val inspection = repository.inspectPrivateGroupInvitationForTimeline(roomId, message.message_id)
                val mapperContext = inspection.context?.let { context ->
                    NearbyPrivateGroupInvitationPresentationContext(
                        messageId = context.messageId,
                        createdAtMs = context.createdAtMs,
                        isHiddenLocally = context.hiddenLocally,
                        audience = when (context.audienceRole) {
                            NearbyPrivateGroupInvitationLocalAudienceRole.LOCAL_SENDER -> PrivateInvitationAuthorizedAudience.LOCAL_SENDER
                            NearbyPrivateGroupInvitationLocalAudienceRole.INTENDED_RECIPIENT -> PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT
                        },
                        senderId = context.logicalSenderId,
                        senderDisplayName = context.senderDisplayName,
                        canMutePeer = context.moderationCapabilities.canMutePeer,
                    )
                }
                when (val projection = privateInvitationPresentationMapper.map(mapperContext, inspection.receiveResult)) {
                    is NearbyPrivateGroupInvitationTimelineProjection.Item -> projection.item
                    NearbyPrivateGroupInvitationTimelineProjection.Omit -> null
                    NearbyPrivateGroupInvitationTimelineProjection.UseNormalMessage -> {
                        when (val publicProjection = publicInvitationPresentationMapper.map(
                            repository.inspectPublicGroupInvitationForTimeline(roomId, message.message_id)
                        )) {
                            is NearbyPublicGroupInvitationTimelineProjection.Item -> publicProjection.item
                            is NearbyPublicGroupInvitationTimelineProjection.HiddenItem -> publicProjection.item
                            NearbyPublicGroupInvitationTimelineProjection.Omit -> null
                            NearbyPublicGroupInvitationTimelineProjection.UseNormalMessage ->
                                NearbyRoomTimelineItem.NormalMessage(message)
                        }
                    }
                }
            }
            runOnUiThread {
                val wasAtBottom = !list.canScrollVertically(1)
                val previousLastMessageId = adapter.currentLastMessageId()
                adapter.updateRoomModerationState(room?.is_local_owner == true, mutedPeerIds)
                adapter.updateLocalProfile(localProfile)
                adapter.updateParticipantProfiles(participantProfiles)
                val changed = adapter.submitList(timelineItems)
                val normalMessages = timelineItems.mapNotNull { (it as? NearbyRoomTimelineItem.NormalMessage)?.source }
                syncComposeState(normalMessages, findViewById(R.id.nearby_room_message_input), findViewById(R.id.nearby_room_compose_mode_bar))
                empty.visibility = if (timelineItems.isEmpty()) View.VISIBLE else View.GONE
                val latestLastMessageId = timelineItems.lastOrNull()?.messageId
                if (timelineItems.isNotEmpty() && changed && wasAtBottom && previousLastMessageId != latestLastMessageId) {
                    list.scrollToPosition(timelineItems.lastIndex)
                }
            }
        }.start()
    }

    private fun showRoomMenu(roomId: String) {
        val options = mutableListOf<CharSequence>(
            getString(R.string.nearby_room_participants),
            getString(R.string.nearby_public_invitation_open_composer),
            getString(R.string.nearby_room_leave),
        )
        if (latestAccessState.isLocalOwner) {
            options += redMenuLabel(getString(R.string.nearby_room_close))
        }
        AlertDialog.Builder(this)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showParticipants(roomId)
                    1 -> startActivity(Intent(this, NearbyPublicInvitationComposerActivity::class.java).apply {
                        putExtra(NearbyPublicInvitationComposerActivity.EXTRA_ROOM_ID, roomId)
                    })
                    2 -> leaveRoom(roomId)
                    3 -> if (latestAccessState.isLocalOwner) showCloseRoomConfirmation(roomId)
                }
            }
            .show()
    }

    private fun showParticipants(roomId: String) {
        Thread {
            val rows = loadParticipantRows(roomId)
            runOnUiThread {
                val content = LayoutInflater.from(this).inflate(R.layout.dialog_nearby_room_participants, null)
                val emptyView = content.findViewById<TextView>(R.id.nearby_room_participants_empty)
                val container = content.findViewById<LinearLayout>(R.id.nearby_room_participants_container)
                renderParticipants(
                    rows = rows,
                    container = container,
                    emptyView = emptyView,
                    roomId = roomId,
                )
                AlertDialog.Builder(this)
                    .setTitle(R.string.nearby_room_participants)
                    .setView(content)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    private fun renderParticipants(
        rows: List<NearbyRoomParticipantRow>,
        container: LinearLayout,
        emptyView: TextView,
        roomId: String,
    ) {
        container.removeAllViews()
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        container.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        if (rows.isEmpty()) return
        val inflater = LayoutInflater.from(this)
        rows.forEach { row ->
            val itemView = inflater.inflate(R.layout.item_nearby_room_participant, container, false)
            val avatarView = itemView.findViewById<TextView>(R.id.nearby_room_participant_avatar)
            val nameView = itemView.findViewById<TextView>(R.id.nearby_room_participant_name)
            val metaView = itemView.findViewById<TextView>(R.id.nearby_room_participant_meta)
            val blockRow = itemView.findViewById<View>(R.id.nearby_room_participant_block_row)
            val blockStatusView = itemView.findViewById<TextView>(R.id.nearby_room_participant_block_status)
            val unblockView = itemView.findViewById<TextView>(R.id.nearby_room_participant_unblock)
            val muteRow = itemView.findViewById<View>(R.id.nearby_room_participant_mute_row)
            val muteStatusView = itemView.findViewById<TextView>(R.id.nearby_room_participant_mute_status)
            val muteActionView = itemView.findViewById<TextView>(R.id.nearby_room_participant_mute_action)
            val inviteView = itemView.findViewById<TextView>(R.id.nearby_room_participant_invite)
            avatarView.text = row.avatarText
            avatarView.background.setTint(row.avatarColor)
            avatarView.setTextColor(AvatarUtils.avatarTextColor(row.avatarColor))
            nameView.text = row.displayName
            metaView.text = row.roleLabel
            if (row.isBlocked) {
                blockRow.visibility = View.VISIBLE
                blockStatusView.text = getString(R.string.nearby_room_participant_blocked_by_you)
                unblockView.visibility = if (row.isLocalUser) View.GONE else View.VISIBLE
                unblockView.setOnClickListener {
                    confirmNearbyRoomUnblock(
                        roomId = roomId,
                        peerId = row.deviceId,
                        displayName = row.displayName,
                        onUpdated = { updatedRows ->
                            renderParticipants(updatedRows, container, emptyView, roomId)
                        },
                    )
                }
            } else {
                blockRow.visibility = View.GONE
                unblockView.visibility = View.GONE
                unblockView.setOnClickListener(null)
            }
            if (row.isMutedByOwner) {
                muteRow.visibility = View.VISIBLE
                muteStatusView.text = row.muteStatusText
                if (row.canUnmuteInRoom) {
                    muteActionView.visibility = View.VISIBLE
                    muteActionView.text = getString(R.string.nearby_room_unmute_peer)
                    muteActionView.setOnClickListener {
                        confirmNearbyRoomUnmute(
                            roomId = roomId,
                            peerId = row.deviceId,
                            displayName = row.displayName,
                            onUpdated = { updatedRows ->
                                renderParticipants(updatedRows, container, emptyView, roomId)
                            },
                        )
                    }
                } else {
                    muteActionView.visibility = View.GONE
                    muteActionView.setOnClickListener(null)
                }
            } else {
                muteRow.visibility = View.GONE
                muteActionView.visibility = View.GONE
                muteActionView.setOnClickListener(null)
            }
            if (!row.isLocalUser) {
                val openPrivateInvitationComposer = View.OnClickListener {
                    startActivity(Intent(this, NearbyPrivateInvitationComposerActivity::class.java).apply {
                        putExtra(NearbyPrivateInvitationComposerActivity.EXTRA_ROOM_ID, roomId)
                        putExtra(NearbyPrivateInvitationComposerActivity.EXTRA_TARGET_PARTICIPANT_ID, row.deviceId)
                    })
                }
                itemView.isClickable = true
                itemView.isFocusable = true
                itemView.contentDescription = getString(
                    R.string.nearby_private_invitation_open_composer,
                ) + ": " + row.displayName
                itemView.setOnClickListener(openPrivateInvitationComposer)
                inviteView.visibility = View.VISIBLE
                inviteView.contentDescription = getString(
                    R.string.nearby_private_invitation_open_composer,
                ) + ": " + row.displayName
                inviteView.setOnClickListener(openPrivateInvitationComposer)
            } else {
                inviteView.visibility = View.GONE
                inviteView.setOnClickListener(null)
            }
            container.addView(itemView)
        }
    }

    private fun confirmNearbyRoomUnblock(
        roomId: String,
        peerId: String,
        displayName: String,
        onUpdated: (List<NearbyRoomParticipantRow>) -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.blocked_peers_unblock)
            .setMessage(getString(R.string.nearby_room_unblock_participant_message, displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.blocked_peers_unblock) { _, _ ->
                Thread {
                    moderationRepository.unblockPeer(roomId, peerId)
                    val rows = loadParticipantRows(roomId)
                    runOnUiThread {
                        toast(getString(R.string.blocked_peers_unblocked))
                        onUpdated(rows)
                    }
                }.start()
            }
            .show()
    }

    private fun confirmNearbyRoomMute(
        roomId: String,
        peerId: String,
        displayName: String,
        onUpdated: (List<NearbyRoomParticipantRow>) -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.nearby_room_mute_peer)
            .setMessage(getString(R.string.nearby_room_mute_participant_message, displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.nearby_room_mute_peer) { _, _ ->
                Thread {
                    val eventCreatedAtMs = System.currentTimeMillis()
                    val muted = repository.mutePeerInRoom(roomId, peerId, eventCreatedAtMs)
                    val sent = if (muted) {
                        transportManager.sendOwnerMuteState(
                            roomId = roomId,
                            targetDeviceId = peerId,
                            muted = true,
                            eventCreatedAtMs = eventCreatedAtMs,
                        )
                    } else {
                        false
                    }
                    val rows = loadParticipantRows(roomId)
                    runOnUiThread {
                        if (!muted) {
                            toast(getString(R.string.nearby_room_mute_failed))
                            return@runOnUiThread
                        }
                        clearComposeModeIfSuppressedPeer(peerId)
                        toast(getString(R.string.nearby_room_mute_success))
                        loadMessages(
                            roomId,
                            findViewById<RecyclerView>(R.id.nearby_room_messages_list).adapter as NearbyRoomMessageAdapter,
                            findViewById(R.id.nearby_room_messages_empty),
                            findViewById(R.id.nearby_room_messages_list),
                        )
                        if (!sent) {
                            ActivityLog.add(
                                label = "Nearby room owner mute transport skipped",
                                source = "moderation",
                                message = "room_id=$roomId actor=${repository.localDeviceId()} target=$peerId muted=true reason=send_failed",
                            )
                        }
                        onUpdated(rows)
                    }
                }.start()
            }
            .show()
    }

    private fun confirmNearbyRoomUnmute(
        roomId: String,
        peerId: String,
        displayName: String,
        onUpdated: (List<NearbyRoomParticipantRow>) -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.nearby_room_unmute_peer)
            .setMessage(getString(R.string.nearby_room_unmute_participant_message, displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.nearby_room_unmute_peer) { _, _ ->
                Thread {
                    val eventCreatedAtMs = System.currentTimeMillis()
                    val unmuted = repository.unmutePeerInRoom(roomId, peerId, eventCreatedAtMs)
                    val sent = if (unmuted) {
                        transportManager.sendOwnerMuteState(
                            roomId = roomId,
                            targetDeviceId = peerId,
                            muted = false,
                            eventCreatedAtMs = eventCreatedAtMs,
                        )
                    } else {
                        false
                    }
                    val rows = loadParticipantRows(roomId)
                    runOnUiThread {
                        if (!unmuted) {
                            toast(getString(R.string.nearby_room_mute_failed))
                            return@runOnUiThread
                        }
                        toast(getString(R.string.nearby_room_unmute_success))
                        loadMessages(
                            roomId,
                            findViewById<RecyclerView>(R.id.nearby_room_messages_list).adapter as NearbyRoomMessageAdapter,
                            findViewById(R.id.nearby_room_messages_empty),
                            findViewById(R.id.nearby_room_messages_list),
                        )
                        if (!sent) {
                            ActivityLog.add(
                                label = "Nearby room owner mute transport skipped",
                                source = "moderation",
                                message = "room_id=$roomId actor=${repository.localDeviceId()} target=$peerId muted=false reason=send_failed",
                            )
                        }
                        onUpdated(rows)
                    }
                }.start()
            }
            .show()
    }

    private fun loadParticipantRows(roomId: String): List<NearbyRoomParticipantRow> {
        repository.materializeLocalParticipant(roomId)
        val participants = repository.listParticipants(roomId)
        val localDeviceId = repository.localDeviceId()
        val localOwner = repository.getRoom(roomId)?.is_local_owner == true
        val localProfile = localUserProfileStore.load()
        return participants.map { participant ->
            val isLocalUser = participant.device_id == localDeviceId
            val displayName = LocalUserProfileUi.resolveDisplayName(
                rawDisplayName = participant.display_name,
                isLocalUser = isLocalUser,
                localProfile = localProfile,
            )
            val isMutedByOwner = repository.isPeerMutedInRoom(roomId, participant.device_id)
            NearbyRoomParticipantRow(
                deviceId = participant.device_id,
                displayName = displayName,
                roleLabel = participantRoleLabel(
                    role = participant.role,
                    isLocalUser = isLocalUser,
                ),
                avatarText = when {
                    isLocalUser && !localProfile?.avatarId.isNullOrBlank() ->
                        AvatarUtils.avatarEmojiForId(this, localProfile?.avatarId)
                    !participant.avatar_id.isNullOrBlank() ->
                        AvatarUtils.avatarEmojiForId(this, participant.avatar_id)
                    else -> AvatarUtils.avatarInitials(displayName)
                },
                avatarColor = AvatarUtils.avatarColorForKey(participant.device_id),
                isBlocked = repository.isGloballyMessageBlocked(participant.device_id),
                isMutedByOwner = isMutedByOwner,
                muteStatusText = when {
                    !isMutedByOwner -> null
                    localOwner &&
                        repository.ownerMuteActorDeviceId(roomId, participant.device_id) == localDeviceId ->
                        getString(R.string.nearby_room_participant_muted_by_you)
                    else -> getString(R.string.nearby_room_participant_muted_by_owner)
                },
                canUnmuteInRoom = isMutedByOwner &&
                    localOwner &&
                    repository.ownerMuteActorDeviceId(roomId, participant.device_id) == localDeviceId,
                isLocalUser = isLocalUser,
            )
        }
    }

    private fun clearComposeModeIfSuppressedPeer(targetDeviceId: String) {
        val replyPreview = selectedReplyMessagePreview
        val editPreview = selectedEditMessagePreview
        val affected = listOfNotNull(replyPreview, editPreview).any {
            (it.owner_device_id ?: it.sender_device_id) == targetDeviceId
        }
        if (affected) {
            clearComposeMode(
                findViewById(R.id.nearby_room_message_input),
                findViewById(R.id.nearby_room_compose_mode_bar),
            )
        }
    }

    private fun participantRoleLabel(role: String, isLocalUser: Boolean): String {
        val roleText = when (role) {
            "OWNER" -> getString(R.string.group_member_role_owner)
            else -> getString(R.string.nearby_room_participant_role_participant)
        }
        return if (isLocalUser) {
            getString(R.string.nearby_room_participant_role_with_you_format, roleText, getString(R.string.group_member_role_you))
        } else {
            roleText
        }
    }

    private fun leaveRoom(roomId: String) {
        Thread {
            val changed = repository.leaveRoom(roomId)
            runOnUiThread {
                if (changed) {
                    toast(getString(R.string.nearby_room_left))
                }
                finish()
            }
        }.start()
    }

    private fun closeRoom(roomId: String) {
        Thread {
            val changed = repository.closeRoom(roomId)
            if (changed) {
                transportManager.sendRoomClosed(roomId)
            }
            runOnUiThread {
                navigateToDiscoveryOnClose(roomId)
            }
        }.start()
    }

    private fun showCloseRoomConfirmation(roomId: String) {
        if (!latestAccessState.isLocalOwner) return
        AlertDialog.Builder(this)
            .setTitle(R.string.nearby_room_close_confirm_title)
            .setMessage(R.string.nearby_room_close_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                closeRoom(roomId)
            }
            .show()
    }

    private fun showOwnerClosedDialog(roomId: String) {
        if (closeNoticeDialogShown || closedRoomDialogHandled || isFinishing) return
        closeNoticeDialogShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.nearby_room_closed_dialog_title)
            .setMessage(R.string.nearby_room_closed_by_owner)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                closedRoomDialogHandled = true
                closeNoticeDialogShown = false
                Thread { repository.purgeRoomLocalData(roomId) }.start()
                navigateToDiscoveryOnClose(roomId)
            }
            .show()
    }

    private fun showExpiredDialog(roomId: String) {
        if (expiredDialogShown || isFinishing) return
        expiredDialogShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.nearby_room_expired_dialog_title)
            .setMessage(R.string.nearby_room_expired)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                navigateToDiscoveryOnClose(roomId)
            }
            .show()
    }

    private fun redMenuLabel(text: String): CharSequence {
        val color = ContextCompat.getColor(this, R.color.status_error)
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun navigateToDiscoveryOnClose(roomId: String) {
        releaseNearbyRuntime(roomId, "navigate_to_discovery")
        ActiveNearbyRoomSessionManager.leaveRoom(applicationContext, roomId)
        ActiveNearbyRoomSessionManager.enterDiscovery(applicationContext)
        startActivity(Intent(this, NearbyRoomsActivity::class.java))
        finish()
    }

    private fun releaseNearbyRuntime(roomId: String, reason: String) {
        if (runtimeReleased) return
        runtimeReleased = true
        val sessionId = runtimeSessionId
        val attemptId = runtimeAttemptId
        when {
            sessionId != null -> NearbyForegroundRuntimeCoordinator.releaseRoom(
                context = applicationContext,
                sessionId = sessionId,
                reason = "$reason:$roomId",
            )
            attemptId != null -> NearbyForegroundRuntimeCoordinator.releaseJoining(
                context = applicationContext,
                attemptId = attemptId,
                reason = "$reason:$roomId",
            )
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun startEdit(
        message: NearbyRoomMessageEntity,
        input: EditText,
        composeModeBar: View,
        composeModeLabel: TextView,
    ) {
        if (message.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM || message.deleted_at_ms != null) return
        selectedReplyMessageId = null
        selectedReplyMessagePreview = null
        selectedEditMessageId = message.message_id
        selectedEditMessagePreview = message
        input.setText(message.body)
        input.setSelection(message.body.length)
        renderComposeMode(composeModeBar, composeModeLabel)
    }

    private fun startReply(
        message: NearbyRoomMessageEntity,
        input: EditText,
    ) {
        if (message.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM ||
            message.deleted_at_ms != null ||
            GroupInvitationProtocol.isExactGroupInvitationCandidate(message.body)
        ) return
        if (selectedEditMessageId != null) {
            input.setText("")
        }
        selectedEditMessageId = null
        selectedEditMessagePreview = null
        selectedReplyMessageId = message.message_id
        selectedReplyMessagePreview = message
        renderComposeMode(
            findViewById(R.id.nearby_room_compose_mode_bar),
            findViewById(R.id.nearby_room_compose_mode_label),
        )
    }

    private fun clearComposeMode(input: EditText, composeModeBar: View) {
        val editing = selectedEditMessageId != null
        selectedReplyMessageId = null
        selectedReplyMessagePreview = null
        selectedEditMessageId = null
        selectedEditMessagePreview = null
        composeModeBar.visibility = View.GONE
        if (editing && input.text?.isNotEmpty() == true) {
            input.text?.clear()
        }
    }

    private fun confirmDeleteMessage(
        roomId: String,
        message: NearbyRoomMessageEntity,
        adapter: NearbyRoomMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
        input: EditText,
        composeModeBar: View,
    ) {
        val actorDeviceId = repository.localDeviceId()
        val ownerDeviceId = message.owner_device_id ?: message.sender_device_id
        if (message.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM ||
            message.deleted_at_ms != null ||
            ownerDeviceId != actorDeviceId
        ) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_delete_confirm_title)
            .setMessage(R.string.chat_delete_confirm_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chat_delete_confirm_cta) { _, _ ->
                Thread {
                    if (!BleModeController.shouldProcessActiveRoomTransport(applicationContext, roomId)) {
                        runOnUiThread { toast(getString(R.string.nearby_room_inactive_state)) }
                        return@Thread
                    }
                    ensureSecurityState(roomId)
                    val updated = repository.deleteLocalMessage(roomId, message.message_id)
                    val room = repository.getRoom(roomId)
                    val ownerTargetDeviceIds = if (room?.is_local_owner == true) {
                        NearbyRoomPeerSnapshotStore.nearbyDeviceIdsForRoom(roomId, limit = OWNER_SEND_TARGET_LIMIT)
                            .filterNot { it == repository.localDeviceId() }
                            .toSet()
                            .takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                    val sent = if (updated != null) {
                        transportManager.sendEncryptedMessage(
                            message = updated,
                            targetDeviceIds = ownerTargetDeviceIds,
                        )
                    } else {
                        false
                    }
                    if (updated != null && sent) {
                        repository.markMessageSent(updated.room_id, updated.message_id)
                        NearbyRoomRelayRecoveryManager.onMessageAvailable(
                            context = applicationContext,
                            message = updated,
                            lastHopSenderDeviceId = null,
                        )
                    } else if (updated != null && room?.is_local_owner == true) {
                        NearbyRoomOwnerMessageRetryCoordinator.enqueue(
                            context = applicationContext,
                            roomId = updated.room_id,
                            messageId = updated.message_id,
                        )
                    }
                    runOnUiThread {
                        if (updated == null) {
                            toast(getString(R.string.chat_delete_failed))
                            return@runOnUiThread
                        }
                        if (!sent && room?.is_local_owner != true) {
                            toast(getString(R.string.chat_delete_failed))
                        }
                        if (selectedReplyMessageId == updated.message_id || selectedEditMessageId == updated.message_id) {
                            clearComposeMode(input, composeModeBar)
                        }
                        NearbyRoomBleCoordinator.refreshActiveRoomAdvertisement(applicationContext, roomId)
                        loadRoom(
                            roomId,
                            findViewById(R.id.header_title),
                            findViewById(R.id.nearby_room_expiry),
                            findViewById(R.id.nearby_room_participant_summary),
                            findViewById(R.id.nearby_room_state_line),
                        )
                        loadMessages(roomId, adapter, emptyView, listView)
                    }
                }.start()
            }
            .show()
    }

    private fun showBlockPeerDialog(
        roomId: String,
        message: NearbyRoomMessageEntity,
        adapter: NearbyRoomMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
        input: EditText,
        composeModeBar: View,
    ) {
        if (message.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM) return
        val targetDeviceId = message.owner_device_id ?: message.sender_device_id
        if (targetDeviceId == repository.localDeviceId()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.moderation_block_peer)
            .setMessage(R.string.moderation_block_peer_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.moderation_block_confirm) { _, _ ->
                Thread {
                    val blocked = repository.blockPeerLocally(
                        roomId = roomId,
                        targetDeviceId = targetDeviceId,
                        reasonCode = NEARBY_ROOM_LOCAL_BLOCK_REASON,
                    )
                    runOnUiThread {
                        if (!blocked) {
                            toast(getString(R.string.nearby_room_block_failed))
                            return@runOnUiThread
                        }
                        if (selectedReplyMessageId == message.message_id || selectedEditMessageId == message.message_id) {
                            clearComposeMode(input, composeModeBar)
                        }
                        toast(getString(R.string.moderation_block_success))
                        loadMessages(roomId, adapter, emptyView, listView)
                    }
                }.start()
            }
            .show()
    }

    private fun handlePrivateInvitationAction(
        roomId: String,
        messageId: String,
        senderId: String,
        action: PrivateInvitationAction,
        adapter: NearbyRoomMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
        input: EditText,
        composeModeBar: View,
    ) {
        when (action) {
            PrivateInvitationAction.OPEN -> startActivity(
                NearbyPrivateGroupInvitationViewerActivity.intent(
                    context = this,
                    roomId = roomId,
                    messageId = messageId,
                )
            )
            PrivateInvitationAction.HIDE, PrivateInvitationAction.UNHIDE -> Thread {
                val result = repository.setGroupInvitationHiddenLocally(
                    roomId = roomId,
                    messageId = messageId,
                    hidden = action == PrivateInvitationAction.HIDE,
                )
                runOnUiThread {
                    when (result) {
                        NearbyGroupInvitationLocalVisibilityResult.Updated,
                        NearbyGroupInvitationLocalVisibilityResult.AlreadyInRequestedState,
                        NearbyGroupInvitationLocalVisibilityResult.MessageNotFound -> Unit
                        NearbyGroupInvitationLocalVisibilityResult.NotAuthorizedViewer -> toast(getString(R.string.nearby_private_invitation_action_failed))
                        NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation,
                        NearbyGroupInvitationLocalVisibilityResult.InvitationInvalid -> toast(getString(R.string.nearby_private_invitation_no_longer_available))
                    }
                    loadMessages(roomId, adapter, emptyView, listView)
                }
            }.start()
            PrivateInvitationAction.REPORT_INVITATION -> showReportPrivateInvitationDialog(
                roomId, messageId, senderId, adapter, emptyView, listView, input, composeModeBar
            )
            PrivateInvitationAction.REPORT_PEER -> showReportPeerDialog(
                roomId, senderId, adapter, emptyView, listView, input, composeModeBar
            )
            PrivateInvitationAction.BLOCK_PEER -> {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.moderation_block_peer))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.moderation_block_confirm) { _, _ ->
                        Thread {
                            val blocked = repository.blockPeerLocally(roomId, senderId, NEARBY_ROOM_LOCAL_BLOCK_REASON)
                            runOnUiThread {
                                if (blocked) { clearComposeModeIfBlockedPeer(senderId, input, composeModeBar); loadMessages(roomId, adapter, emptyView, listView) }
                                else toast(getString(R.string.nearby_room_block_failed))
                            }
                        }.start()
                    }.show()
            }
            PrivateInvitationAction.MUTE_PEER -> confirmNearbyRoomMute(
                roomId = roomId, peerId = senderId, displayName = senderId,
                onUpdated = { _ -> loadMessages(roomId, adapter, emptyView, listView) },
            )
        }
    }

    private fun showReportPrivateInvitationDialog(
        roomId: String,
        messageId: String,
        senderId: String,
        adapter: NearbyRoomMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
        input: EditText,
        composeModeBar: View,
    ) {
        showReasonPicker { reason ->
            showReportNoteDialog(R.string.moderation_report_message, false) { note, _, alsoBlock ->
                Thread {
                    val reportId = moderationRepository.createNearbyRoomPrivateInvitationReport(
                        roomId, messageId, senderId, reason.code, note, alsoBlock
                    )
                    runOnUiThread {
                        if (reportId == null) toast(getString(R.string.nearby_private_invitation_action_failed))
                        else toast(getString(R.string.moderation_report_saved))
                        if (alsoBlock && reportId != null) {
                            clearComposeModeIfBlockedPeer(senderId, input, composeModeBar)
                            loadMessages(roomId, adapter, emptyView, listView)
                        }
                    }
                }.start()
            }
        }
    }

    private fun showReportPeerDialog(
        roomId: String,
        targetDeviceId: String,
        adapter: NearbyRoomMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
        input: EditText,
        composeModeBar: View,
    ) {
        if (targetDeviceId == repository.localDeviceId()) return
        showReasonPicker { reason ->
            showReportNoteDialog(
                titleRes = R.string.moderation_report_peer,
                includePlaintextToggle = false,
            ) { note, _, alsoBlock ->
                Thread {
                    moderationRepository.createPeerReport(
                        groupId = roomId,
                        reportedPeerId = targetDeviceId,
                        reasonCode = reason.code,
                        userNote = note,
                        alsoBlock = alsoBlock,
                    )
                    runOnUiThread {
                        toast(getString(R.string.moderation_report_saved))
                        if (alsoBlock) {
                            clearComposeModeIfBlockedPeer(targetDeviceId, input, composeModeBar)
                            loadMessages(roomId, adapter, emptyView, listView)
                        }
                    }
                }.start()
            }
        }
    }

    private fun showReportMessageDialog(
        roomId: String,
        message: NearbyRoomMessageEntity,
        adapter: NearbyRoomMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
        input: EditText,
        composeModeBar: View,
    ) {
        val targetDeviceId = message.owner_device_id ?: message.sender_device_id
        if (targetDeviceId == repository.localDeviceId()) return
        showReasonPicker { reason ->
            showReportNoteDialog(
                titleRes = R.string.moderation_report_message,
                includePlaintextToggle = true,
            ) { note, includePlaintext, alsoBlock ->
                Thread {
                    moderationRepository.createNearbyRoomMessageReport(
                        roomId = roomId,
                        message = message,
                        reasonCode = reason.code,
                        userNote = note,
                        includePlaintext = includePlaintext,
                        alsoBlock = alsoBlock,
                    )
                    runOnUiThread {
                        toast(getString(R.string.moderation_report_saved))
                        if (alsoBlock) {
                            if (selectedReplyMessageId == message.message_id || selectedEditMessageId == message.message_id) {
                                clearComposeMode(input, composeModeBar)
                            } else {
                                clearComposeModeIfBlockedPeer(targetDeviceId, input, composeModeBar)
                            }
                            loadMessages(roomId, adapter, emptyView, listView)
                        }
                    }
                }.start()
            }
        }
    }

    private fun clearComposeModeIfBlockedPeer(
        targetDeviceId: String,
        input: EditText,
        composeModeBar: View,
    ) {
        val replyPreview = selectedReplyMessagePreview
        val editPreview = selectedEditMessagePreview
        val affected = listOfNotNull(replyPreview, editPreview).any {
            (it.owner_device_id ?: it.sender_device_id) == targetDeviceId
        }
        if (affected) {
            clearComposeMode(input, composeModeBar)
        }
    }

    private fun showReasonPicker(onSelected: (ModerationReason) -> Unit) {
        val reasons = ModerationReason.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.moderation_reason_title)
            .setItems(reasons.map { getString(it.labelRes) }.toTypedArray()) { _, which ->
                onSelected(reasons[which])
            }
            .show()
    }

    private fun showReportNoteDialog(
        titleRes: Int,
        includePlaintextToggle: Boolean,
        onSubmit: (note: String?, includePlaintext: Boolean, alsoBlock: Boolean) -> Unit,
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_moderation_report, null)
        val noteInput = content.findViewById<EditText>(R.id.moderation_note_input)
        val includePlaintext = content.findViewById<android.widget.CheckBox>(R.id.moderation_include_plaintext)
        val alsoBlock = content.findViewById<android.widget.CheckBox>(R.id.moderation_also_block)
        val disclosure = content.findViewById<TextView>(R.id.moderation_disclosure_text)
        includePlaintext.visibility = if (includePlaintextToggle) View.VISIBLE else View.GONE
        disclosure.text = getString(
            if (includePlaintextToggle) {
                R.string.moderation_report_disclosure_message
            } else {
                R.string.moderation_report_disclosure_peer
            }
        )
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.moderation_report_confirm) { _, _ ->
                onSubmit(
                    noteInput.text?.toString(),
                    includePlaintext.isChecked && includePlaintextToggle,
                    alsoBlock.isChecked,
                )
            }
            .show()
    }

    private fun renderComposeMode(composeModeBar: View, composeModeLabel: TextView) {
        val composeModeText = findViewById<TextView>(R.id.nearby_room_compose_mode_text)
        val composeModeClose = findViewById<ImageButton>(R.id.nearby_room_compose_mode_close)
        val editing = selectedEditMessagePreview
        val replying = selectedReplyMessagePreview
        val composeMessage = editing ?: replying
        if (composeMessage == null) {
            composeModeBar.visibility = View.GONE
            return
        }
        composeModeBar.visibility = View.VISIBLE
        if (editing != null) {
            composeModeLabel.text = getString(R.string.nearby_room_edit_mode_label)
            composeModeText.text = composeMessage.body
            composeModeClose.contentDescription = getString(R.string.chat_edit_cancel)
        } else {
            val actorLabel = LocalUserProfileUi.resolveDisplayName(
                rawDisplayName = composeMessage.sender_display_name,
                isLocalUser = (composeMessage.owner_device_id ?: composeMessage.sender_device_id) == repository.localDeviceId(),
                localProfile = localUserProfileStore.load(),
            )
            composeModeLabel.text = getString(R.string.chat_replying_to_format, actorLabel)
            composeModeText.text = composeMessage.body.ifBlank { getString(R.string.chat_reply_original_unavailable) }
            composeModeClose.contentDescription = getString(R.string.chat_reply_cancel)
        }
    }

    private fun syncComposeState(
        messages: List<NearbyRoomMessageEntity>,
        input: EditText,
        composeModeBar: View,
    ) {
        val composeMessageId = selectedEditMessageId ?: selectedReplyMessageId ?: return
        val refreshed = messages.firstOrNull { it.message_id == composeMessageId }
        if (refreshed == null ||
            refreshed.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM ||
            refreshed.deleted_at_ms != null
        ) {
            clearComposeMode(input, composeModeBar)
            return
        }
        if (selectedEditMessageId != null) {
            selectedEditMessagePreview = refreshed
        } else {
            selectedReplyMessagePreview = refreshed
        }
        renderComposeMode(composeModeBar, findViewById(R.id.nearby_room_compose_mode_label))
    }

    private fun scrollToMessage(
        messageId: String,
        listView: RecyclerView,
        adapter: NearbyRoomMessageAdapter,
    ) {
        val index = adapter.indexOfMessageId(messageId)
        if (index < 0) {
            toast(getString(R.string.chat_reply_jump_unavailable))
            return
        }
        val layoutManager = listView.layoutManager as? LinearLayoutManager
        if (layoutManager == null) {
            listView.smoothScrollToPosition(index)
            return
        }
        replyJumpScrollListener?.let { listView.removeOnScrollListener(it) }
        replyJumpScrollListener = null
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val alreadyVisible = index in firstVisible..lastVisible
        val highlightTarget = Runnable {
            adapter.setHighlightedMessageId(messageId)
            listView.postDelayed({ adapter.setHighlightedMessageId(null) }, REPLY_HIGHLIGHT_DURATION_MS)
        }
        if (alreadyVisible) {
            listView.postDelayed(highlightTarget, REPLY_HIGHLIGHT_SCROLL_SETTLE_MS)
            return
        }
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                recyclerView.removeOnScrollListener(this)
                replyJumpScrollListener = null
                recyclerView.postDelayed(highlightTarget, REPLY_HIGHLIGHT_SCROLL_SETTLE_MS)
            }
        }
        replyJumpScrollListener = listener
        listView.addOnScrollListener(listener)
        listView.smoothScrollToPosition(index)
    }

    private fun ensureSecurityState(roomId: String, force: Boolean = false) {
        val nowMs = System.currentTimeMillis()
        if (latestAccessState.isKeyReady) {
            joinAccessRetryUntilMs = 0L
            joinAccessTimeoutHandled = false
            NearbyRoomPendingJoinIntentManager.clear(
                applicationContext,
                roomId = roomId,
                reason = "key_access_ready",
            )
            return
        }
        if (!force && nowMs - lastKeyRequestAtMs < KEY_REQUEST_INTERVAL_MS) {
            return
        }
        lastKeyRequestAtMs = nowMs
        transportManager.sendKeyRequest(roomId)
    }

    private fun refreshAccessState(
        roomId: String,
        overlay: FrameLayout,
        loadingDots: NearbyRoomsLoadingDotsView,
    ) {
        Thread {
            val accessState = computeRoomAccessState(roomId)
            runOnUiThread {
                if (!latestAccessState.isKeyReady && accessState.isKeyReady) {
                    ActivityLog.add(
                        label = "Nearby room key access ready",
                        source = "nearby",
                        message = "room_id=$roomId metadata_ready=${accessState.isMetadataReady}",
                    )
                    if (!accessState.isMetadataReady) {
                        ActivityLog.add(
                            label = "Nearby room metadata pending after key ready",
                            source = "nearby",
                            message = "room_id=$roomId status=pending",
                        )
                    }
                }
                if (joinAccessRetryUntilMs > 0L && !latestAccessState.isKeyReady && accessState.isKeyReady) {
                    ActivityLog.add(
                        label = "Nearby room join retry stopped",
                        source = "nearby",
                        message = "room_id=$roomId reason=key_ready deadline_ms=$joinAccessRetryUntilMs",
                    )
                }
                latestAccessState = accessState
                if (accessState.isKeyReady && runtimeSessionId == null) {
                    runtimeSessionId = NearbyForegroundRuntimeCoordinator.activateRoom(
                        context = applicationContext,
                        roomId = roomId,
                        attemptId = runtimeAttemptId,
                        reason = "room_access_ready",
                        sessionId = runtimeAttemptId ?: java.util.UUID.randomUUID().toString(),
                    )
                }
                val shouldShowOverlay = !accessState.isLocalOwner && !accessState.isKeyReady
                overlay.visibility = if (shouldShowOverlay) View.VISIBLE else View.GONE
                loadingDots.setLoadingVisible(shouldShowOverlay)
                if (accessState.isKeyReady) {
                    NearbyRoomPendingJoinIntentManager.clear(
                        applicationContext,
                        roomId = roomId,
                        reason = "key_access_ready",
                    )
                }
                if (!accessState.isLocalOwner && joinAccessRetryUntilMs == 0L && !accessState.isKeyReady) {
                    joinAccessRetryUntilMs = System.currentTimeMillis() + JOIN_ACCESS_RETRY_WINDOW_MS
                    joinAccessTimeoutHandled = false
                }
            }
        }.start()
    }

    private fun computeRoomAccessState(roomId: String): RoomAccessState {
        val room = repository.getRoom(roomId)
        if (room == null) {
            return RoomAccessState(
                isLocalOwner = false,
                isKeyReady = false,
                isMetadataReady = false,
                isChatAccessReady = false,
            )
        }
        if (room.state != NearbyRoomEntity.STATE_ACTIVE) {
            return RoomAccessState(
                isLocalOwner = room.is_local_owner,
                isKeyReady = false,
                isMetadataReady = room.metadata_status == NearbyRoomEntity.METADATA_RESOLVED,
                isChatAccessReady = false,
            )
        }
        if (room.is_local_owner) {
            return RoomAccessState(
                isLocalOwner = true,
                isKeyReady = true,
                isMetadataReady = true,
                isChatAccessReady = true,
            )
        }
        val isKeyReady = transportManager.hasRoomKey(roomId)
        val isMetadataReady = room.metadata_status == NearbyRoomEntity.METADATA_RESOLVED
        return RoomAccessState(
            isLocalOwner = false,
            isKeyReady = isKeyReady,
            isMetadataReady = isMetadataReady,
            isChatAccessReady = isKeyReady,
        )
    }

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_AUTO_RESUMED_JOIN = "extra_auto_resumed_join"
        const val EXTRA_JOIN_ATTEMPT_ID = "extra_join_attempt_id"
        private const val STATE_JOIN_ATTEMPT_ID = "state_join_attempt_id"
        private const val STATE_ROOM_SESSION_ID = "state_room_session_id"
        private const val REQ_NEARBY_ROOM_PERM = 4201
        private const val KEY_REQUEST_INTERVAL_MS = 30_000L
        private const val OWNER_SEND_TARGET_LIMIT = 10
        private const val JOIN_ACCESS_RETRY_WINDOW_MS = 60_000L
        private const val REPLY_HIGHLIGHT_SCROLL_SETTLE_MS = 150L
        private const val REPLY_HIGHLIGHT_DURATION_MS = 2_000L
        private const val NEARBY_ROOM_LOCAL_BLOCK_REASON = "user_block"
    }

    private data class RoomAccessState(
        val isLocalOwner: Boolean,
        val isKeyReady: Boolean,
        val isMetadataReady: Boolean,
        val isChatAccessReady: Boolean,
    )

    private data class NearbyRoomParticipantRow(
        val deviceId: String,
        val displayName: String,
        val roleLabel: String,
        val avatarText: String,
        val avatarColor: Int,
        val isBlocked: Boolean,
        val isMutedByOwner: Boolean,
        val muteStatusText: String?,
        val canUnmuteInRoom: Boolean,
        val isLocalUser: Boolean,
    )

    private enum class ModerationReason(val code: String, val labelRes: Int) {
        HARASSMENT("harassment", R.string.moderation_reason_harassment),
        THREAT("threatening_behavior", R.string.moderation_reason_threatening),
        SPAM("spam", R.string.moderation_reason_spam),
        INAPPROPRIATE("inappropriate_content", R.string.moderation_reason_inappropriate),
        IMPERSONATION("impersonation", R.string.moderation_reason_impersonation),
        OTHER("other", R.string.moderation_reason_other),
    }
}
