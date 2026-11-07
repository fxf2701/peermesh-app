package app.peermesh

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Button
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.id.EventIdFactory
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.core.transport.EnvelopeReceiver
import app.peermesh.ble.BlePermissionHelper
import app.peermesh.relay.SeenAckCloudBuffer
import app.peermesh.relay.ManagedRelayAccessCoordinator
import app.peermesh.relay.RelaySyncSettings
import app.peermesh.relay.RelaySyncUiEvaluator
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.reducer.ChatReducer
import app.peermesh.data.membership.GroupMembershipPolicy
import app.peermesh.moderation.ModerationRepository
import app.peermesh.nearby.NearbyBlockingReason
import app.peermesh.nearby.NearbyReadinessHelper
import app.peermesh.security.crypto.SecureKeyStore
import app.peermesh.ble.BleMeshRuntime
import app.peermesh.config.EnvironmentLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import app.peermesh.data.ingest.IngestNotifier
import app.peermesh.data.debug.ActivityLog
import app.peermesh.ble.MeshAckManager

class GroupChatActivity : AppCompatActivity() {
    private val meshTransport by lazy {
        BleMeshRuntime.get(applicationContext)
    }
    private val moderationRepository by lazy { ModerationRepository(applicationContext) }
    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null
    private var ingestListener: IngestNotifier.Listener? = null
    private var statusReceiverRegistered = false
    private val snapshotMessages = mutableListOf<app.peermesh.data.db.entity.ChatMessageEntity>()
    private val pendingBuffer = mutableListOf<app.peermesh.data.db.entity.ChatMessageEntity>()
    @Volatile private var refreshInFlight = false
    @Volatile private var refreshPending = false
    private var refreshTickerRunnable: Runnable? = null
    private var lastRefreshAtMs: Long = 0L
    private var chatActive = false
    @Volatile private var isTyping = false
    private var typingIdleRunnable: Runnable? = null
    private var autoRefreshEnabled = true
    private var forceRefreshToBottom = false
    @Volatile private var paginationInFlight = false
    private var hasMoreHistory = true
    private var refreshGid: String? = null
    private var refreshAdapter: ChatMessageAdapter? = null
    private var refreshEmptyView: TextView? = null
    private var refreshListView: RecyclerView? = null
    private var messageScrollListener: RecyclerView.OnScrollListener? = null
    private var currentProfiles: Map<String, ChatMessageAdapter.ProfileUi> = emptyMap()
    private var selectedReplyMessageId: String? = null
    private var selectedReplyMessagePreview: app.peermesh.data.db.entity.ChatMessageEntity? = null
    private var selectedEditMessageId: String? = null
    private var selectedEditMessagePreview: app.peermesh.data.db.entity.ChatMessageEntity? = null
    private var messageHighlightClearRunnable: Runnable? = null
    private var replyJumpScrollListener: RecyclerView.OnScrollListener? = null
    @Volatile private var actionMenuVisible = false
    @Volatile private var participationBlocked = false
    private var networkCallbackRegistered = false
    private val validatedNetworks = mutableSetOf<Network>()
    private var connectivityBleStatusListener: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_chat)

        val gid = intent.getStringExtra(EXTRA_GID) ?: ""
        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val messagesEmpty = findViewById<TextView>(R.id.messages_empty)
        val messagesList = findViewById<RecyclerView>(R.id.messages_list)
        val messageInput = findViewById<EditText>(R.id.message_input)
        val messageSend = findViewById<ImageButton>(R.id.message_send)
        val messageStatus = findViewById<TextView>(R.id.message_send_status)
        val replyComposeContainer = findViewById<View>(R.id.reply_compose_container)
        val replyComposeClose = findViewById<ImageButton>(R.id.reply_compose_close)
        val blePermissionWarning = findViewById<TextView>(R.id.ble_permission_warning)
        val blePermissionGrant = findViewById<Button>(R.id.ble_permission_grant)
        val blePermissionSettingsHint = findViewById<TextView>(R.id.ble_permission_settings_hint)
        val blePermissionOpenSettings = findViewById<Button>(R.id.ble_permission_open_settings)
        val connectionStatus = findViewById<View>(R.id.group_chat_connection_status)
        val connectionPrimary = findViewById<TextView>(R.id.group_chat_connection_primary)
        val connectionBody = findViewById<TextView>(R.id.group_chat_connection_body)
        val connectionAction = findViewById<Button>(R.id.group_chat_connection_action)
        val messageCharCounter = findViewById<TextView>(R.id.message_char_counter)

        headerTitle.text = groupName ?: getString(R.string.page_title_group)
        messageInput.filters = arrayOf(InputFilter.LengthFilter(MAX_MESSAGE_CHARS))

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            }
        )
        headerMenu.setOnClickListener {
            val menu = android.widget.PopupMenu(this, headerMenu)
            menu.menuInflater.inflate(R.menu.group_chat_menu, menu.menu)
            PeerMeshFeatureUi.applyGroupChatMenuCapabilities(menu.menu)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_group_detail -> {
                        val intent = Intent(this, GroupDetailActivity::class.java).apply {
                            putExtra(GroupDetailActivity.EXTRA_GID, gid)
                        }
                        startActivity(intent)
                        true
                    }
                    R.id.menu_group_map -> {
                        PeerMeshFeatureUi.openMapOrPaywall(this, gid, groupName)
                        true
                    }
                    R.id.menu_group_settings -> {
                        val intent = Intent(this, GroupSettingsActivity::class.java).apply {
                            putExtra(GroupSettingsActivity.EXTRA_GID, gid)
                            putExtra(GroupSettingsActivity.EXTRA_GROUP_NAME, groupName)
                        }
                        startActivity(intent)
                        true
                    }
                    R.id.menu_group_close -> {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                        true
                    }
                    else -> false
                }
            }
            menu.show()
        }

        lateinit var adapter: ChatMessageAdapter
        adapter = ChatMessageAdapter(
            onReplyRequested = { message -> startReply(message) },
            onReplyPreviewClicked = { messageId -> scrollToMessage(messageId) },
            onEditRequested = { message -> startEdit(message) },
            onDeleteRequested = { message -> confirmDeleteMessage(gid, message, adapter, messagesEmpty, messagesList) },
            onReportMessageRequested = { message -> showReportMessageDialog(gid, message) },
            onReportPeerRequested = { message -> showReportPeerDialog(gid, message.actor_did) },
            onBlockPeerRequested = { message -> showBlockPeerDialog(gid, message.actor_did) },
            onActionMenuVisibilityChanged = { visible -> actionMenuVisible = visible },
        )
        adapter.setActionsEnabled(false)
        val layoutManager = LinearLayoutManager(this)
        messagesList.layoutManager = layoutManager
        messagesList.adapter = adapter
        replyComposeClose.setOnClickListener {
            clearComposeMode()
        }
        replyComposeContainer.setOnClickListener {
            selectedReplyMessageId?.let { messageId -> scrollToMessage(messageId) }
        }
        renderComposerState()

        if (gid.isBlank()) {
            messagesEmpty.text = getString(R.string.chat_messages_empty)
            messagesEmpty.visibility = View.VISIBLE
            return
        }

        forceRefreshToBottom = true
        loadMessages(gid, adapter, messagesEmpty, messagesList)
        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val firstVisible = lm.findFirstVisibleItemPosition()
                val lastVisible = lm.findLastVisibleItemPosition()
                val itemCount = adapter.itemCount
                val atBottom = itemCount == 0 || lastVisible >= itemCount - 1
                if (atBottom) {
                    val wasDisabled = !autoRefreshEnabled
                    autoRefreshEnabled = true
                    if (wasDisabled && refreshPending) {
                        runRefreshNow()
                    }
                } else {
                    autoRefreshEnabled = false
                }
                if (dy < 0 && firstVisible <= 0) {
                    loadOlderMessages(gid, adapter, messagesEmpty, messagesList)
                }
            }
        }
        messagesList.addOnScrollListener(scrollListener)
        messageScrollListener = scrollListener
        updateSendState(
            messageInput,
            messageSend,
            messageStatus,
            blePermissionWarning,
            blePermissionGrant,
            blePermissionSettingsHint,
            blePermissionOpenSettings,
            messageStatus
        )
        refreshParticipationState(
            gid = gid,
            inputView = messageInput,
            sendButton = messageSend,
            statusView = messageStatus,
            permissionWarning = blePermissionWarning,
            permissionGrant = blePermissionGrant,
            permissionSettingsHint = blePermissionSettingsHint,
            permissionOpenSettings = blePermissionOpenSettings,
            noNearbyStatusView = messageStatus,
        )
        refreshConnectionStatusArea(connectionStatus, connectionPrimary, connectionBody, connectionAction)
        updateMessageCharacterCounter(messageCharCounter, messageInput.text?.length ?: 0)

        blePermissionGrant.setOnClickListener {
            if (shouldShowBleRationale()) {
                openAppSettings()
                blePermissionSettingsHint.visibility = View.VISIBLE
                blePermissionOpenSettings.visibility = View.VISIBLE
            } else {
                ActivityCompat.requestPermissions(this, BlePermissionHelper.requiredPermissions(), REQ_BLE_PERM)
            }
        }
        blePermissionOpenSettings.setOnClickListener {
            openAppSettings()
        }
        connectionAction.setOnClickListener {
            handleConnectionAction(resolveConnectivityUiModel().action)
        }
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateMessageCharacterCounter(messageCharCounter, s?.length ?: 0)
                isTyping = true
                typingIdleRunnable?.let { statusHandler.removeCallbacks(it) }
                typingIdleRunnable = Runnable {
                    isTyping = false
                    refreshPending = true
                    lastRefreshAtMs = 0L
                }.also { statusHandler.postDelayed(it, TYPING_IDLE_MS) }
            }
        })

        messageSend.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isBlank()) {
                messageStatus.visibility = View.GONE
                return@setOnClickListener
            }
            val connectivityBeforeSend = resolveConnectivityUiModel().state
            Thread {
                val replyToMessageId = selectedReplyMessageId
                val editMessageId = selectedEditMessageId
                val result = if (editMessageId != null) {
                    editMessageWithRetry(gid, editMessageId, text)
                } else {
                    sendMessageWithRetry(gid, text, replyToMessageId)
                }
                runOnUiThread {
                    if (result.eventId != null) {
                        messageInput.setText("")
                        clearComposeMode()
                        if (result.error == null) {
                            messageStatus.visibility = View.GONE
                            if (connectivityBeforeSend == GroupChatConnectivityState.NoActivePath) {
                                Snackbar.make(
                                    findViewById(android.R.id.content),
                                    R.string.group_chat_message_queued_snackbar,
                                    Snackbar.LENGTH_SHORT,
                                ).show()
                            }
                        } else {
                            messageStatus.text = result.error
                            messageStatus.visibility = View.VISIBLE
                        }
                        // Track eventId only via snapshot/refresh flow.
                        result.localMessage?.let { appendSnapshot(it, adapter, messagesEmpty, messagesList) }
                    } else {
                        if (!result.error.isNullOrBlank()) {
                            messageStatus.text = result.error
                            messageStatus.visibility = View.VISIBLE
                        }
                    }
                }
            }.start()
        }
    }

    private fun confirmDeleteMessage(
        gid: String,
        message: app.peermesh.data.db.entity.ChatMessageEntity,
        adapter: ChatMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
    ) {
        val actorDid = DeviceIdProvider.get(this)
        if (gid.isBlank() || message.actor_did != actorDid) return
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_delete_confirm_title)
            .setMessage(R.string.chat_delete_confirm_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.chat_delete_confirm_cta) { _, _ ->
                Thread {
                    val result = deleteMessageOnce(gid, message.message_id)
                    runOnUiThread {
                        if (result.error != null) {
                            findViewById<TextView>(R.id.message_send_status).apply {
                                text = result.error
                                visibility = View.VISIBLE
                            }
                            return@runOnUiThread
                        }
                        if (selectedReplyMessageId == message.message_id) {
                            clearReply()
                        }
                        if (selectedEditMessageId == message.message_id) {
                            clearEdit()
                        }
                        result.updatedMessage?.let {
                            replaceSnapshotMessage(it, adapter, emptyView, listView)
                        } ?: runRefreshNow()
                    }
                }.start()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        val gid = intent.getStringExtra(EXTRA_GID) ?: return
        ActiveGroupManager.setActiveGroup(
            context = this,
            gid = gid,
            onLocationUnavailable = {
                StatusIndicatorUpdater.showLocationRequiredDialog(this)
            },
            onBluetoothUnavailable = {
                StatusIndicatorUpdater.showBluetoothUnavailableDialog(this)
            },
        )
        StatusIndicatorUpdater.start(this)
        chatActive = true
        val adapter = (findViewById<RecyclerView>(R.id.messages_list)).adapter as? ChatMessageAdapter
            ?: return
        adapter.setCurrentActorId(DeviceIdProvider.get(this))
        val statusView = findViewById<TextView>(R.id.message_send_status)
        val sendButton = findViewById<ImageButton>(R.id.message_send)
        val inputView = findViewById<EditText>(R.id.message_input)
        val blePermissionWarning = findViewById<TextView>(R.id.ble_permission_warning)
        val blePermissionGrant = findViewById<Button>(R.id.ble_permission_grant)
        val blePermissionSettingsHint = findViewById<TextView>(R.id.ble_permission_settings_hint)
        val blePermissionOpenSettings = findViewById<Button>(R.id.ble_permission_open_settings)
        val connectionStatus = findViewById<View>(R.id.group_chat_connection_status)
        val connectionPrimary = findViewById<TextView>(R.id.group_chat_connection_primary)
        val connectionBody = findViewById<TextView>(R.id.group_chat_connection_body)
        val connectionAction = findViewById<Button>(R.id.group_chat_connection_action)
        val messageCharCounter = findViewById<TextView>(R.id.message_char_counter)
        val gidValue = gid
        updateSendState(
            inputView,
            sendButton,
            statusView,
            blePermissionWarning,
            blePermissionGrant,
            blePermissionSettingsHint,
            blePermissionOpenSettings,
            statusView
        )
        refreshParticipationState(
            gid = gidValue,
            inputView = inputView,
            sendButton = sendButton,
            statusView = statusView,
            permissionWarning = blePermissionWarning,
            permissionGrant = blePermissionGrant,
            permissionSettingsHint = blePermissionSettingsHint,
            permissionOpenSettings = blePermissionOpenSettings,
            noNearbyStatusView = statusView,
        )
        refreshConnectionStatusArea(connectionStatus, connectionPrimary, connectionBody, connectionAction)
        updateMessageCharacterCounter(messageCharCounter, inputView.text?.length ?: 0)
        dispatchSeenAcksForLoadedMessages(gidValue, snapshotMessages)

        val listener = object : IngestNotifier.Listener {
            override fun onEventInserted(gid: String, eventId: String) {
                if (gid != gidValue) return
                val db = AppDatabaseProvider.get(applicationContext)
                val eventRow = db.eventsDao().findById(gidValue, eventId)
                val messageRow = db.chatDao().findMessage(gidValue, eventId)
                    ?: db.chatDao().findMessageByEventId(gidValue, eventId)
                val messageText = messageRow?.text
                val messageActor = messageRow?.actor_did
                val messageCreated = messageRow?.created_t ?: eventRow?.created_t
                val source = eventRow?.source
                val targetMessageId = eventRow?.ref_target ?: eventRow?.ref_parent ?: messageRow?.message_id
                runOnUiThread {
                    android.util.Log.i(
                        "GroupChatActivity",
                        "Render messages gid=$gidValue eventId=$eventId tMs=${System.currentTimeMillis()}"
                    )
                    ActivityLog.add(
                        label = "Rendered message at",
                        source = source,
                        message = buildString {
                            if (!messageText.isNullOrBlank()) {
                                append("text=")
                                append(messageText)
                                append(" ")
                            }
                            if (!messageActor.isNullOrBlank()) {
                                append("actor=")
                                append(messageActor)
                                append(" ")
                            }
                            if (messageCreated != null) {
                                append("created_s=")
                                append(messageCreated)
                                append(" ")
                            }
                            append("applied_event_id=")
                            append(eventId)
                            append(" ")
                            append("target_message_id=")
                            append(targetMessageId ?: "none")
                            append(" ")
                            append("row_message_id=")
                            append(messageRow?.message_id ?: "none")
                            append(" ")
                            append("row_last_event_id=")
                            append(messageRow?.last_event_id ?: "none")
                        },
                        eventId = eventId,
                        gid = gidValue,
                    )
                    if (messageActor != null && messageActor != DeviceIdProvider.get(applicationContext)) {
                        MeshAckManager.sendSeenAck(gidValue, eventId)
                    }
                    refreshParticipationState(
                        gid = gidValue,
                        inputView,
                        sendButton,
                        statusView,
                        blePermissionWarning,
                        blePermissionGrant,
                        blePermissionSettingsHint,
                        blePermissionOpenSettings,
                        statusView,
                    )
                }
            }
        }
        ingestListener = listener
        IngestNotifier.add(listener)

        val statusUpdate = Runnable {
            updateSendState(
                inputView,
                sendButton,
                statusView,
                blePermissionWarning,
                blePermissionGrant,
                blePermissionSettingsHint,
                blePermissionOpenSettings,
                statusView
            )
            refreshConnectionStatusArea(connectionStatus, connectionPrimary, connectionBody, connectionAction)
            updateMessageCharacterCounter(messageCharCounter, inputView.text?.length ?: 0)
            statusHandler.postDelayed(statusRunnable!!, STATUS_REFRESH_MS)
        }
        statusRunnable = statusUpdate
        statusHandler.postDelayed(statusUpdate, STATUS_REFRESH_MS)
        registerStatusReceiverIfNeeded()
        registerNetworkCallbackIfNeeded()
        if (connectivityBleStatusListener == null) {
            connectivityBleStatusListener = {
                runOnUiThread { refreshConnectivityViews() }
            }.also(app.peermesh.ble.BleMeshStatus::addListener)
        }
        refreshConnectivityViews()
        startRefreshTicker()
    }

    override fun onPause() {
        super.onPause()
        chatActive = false
        StatusIndicatorUpdater.stop(this)
        statusRunnable?.let { statusHandler.removeCallbacks(it) }
        statusRunnable = null
        stopRefreshTicker()
        typingIdleRunnable?.let { statusHandler.removeCallbacks(it) }
        typingIdleRunnable = null
        isTyping = false
        unregisterNetworkCallbackIfNeeded()
        unregisterStatusReceiverIfNeeded()
        connectivityBleStatusListener?.let(app.peermesh.ble.BleMeshStatus::removeListener)
        connectivityBleStatusListener = null
        ingestListener?.let { IngestNotifier.remove(it) }
        ingestListener = null
    }

    override fun onDestroy() {
        messageScrollListener?.let { findViewById<RecyclerView>(R.id.messages_list).removeOnScrollListener(it) }
        messageScrollListener = null
        replyJumpScrollListener?.let { findViewById<RecyclerView>(R.id.messages_list).removeOnScrollListener(it) }
        replyJumpScrollListener = null
        messageHighlightClearRunnable?.let { statusHandler.removeCallbacks(it) }
        messageHighlightClearRunnable = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLE_PERM) return
        val statusView = findViewById<TextView>(R.id.message_send_status)
        val sendButton = findViewById<ImageButton>(R.id.message_send)
        val inputView = findViewById<EditText>(R.id.message_input)
        val blePermissionWarning = findViewById<TextView>(R.id.ble_permission_warning)
        val blePermissionGrant = findViewById<Button>(R.id.ble_permission_grant)
        val blePermissionSettingsHint = findViewById<TextView>(R.id.ble_permission_settings_hint)
        val blePermissionOpenSettings = findViewById<Button>(R.id.ble_permission_open_settings)
        val connectionStatus = findViewById<View>(R.id.group_chat_connection_status)
        val connectionPrimary = findViewById<TextView>(R.id.group_chat_connection_primary)
        val connectionBody = findViewById<TextView>(R.id.group_chat_connection_body)
        val connectionAction = findViewById<Button>(R.id.group_chat_connection_action)
        updateSendState(
            inputView,
            sendButton,
            statusView,
            blePermissionWarning,
            blePermissionGrant,
            blePermissionSettingsHint,
            blePermissionOpenSettings,
            statusView
        )
        refreshParticipationState(
            gid = intent.getStringExtra(EXTRA_GID).orEmpty(),
            inputView = inputView,
            sendButton = sendButton,
            statusView = statusView,
            permissionWarning = blePermissionWarning,
            permissionGrant = blePermissionGrant,
            permissionSettingsHint = blePermissionSettingsHint,
            permissionOpenSettings = blePermissionOpenSettings,
            noNearbyStatusView = statusView,
        )
        refreshConnectionStatusArea(connectionStatus, connectionPrimary, connectionBody, connectionAction)
        updateMessageCharacterCounter(findViewById(R.id.message_char_counter), inputView.text?.length ?: 0)
    }

    private fun loadMessages(
        gid: String,
        adapter: ChatMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
        onLoaded: ((List<app.peermesh.data.db.entity.ChatMessageEntity>) -> Unit)? = null,
    ) {
        refreshGid = gid
        refreshAdapter = adapter
        refreshEmptyView = emptyView
        refreshListView = listView
        runRefreshNow(onLoaded)
    }

    private fun runRefreshNow(
        onLoaded: ((List<app.peermesh.data.db.entity.ChatMessageEntity>) -> Unit)? = null,
    ) {
        if (actionMenuVisible) {
            refreshPending = true
            return
        }
        if (!autoRefreshEnabled && !forceRefreshToBottom) return
        if (refreshInFlight) {
            val gid = refreshGid ?: return
            val adapter = refreshAdapter ?: return
            val emptyView = refreshEmptyView ?: return
            val listView = refreshListView ?: return
            scheduleRefresh(gid, adapter, emptyView, listView)
            return
        }
        val gid = refreshGid ?: return
        val adapter = refreshAdapter ?: return
        val emptyView = refreshEmptyView ?: return
        val listView = refreshListView ?: return
        refreshInFlight = true
        val db = AppDatabaseProvider.get(this)
        val startMs = System.currentTimeMillis()
        android.util.Log.i("GroupChatActivity", "Load messages start gid=$gid tMs=$startMs")
        Thread {
            val hiddenPeerIds = moderationRepository.hiddenMessagePeerIds(gid)
            val messages = db.chatDao()
                .listMessages(gid, RECENT_MESSAGES_LIMIT)
                .filterNot { it.actor_did in hiddenPeerIds }
            val profiles = db.userProfileDao().listByGid(gid)
            val profileMap = profiles.associate {
                it.actor_did to ChatMessageAdapter.ProfileUi(it.display_name, it.avatar_id)
            }
            runOnUiThread {
                currentProfiles = profileMap
                val queryDoneMs = System.currentTimeMillis()
                val latestDb = messages.lastOrNull()
                ActivityLog.add(
                    label = "Chat refresh latest db",
                    source = "ui",
                    message = if (latestDb != null) {
                        "text=${latestDb.text} created_t=${latestDb.created_t} updated_t=${latestDb.updated_t}"
                    } else {
                        "empty"
                    },
                    eventId = latestDb?.last_event_id,
                    gid = gid,
                )
                snapshotMessages.clear()
                snapshotMessages.addAll(messages)
                if (pendingBuffer.isNotEmpty()) {
                    snapshotMessages.addAll(pendingBuffer)
                    pendingBuffer.clear()
                }
                if (snapshotMessages.isEmpty()) {
                    emptyView.text = getString(R.string.chat_messages_empty)
                    emptyView.visibility = View.VISIBLE
                } else {
                    emptyView.visibility = View.GONE
                    adapter.setProfiles(profileMap)
                    adapter.submitList(snapshotMessages.toList())
                    renderComposerState()
                    dispatchSeenAcksForLoadedMessages(gid, snapshotMessages)
                    if (forceRefreshToBottom) {
                        listView.scrollToPosition(snapshotMessages.size - 1)
                    }
                }
                onLoaded?.invoke(snapshotMessages)
                val renderMs = System.currentTimeMillis()
                android.util.Log.i(
                    "GroupChatActivity",
                    "Load messages done gid=$gid totalMs=${renderMs - startMs} " +
                        "queryToUiMs=${queryDoneMs - startMs} renderMs=${renderMs - queryDoneMs} " +
                        "count=${snapshotMessages.size}",
                )
                refreshInFlight = false
                forceRefreshToBottom = false
            }
        }.start()
    }

    private fun scheduleRefresh(
        gid: String,
        adapter: ChatMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
    ) {
        refreshGid = gid
        refreshAdapter = adapter
        refreshEmptyView = emptyView
        refreshListView = listView
        refreshPending = true
    }

    private fun loadOlderMessages(
        gid: String,
        adapter: ChatMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
    ) {
        if (paginationInFlight || !hasMoreHistory || snapshotMessages.isEmpty()) return
        val oldest = snapshotMessages.firstOrNull() ?: return
        paginationInFlight = true
        val firstVisible = (listView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
        val firstTop = listView.getChildAt(0)?.top ?: 0
        val db = AppDatabaseProvider.get(this)
        Thread {
            val hiddenPeerIds = moderationRepository.hiddenMessagePeerIds(gid)
            val older = db.chatDao()
                .listMessagesBefore(
                    gid = gid,
                    beforeCreatedT = oldest.created_t,
                    beforeMessageId = oldest.message_id,
                    limit = RECENT_MESSAGES_LIMIT,
                )
                .filterNot { it.actor_did in hiddenPeerIds }
            runOnUiThread {
                if (older.isEmpty()) {
                    hasMoreHistory = false
                    paginationInFlight = false
                    return@runOnUiThread
                }
                snapshotMessages.addAll(0, older)
                emptyView.visibility = View.GONE
                adapter.submitList(snapshotMessages.toList())
                renderComposerState()
                dispatchSeenAcksForLoadedMessages(gid, older)
                val lm = listView.layoutManager as? LinearLayoutManager
                lm?.scrollToPositionWithOffset(firstVisible + older.size, firstTop)
                paginationInFlight = false
            }
        }.start()
    }

    private fun startRefreshTicker() {
        if (refreshTickerRunnable != null) return
        val ticker = object : Runnable {
            override fun run() {
                if (!chatActive) return
                if (!isTyping && !refreshInFlight && !actionMenuVisible && (autoRefreshEnabled || forceRefreshToBottom)) {
                    val now = System.currentTimeMillis()
                    if (now - lastRefreshAtMs >= REFRESH_INTERVAL_MS) {
                        lastRefreshAtMs = now
                        runRefreshNow()
                    }
                }
                statusHandler.postDelayed(this, REFRESH_TICK_MS)
            }
        }
        refreshTickerRunnable = ticker
        statusHandler.post(ticker)
    }

    private fun stopRefreshTicker() {
        refreshTickerRunnable?.let { statusHandler.removeCallbacks(it) }
        refreshTickerRunnable = null
        refreshPending = false
        forceRefreshToBottom = false
    }

    private fun appendSnapshot(
        message: app.peermesh.data.db.entity.ChatMessageEntity,
        adapter: ChatMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
    ) {
        if (refreshInFlight) {
            pendingBuffer.add(message)
            return
        }
        snapshotMessages.add(message)
        emptyView.visibility = View.GONE
        adapter.submitList(snapshotMessages.toList())
        renderComposerState()
        dispatchSeenAcksForLoadedMessages(message.gid, listOf(message))
        listView.scrollToPosition(snapshotMessages.size - 1)
    }

    private fun replaceSnapshotMessage(
        message: app.peermesh.data.db.entity.ChatMessageEntity,
        adapter: ChatMessageAdapter,
        emptyView: TextView,
        listView: RecyclerView,
    ) {
        val index = snapshotMessages.indexOfFirst { it.message_id == message.message_id }
        if (index < 0) {
            appendSnapshot(message, adapter, emptyView, listView)
            return
        }
        snapshotMessages[index] = message
        adapter.submitList(snapshotMessages.toList())
        if (selectedEditMessageId == message.message_id &&
            message.status == app.peermesh.data.reducer.ReducerConstants.STATUS_DELETED
        ) {
            clearEdit()
            return
        }
        renderComposerState()
    }

    private fun dispatchSeenAcksForLoadedMessages(
        gid: String,
        messages: List<app.peermesh.data.db.entity.ChatMessageEntity>,
    ) {
        if (!chatActive || messages.isEmpty()) return
        val currentActor = DeviceIdProvider.get(this)
        messages.forEach { message ->
            if (message.actor_did == currentActor) return@forEach
            if (parseSeenByMap(message.seen_by).containsKey(currentActor)) return@forEach
            SeenAckCloudBuffer.enqueue(gid, message.message_id)
            MeshAckManager.sendSeenAck(gid, message.message_id)
        }
    }

    private fun sendMessageWithRetry(gid: String, text: String, replyToMessageId: String?): SendResult {
        var attempt = 0
        var lastError: String? = null
        while (attempt < INSERT_RETRY_MAX) {
            val result = sendMessageOnce(gid, text, replyToMessageId)
            if (result.eventId != null) return result
            lastError = result.error
            attempt += 1
            if (attempt < INSERT_RETRY_MAX) {
                Thread.sleep(INSERT_RETRY_COOLDOWN_MS)
            }
        }
        return SendResult(lastError, null, null)
    }

    private fun editMessageWithRetry(gid: String, targetMessageId: String, text: String): SendResult {
        var attempt = 0
        var lastError: String? = null
        while (attempt < INSERT_RETRY_MAX) {
            val result = editMessageOnce(gid, targetMessageId, text)
            if (result.eventId != null) return result
            lastError = result.error
            attempt += 1
            if (attempt < INSERT_RETRY_MAX) {
                Thread.sleep(INSERT_RETRY_COOLDOWN_MS)
            }
        }
        return SendResult(lastError, null, null)
    }

    private fun sendMessageOnce(gid: String, text: String, replyToMessageId: String?): SendResult {
        val db = AppDatabaseProvider.get(this)
        val actorDid = DeviceIdProvider.get(this)
        if (GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)) {
            return SendResult(getString(R.string.group_removed_cannot_participate), null, null)
        }
        val nowSeconds = System.currentTimeMillis() / 1000L
        val lamport = System.currentTimeMillis()
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val core = EventFactory.chatMessage(
            eventId = eventId,
            actorDid = actorDid,
            createdSeconds = nowSeconds,
            lamport = lamport,
            text = text,
            replyTo = replyToMessageId,
        )
        val coreWithKid = KeyIdHelper.attachKeyId(this, gid, core)
        val payloadJson = CanonicalJson.json.encodeToString(EventCore.serializer(), coreWithKid)
        val event = EventEntity(
            gid = gid,
            event_id = eventId,
            type = coreWithKid.type,
            source = "local",
            created_t = nowSeconds,
            time_lt = coreWithKid.time.lt ?: 0L,
            actor_did = coreWithKid.actor.did,
            ref_root = coreWithKid.ref.root,
            ref_parent = coreWithKid.ref.parent,
            ref_target = coreWithKid.ref.target,
            ttl_s = coreWithKid.meta?.ttl_s,
            payload_json = payloadJson,
            inserted_at = nowSeconds,
        )
        var inserted = false
        db.runInTransaction {
            val result = db.eventsDao().insert(event)
            if (result != -1L) {
                inserted = true
                ChatReducer(db.chatDao(), db.deletedEventDao(), db.eventsDao()).apply(coreWithKid, gid)
            }
        }
        if (!inserted) return SendResult(getString(R.string.chat_send_failed), null, null)

        val accepted = UserOutbox.enqueue(
            context = this,
            gid = gid,
            core = coreWithKid,
            source = "mesh",
            successLabel = "Sent message at",
            successMessage = text,
        )
        if (!accepted) return SendResult(getString(R.string.group_removed_cannot_participate), null, null)
        val localMessage = db.chatDao().findMessage(gid, eventId)
        return SendResult(null, eventId, localMessage)
    }

    private fun deleteMessageOnce(gid: String, targetMessageId: String): DeleteResult {
        val db = AppDatabaseProvider.get(this)
        val targetMessage = db.chatDao().findMessage(gid, targetMessageId)
            ?: return DeleteResult(getString(R.string.chat_delete_failed), null)
        val actorDid = DeviceIdProvider.get(this)
        if (GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)) {
            return DeleteResult(getString(R.string.group_removed_cannot_participate), null)
        }
        if (targetMessage.actor_did != actorDid) {
            return DeleteResult(getString(R.string.chat_delete_failed), null)
        }
        val nowSeconds = System.currentTimeMillis() / 1000L
        val lamport = System.currentTimeMillis()
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val core = EventFactory.chatDelete(
            eventId = eventId,
            actorDid = actorDid,
            createdSeconds = nowSeconds,
            lamport = lamport,
            targetMessageId = targetMessageId,
        )
        val coreWithKid = KeyIdHelper.attachKeyId(this, gid, core)
        val payloadJson = CanonicalJson.json.encodeToString(EventCore.serializer(), coreWithKid)
        val event = EventEntity(
            gid = gid,
            event_id = eventId,
            type = coreWithKid.type,
            source = "local",
            created_t = nowSeconds,
            time_lt = coreWithKid.time.lt ?: 0L,
            actor_did = coreWithKid.actor.did,
            ref_root = coreWithKid.ref.root,
            ref_parent = coreWithKid.ref.parent,
            ref_target = coreWithKid.ref.target,
            ttl_s = coreWithKid.meta?.ttl_s,
            payload_json = payloadJson,
            inserted_at = nowSeconds,
        )
        var inserted = false
        db.runInTransaction {
            val result = db.eventsDao().insert(event)
            if (result != -1L) {
                inserted = true
                ChatReducer(db.chatDao(), db.deletedEventDao(), db.eventsDao()).apply(coreWithKid, gid)
            }
        }
        if (!inserted) return DeleteResult(getString(R.string.chat_delete_failed), null)
        val accepted = UserOutbox.enqueue(
            context = this,
            gid = gid,
            core = coreWithKid,
            source = "mesh",
            successLabel = "Sent delete at",
            successMessage = "target=$targetMessageId",
        )
        if (!accepted) return DeleteResult(getString(R.string.group_removed_cannot_participate), null)
        return DeleteResult(null, db.chatDao().findMessage(gid, targetMessageId))
    }

    private fun editMessageOnce(gid: String, targetMessageId: String, text: String): SendResult {
        val db = AppDatabaseProvider.get(this)
        val targetMessage = db.chatDao().findMessage(gid, targetMessageId)
            ?: return SendResult(getString(R.string.chat_edit_failed), null, null)
        val actorDid = DeviceIdProvider.get(this)
        if (GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)) {
            return SendResult(getString(R.string.group_removed_cannot_participate), null, null)
        }
        if (targetMessage.actor_did != actorDid ||
            targetMessage.status == app.peermesh.data.reducer.ReducerConstants.STATUS_DELETED
        ) {
            return SendResult(getString(R.string.chat_edit_failed), null, null)
        }
        val nowSeconds = System.currentTimeMillis() / 1000L
        val lamport = System.currentTimeMillis()
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val core = EventFactory.chatEdit(
            eventId = eventId,
            actorDid = actorDid,
            createdSeconds = nowSeconds,
            lamport = lamport,
            targetMessageId = targetMessageId,
            targetCreatedSeconds = targetMessage.created_t,
            text = text,
        )
        val coreWithKid = KeyIdHelper.attachKeyId(this, gid, core)
        val payloadJson = CanonicalJson.json.encodeToString(EventCore.serializer(), coreWithKid)
        val event = EventEntity(
            gid = gid,
            event_id = eventId,
            type = coreWithKid.type,
            source = "local",
            created_t = nowSeconds,
            time_lt = coreWithKid.time.lt ?: 0L,
            actor_did = coreWithKid.actor.did,
            ref_root = coreWithKid.ref.root,
            ref_parent = coreWithKid.ref.parent,
            ref_target = coreWithKid.ref.target,
            ttl_s = coreWithKid.meta?.ttl_s,
            payload_json = payloadJson,
            inserted_at = nowSeconds,
        )
        var inserted = false
        db.runInTransaction {
            val result = db.eventsDao().insert(event)
            if (result != -1L) {
                inserted = true
                ChatReducer(db.chatDao(), db.deletedEventDao(), db.eventsDao()).apply(coreWithKid, gid)
            }
        }
        if (!inserted) return SendResult(getString(R.string.chat_edit_failed), null, null)
        val accepted = UserOutbox.enqueue(
            context = this,
            gid = gid,
            core = coreWithKid,
            source = "mesh",
            successLabel = "Sent edit at",
            successMessage = "target=$targetMessageId",
        )
        if (!accepted) return SendResult(getString(R.string.group_removed_cannot_participate), null, null)
        return SendResult(null, eventId, db.chatDao().findMessage(gid, targetMessageId))
    }

    private fun startReply(message: app.peermesh.data.db.entity.ChatMessageEntity) {
        if (selectedEditMessageId != null) {
            findViewById<EditText>(R.id.message_input).setText("")
        }
        selectedEditMessageId = null
        selectedEditMessagePreview = null
        selectedReplyMessageId = message.message_id
        selectedReplyMessagePreview = message
        renderComposerState()
    }

    private fun clearReply() {
        selectedReplyMessageId = null
        selectedReplyMessagePreview = null
        renderComposerState()
    }

    private fun startEdit(message: app.peermesh.data.db.entity.ChatMessageEntity) {
        selectedReplyMessageId = null
        selectedReplyMessagePreview = null
        selectedEditMessageId = message.message_id
        selectedEditMessagePreview = message
        val inputView = findViewById<EditText>(R.id.message_input)
        inputView.setText(message.text)
        inputView.setSelection(inputView.text?.length ?: 0)
        renderComposerState()
    }

    private fun clearEdit() {
        selectedEditMessageId = null
        selectedEditMessagePreview = null
        findViewById<EditText>(R.id.message_input).setText("")
        renderComposerState()
    }

    private fun clearComposeMode() {
        val editing = selectedEditMessageId != null
        selectedReplyMessageId = null
        selectedReplyMessagePreview = null
        selectedEditMessageId = null
        selectedEditMessagePreview = null
        if (editing) {
            findViewById<EditText>(R.id.message_input).setText("")
        }
        renderComposerState()
    }

    private fun renderComposerState() {
        val container = findViewById<View>(R.id.reply_compose_container)
        val actorView = findViewById<TextView>(R.id.reply_compose_actor)
        val textView = findViewById<TextView>(R.id.reply_compose_text)
        val closeButton = findViewById<ImageButton>(R.id.reply_compose_close)
        val composeMessageId = selectedEditMessageId ?: selectedReplyMessageId
        val message = composeMessageId?.let { messageId ->
            snapshotMessages.firstOrNull { it.message_id == messageId } ?:
                selectedEditMessagePreview ?: selectedReplyMessagePreview
        }
        if (message == null || composeMessageId.isNullOrBlank()) {
            container.visibility = View.GONE
            return
        }
        if (selectedEditMessageId != null) {
            selectedEditMessagePreview = message
            actorView.text = getString(R.string.chat_editing_label)
            closeButton.contentDescription = getString(R.string.chat_edit_cancel)
        } else {
            selectedReplyMessagePreview = message
            val actorLabel = currentProfiles[message.actor_did]?.displayName ?: message.actor_did.take(12)
            actorView.text = getString(R.string.chat_replying_to_format, actorLabel)
            closeButton.contentDescription = getString(R.string.chat_reply_cancel)
        }
        textView.text = message.text.ifBlank { getString(R.string.chat_reply_original_unavailable) }
        container.visibility = View.VISIBLE
    }

    private fun scrollToMessage(messageId: String) {
        val index = snapshotMessages.indexOfFirst { it.message_id == messageId }
        if (index < 0) {
            android.widget.Toast.makeText(
                this,
                R.string.chat_reply_jump_unavailable,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val listView = findViewById<RecyclerView>(R.id.messages_list)
        val adapter = listView.adapter as? ChatMessageAdapter
        val layoutManager = listView.layoutManager as? LinearLayoutManager
        if (adapter == null || layoutManager == null) {
            listView.smoothScrollToPosition(index)
            return
        }

        replyJumpScrollListener?.let { listView.removeOnScrollListener(it) }
        replyJumpScrollListener = null

        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val alreadyVisible = index in firstVisible..lastVisible

        val highlightTarget = Runnable {
            highlightMessage(adapter, messageId)
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

    private fun showBlockPeerDialog(gid: String, targetPeerId: String) {
        if (participationBlocked) {
            toast(getString(R.string.group_removed_actions_unavailable))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.moderation_block_peer)
            .setMessage(R.string.moderation_block_peer_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.moderation_block_confirm) { _, _ ->
                Thread {
                    moderationRepository.blockPeerLocally(
                        groupId = gid,
                        targetPeerId = targetPeerId,
                        reasonCode = ModerationReason.HARASSMENT.code,
                    )
                    runOnUiThread {
                        toast(getString(R.string.moderation_block_success))
                        runRefreshNow()
                    }
                }.start()
            }
            .show()
    }

    private fun showReportPeerDialog(gid: String, targetPeerId: String) {
        if (participationBlocked) {
            toast(getString(R.string.group_removed_actions_unavailable))
            return
        }
        showReasonPicker { reason ->
            showReportNoteDialog(
                titleRes = R.string.moderation_report_peer,
                includePlaintextToggle = false,
            ) { note, _, alsoBlock ->
                Thread {
                    moderationRepository.createPeerReport(
                        groupId = gid,
                        reportedPeerId = targetPeerId,
                        reasonCode = reason.code,
                        userNote = note,
                        alsoBlock = alsoBlock,
                    )
                    runOnUiThread {
                        toast(getString(R.string.moderation_report_saved))
                        if (alsoBlock) {
                            runRefreshNow()
                        }
                    }
                }.start()
            }
        }
    }

    private fun showReportMessageDialog(gid: String, message: app.peermesh.data.db.entity.ChatMessageEntity) {
        if (participationBlocked) {
            toast(getString(R.string.group_removed_actions_unavailable))
            return
        }
        showReasonPicker { reason ->
            showReportNoteDialog(
                titleRes = R.string.moderation_report_message,
                includePlaintextToggle = true,
            ) { note, includePlaintext, alsoBlock ->
                Thread {
                    moderationRepository.createMessageReport(
                        groupId = gid,
                        message = message,
                        reasonCode = reason.code,
                        userNote = note,
                        includePlaintext = includePlaintext,
                        alsoBlock = alsoBlock,
                    )
                    runOnUiThread {
                        toast(getString(R.string.moderation_report_saved))
                        if (alsoBlock) {
                            runRefreshNow()
                        }
                    }
                }.start()
            }
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

    private fun highlightMessage(adapter: ChatMessageAdapter, messageId: String) {
        messageHighlightClearRunnable?.let { statusHandler.removeCallbacks(it) }
        adapter.setHighlightedMessageId(messageId)
        messageHighlightClearRunnable = Runnable {
            adapter.setHighlightedMessageId(null)
            messageHighlightClearRunnable = null
        }.also { statusHandler.postDelayed(it, REPLY_HIGHLIGHT_DURATION_MS) }
    }

    private fun setSendEnabled(
        inputView: EditText,
        sendButton: ImageButton,
        enabled: Boolean,
    ) {
        inputView.isEnabled = enabled
        inputView.alpha = if (enabled) 1.0f else 0.6f
        sendButton.isEnabled = enabled
        sendButton.alpha = if (enabled) 1.0f else 0.4f
    }

    private fun hasBlePermissions(): Boolean {
        return BlePermissionHelper.requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(this, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isBleReady(): Boolean {
        val manager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
    }

    private fun updateSendState(
        inputView: EditText,
        sendButton: ImageButton,
        statusView: TextView,
        permissionWarning: TextView,
        permissionGrant: Button,
        permissionSettingsHint: TextView,
        permissionOpenSettings: Button,
        noNearbyStatusView: TextView,
    ) {
        if (participationBlocked) {
            clearComposeMode()
            setSendEnabled(inputView, sendButton, false)
            inputView.hint = getString(R.string.group_removed_chat_read_only)
            statusView.text = getString(R.string.group_removed_cannot_participate)
            statusView.visibility = View.VISIBLE
            permissionWarning.visibility = View.GONE
            permissionGrant.visibility = View.GONE
            permissionSettingsHint.visibility = View.GONE
            permissionOpenSettings.visibility = View.GONE
            return
        }
        inputView.hint = getString(R.string.chat_message_hint)
        inputView.isEnabled = true
        inputView.alpha = 1.0f
        sendButton.isEnabled = true
        sendButton.alpha = 1.0f

        if (statusView.text == getString(R.string.chat_ble_permission_required) ||
            statusView.text == getString(R.string.chat_ble_not_ready) ||
            statusView.text == getString(R.string.chat_ble_no_device)
        ) {
            statusView.visibility = View.GONE
        }
        if (noNearbyStatusView.text == getString(R.string.chat_ble_no_device)) {
            noNearbyStatusView.visibility = View.GONE
        }
        permissionWarning.visibility = View.GONE
        permissionGrant.visibility = View.GONE
        permissionSettingsHint.visibility = View.GONE
        permissionOpenSettings.visibility = View.GONE
    }

    private fun refreshParticipationState(
        gid: String,
        inputView: EditText,
        sendButton: ImageButton,
        statusView: TextView,
        permissionWarning: TextView,
        permissionGrant: Button,
        permissionSettingsHint: TextView,
        permissionOpenSettings: Button,
        noNearbyStatusView: TextView,
    ) {
        if (gid.isBlank()) return
        val actorDid = DeviceIdProvider.get(this)
        Thread {
            val blocked = GroupMembershipPolicy.isPeerRemoved(
                AppDatabaseProvider.get(applicationContext),
                gid,
                actorDid,
            )
            runOnUiThread {
                participationBlocked = blocked
                refreshAdapter?.setActionsEnabled(!blocked)
                ActivityLog.add(
                    label = "Group chat participation state",
                    source = "ui",
                    message = "gid=$gid actor=$actorDid blocked=$blocked",
                    gid = gid,
                )
                updateSendState(
                    inputView = inputView,
                    sendButton = sendButton,
                    statusView = statusView,
                    permissionWarning = permissionWarning,
                    permissionGrant = permissionGrant,
                    permissionSettingsHint = permissionSettingsHint,
                    permissionOpenSettings = permissionOpenSettings,
                    noNearbyStatusView = noNearbyStatusView,
                )
            }
        }.start()
    }

    private fun isOnline(): Boolean {
        val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
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

    private fun updateMessageCharacterCounter(counterView: TextView, messageLength: Int) {
        counterView.text = getString(
            R.string.message_char_counter_format,
            messageLength,
            MAX_MESSAGE_CHARS,
        )
    }

    private fun refreshConnectionStatusArea(
        container: View,
        primaryView: TextView,
        bodyView: TextView,
        actionButton: Button,
    ) {
        if (participationBlocked) {
            container.visibility = View.GONE
            return
        }
        val connectivity = resolveConnectivityUiModel()
        container.visibility = View.VISIBLE
        primaryView.text = connectivity.primaryText
        bodyView.text = connectivity.bodyText
        bodyView.visibility = View.VISIBLE
        container.contentDescription = "${connectivity.primaryText}. ${connectivity.bodyText}"
        if (connectivity.action == ConnectionAction.None) {
            actionButton.visibility = View.GONE
        } else {
            actionButton.setText(connectivity.action.labelRes)
            actionButton.visibility = View.VISIBLE
        }
        findViewById<EditText>(R.id.message_input).hint =
            if (connectivity.state == GroupChatConnectivityState.NoActivePath) {
                getString(R.string.group_chat_message_hint_offline_queued)
            } else {
                getString(R.string.chat_message_hint)
            }
    }

    private fun resolveConnectivityUiModel(): GroupChatConnectivityUiModel {
        val nearbyReadiness = NearbyReadinessHelper.evaluate(applicationContext)
        val nowMs = System.currentTimeMillis()
        val nearbyCount = app.peermesh.ble.BleMeshStatus.nearbyTotalForUi(nowMs) {
            meshTransport.nearbyDeviceCount(nowMs)
        }
        val nearbyHealth = NearbyTransportHealthEvaluator.evaluate(
            NearbyTransportSnapshot(
                systemReady = nearbyReadiness.canUseRoom,
                runtimeRunning = app.peermesh.ble.BleForegroundService.isMeshRunning(),
                unresolvedStall = hasUnresolvedNearbyStall(nowMs),
            ),
        )
        val onlinePath = resolveOnlinePathState(nowMs)
        val connectivityState = GroupChatConnectivityEvaluator.evaluate(
            onlinePath = onlinePath,
            nearbyHealth = nearbyHealth,
            nearbyPeerCount = nearbyCount,
        )
        val action = resolveConnectionAction(nearbyReadiness)
        return when (connectivityState) {
            is GroupChatConnectivityState.OnlineAndNearby -> GroupChatConnectivityUiModel(
                state = connectivityState,
                primaryText = getString(R.string.group_chat_status_online_nearby_primary),
                bodyText = getString(R.string.group_chat_status_online_nearby_secondary, connectivityState.nearbyPeerCount),
                action = ConnectionAction.None,
            )
            is GroupChatConnectivityState.OnlineOnly -> GroupChatConnectivityUiModel(
                state = connectivityState,
                primaryText = getString(R.string.group_chat_status_online_primary),
                bodyText = when (connectivityState.nearbyHealth) {
                    NearbyTransportHealth.Healthy -> getString(R.string.group_chat_status_online_ready_secondary)
                    NearbyTransportHealth.Blocked -> getString(R.string.group_chat_status_online_nearby_paused_secondary)
                    NearbyTransportHealth.Starting -> getString(R.string.group_chat_status_online_starting_secondary)
                    NearbyTransportHealth.Stalled -> getString(R.string.group_chat_status_online_nearby_unavailable_secondary)
                },
                action = ConnectionAction.None,
            )
            is GroupChatConnectivityState.NearbyOnly -> GroupChatConnectivityUiModel(
                state = connectivityState,
                primaryText = getString(
                    if (connectivityState.nearbyPeerCount > 0) R.string.group_chat_status_nearby_active_primary
                    else R.string.group_chat_status_nearby_ready_primary,
                ),
                bodyText = if (connectivityState.nearbyPeerCount > 0) {
                    getString(R.string.group_chat_status_nearby_active_secondary, connectivityState.nearbyPeerCount)
                } else {
                    getString(R.string.group_chat_status_nearby_ready_secondary)
                },
                action = ConnectionAction.None,
            )
            GroupChatConnectivityState.NoActivePath -> GroupChatConnectivityUiModel(
                state = connectivityState,
                primaryText = getString(R.string.group_chat_status_paused_primary),
                bodyText = getString(R.string.group_chat_status_paused_secondary),
                action = action,
            )
            is GroupChatConnectivityState.Degraded -> GroupChatConnectivityUiModel(
                state = connectivityState,
                primaryText = getString(
                    if (connectivityState.reason == GroupChatConnectivityReason.NearbyStarting) {
                        R.string.group_chat_status_nearby_starting_primary
                    } else {
                        R.string.group_chat_status_nearby_unavailable_primary
                    },
                ),
                bodyText = getString(
                    if (connectivityState.reason == GroupChatConnectivityReason.NearbyStarting) {
                        R.string.group_chat_status_nearby_starting_secondary
                    } else if (onlinePath != OnlinePathState.Unavailable) {
                        R.string.group_chat_status_online_nearby_unavailable_secondary
                    } else {
                        R.string.group_chat_status_nearby_unavailable_secondary
                    },
                ),
                action = ConnectionAction.None,
            )
        }
    }

    private fun resolveOnlinePathState(nowMs: Long): OnlinePathState {
        val capabilityAvailable = PeerMeshCapabilities.canUseRelaySync
        val internetValidated = hasValidatedInternetPath()
        val environment = EnvironmentLoader.load()
        val relayUrls = RelaySyncSettings.resolveRelayBaseUrls(this, environment.relayBaseUrl)
        val pathConfigured = RelaySyncUiEvaluator.hasConfiguredPath(
            directRelayCount = relayUrls.size,
            managedConfigured = ManagedRelayAccessCoordinator(this, environment).isConfigured(),
        )
        return OnlinePathEvaluator.evaluate(
            OnlinePathSnapshot(
                capabilityAvailable = capabilityAvailable,
                syncEnabled = RelaySyncSettings.isEnabled(this),
                internetValidated = internetValidated,
                pathConfigured = pathConfigured,
                hasRecentSuccess = RelaySyncSettings.lastSuccessMs(this) > 0L &&
                    nowMs - RelaySyncSettings.lastSuccessMs(this) <= ONLINE_SUCCESS_FRESH_MS,
            ),
        )
    }

    private fun hasUnresolvedNearbyStall(nowMs: Long): Boolean {
        val startupStalled = app.peermesh.ble.BleMeshStatus.lastStartupStallDetectedAtMs >
            app.peermesh.ble.BleMeshStatus.lastStartupRecoveryAtMs
        val transportStalled = app.peermesh.ble.BleMeshStatus.lastStallDetectedAtMs >
            app.peermesh.ble.BleMeshStatus.lastStallRecoveryAtMs
        return (startupStalled && nowMs - app.peermesh.ble.BleMeshStatus.lastStartupStallDetectedAtMs < NEARBY_STALL_WINDOW_MS) ||
            (transportStalled && nowMs - app.peermesh.ble.BleMeshStatus.lastStallDetectedAtMs < NEARBY_STALL_WINDOW_MS)
    }

    private fun resolveConnectionAction(nearbyReadiness: app.peermesh.nearby.NearbyReadinessState): ConnectionAction {
        val missingPermissions = NearbyReadinessHelper.missingPermissionsForRoom(applicationContext)
        return when {
            missingPermissions.isNotEmpty() -> ConnectionAction.RequestNearbyPermissions
            !nearbyReadiness.bluetoothEnabled -> ConnectionAction.OpenBluetoothSettings
            nearbyReadiness.locationRequired && !nearbyReadiness.locationServicesEnabled ->
                ConnectionAction.OpenLocationSettings
            else -> ConnectionAction.CheckAgain
        }
    }

    private fun handleConnectionAction(action: ConnectionAction) {
        when (action) {
            ConnectionAction.None -> Unit
            ConnectionAction.RequestNearbyPermissions -> {
                val missingPermissions = NearbyReadinessHelper.missingPermissionsForRoom(applicationContext)
                if (missingPermissions.isNotEmpty()) {
                    ActivityCompat.requestPermissions(this, missingPermissions, REQ_BLE_PERM)
                }
            }
            ConnectionAction.OpenBluetoothSettings -> openBluetoothSettings()
            ConnectionAction.OpenLocationSettings -> openLocationSettings()
            ConnectionAction.CheckAgain -> {
                refreshConnectionStatusArea(
                    findViewById(R.id.group_chat_connection_status),
                    findViewById(R.id.group_chat_connection_primary),
                    findViewById(R.id.group_chat_connection_body),
                    findViewById(R.id.group_chat_connection_action),
                )
                updateMessageCharacterCounter(
                    findViewById(R.id.message_char_counter),
                    findViewById<EditText>(R.id.message_input).text?.length ?: 0,
                )
            }
        }
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
            addAction(app.peermesh.relay.RelaySyncService.ACTION_RELAY_STATUS_CHANGED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusChangeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusChangeReceiver, filter)
        }
        statusReceiverRegistered = true
    }

    private fun unregisterStatusReceiverIfNeeded() {
        if (!statusReceiverRegistered) return
        unregisterReceiver(statusChangeReceiver)
        statusReceiverRegistered = false
    }

    private fun registerNetworkCallbackIfNeeded() {
        if (networkCallbackRegistered) return
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        synchronized(validatedNetworks) {
            validatedNetworks.clear()
            val activeNetwork = manager.activeNetwork
            val capabilities = activeNetwork?.let(manager::getNetworkCapabilities)
            if (activeNetwork != null && capabilities?.hasValidatedInternet() == true) {
                validatedNetworks.add(activeNetwork)
            }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(connectivityCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                manager.registerNetworkCallback(request, connectivityCallback)
            }
            networkCallbackRegistered = true
        } catch (_: Exception) {
            networkCallbackRegistered = false
        }
    }

    private fun unregisterNetworkCallbackIfNeeded() {
        if (!networkCallbackRegistered) return
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { manager.unregisterNetworkCallback(connectivityCallback) }
        networkCallbackRegistered = false
        synchronized(validatedNetworks) { validatedNetworks.clear() }
    }

    private fun hasValidatedInternetPath(): Boolean {
        if (!networkCallbackRegistered) return isOnline()
        return synchronized(validatedNetworks) { validatedNetworks.isNotEmpty() }
    }

    private fun NetworkCapabilities.hasValidatedInternet(): Boolean {
        return hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun refreshConnectivityViews() {
        if (!chatActive) return
        refreshConnectionStatusArea(
            findViewById(R.id.group_chat_connection_status),
            findViewById(R.id.group_chat_connection_primary),
            findViewById(R.id.group_chat_connection_body),
            findViewById(R.id.group_chat_connection_action),
        )
        updateMessageCharacterCounter(
            findViewById(R.id.message_char_counter),
            findViewById<EditText>(R.id.message_input).text?.length ?: 0,
        )
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_GID = "extra_gid"
        const val EXTRA_GROUP_NAME = "extra_group_name"
        private const val STATUS_REFRESH_MS = 2000L
        private const val ONLINE_SUCCESS_FRESH_MS = 5 * 60_000L
        private const val NEARBY_STALL_WINDOW_MS = 60_000L
        private const val MAX_MESSAGE_CHARS = 200
        private const val REQ_BLE_PERM = 2001
        private const val REFRESH_TICK_MS = 500L
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val TYPING_IDLE_MS = 1000L
        private const val INSERT_RETRY_MAX = 3
        private const val INSERT_RETRY_COOLDOWN_MS = 500L
        private const val RECENT_MESSAGES_LIMIT = 100
        private const val REPLY_HIGHLIGHT_SCROLL_SETTLE_MS = 120L
        private const val REPLY_HIGHLIGHT_DURATION_MS = 500L
    }

    private data class SendResult(
        val error: String?,
        val eventId: String?,
        val localMessage: app.peermesh.data.db.entity.ChatMessageEntity?,
    )

    private data class DeleteResult(
        val error: String?,
        val updatedMessage: app.peermesh.data.db.entity.ChatMessageEntity?,
    )

    private data class GroupChatConnectivityUiModel(
        val state: GroupChatConnectivityState,
        val primaryText: String,
        val bodyText: String,
        val action: ConnectionAction,
    )

    private sealed class ConnectionAction(val labelRes: Int) {
        data object None : ConnectionAction(0)
        data object RequestNearbyPermissions : ConnectionAction(R.string.group_chat_connectivity_action_allow_bluetooth)
        data object OpenBluetoothSettings : ConnectionAction(R.string.group_chat_connectivity_action_turn_on_bluetooth)
        data object OpenLocationSettings : ConnectionAction(R.string.group_chat_connectivity_action_turn_on_location)
        data object CheckAgain : ConnectionAction(R.string.group_chat_connectivity_action_check_again)
    }

    private val statusChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            runOnUiThread { refreshConnectivityViews() }
        }
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val validated = manager.getNetworkCapabilities(network)?.hasValidatedInternet() == true
            synchronized(validatedNetworks) {
                if (validated) validatedNetworks.add(network) else validatedNetworks.remove(network)
            }
            runOnUiThread { refreshConnectivityViews() }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            synchronized(validatedNetworks) {
                if (networkCapabilities.hasValidatedInternet()) {
                    validatedNetworks.add(network)
                } else {
                    validatedNetworks.remove(network)
                }
            }
            runOnUiThread { refreshConnectivityViews() }
        }

        override fun onLost(network: Network) {
            synchronized(validatedNetworks) { validatedNetworks.remove(network) }
            runOnUiThread { refreshConnectivityViews() }
        }
    }

    private enum class ModerationReason(val code: String, val labelRes: Int) {
        HARASSMENT("harassment", R.string.moderation_reason_harassment),
        THREAT("threatening_behavior", R.string.moderation_reason_threatening),
        SPAM("spam", R.string.moderation_reason_spam),
        INAPPROPRIATE("inappropriate_content", R.string.moderation_reason_inappropriate),
        IMPERSONATION("impersonation", R.string.moderation_reason_impersonation),
        OTHER("other", R.string.moderation_reason_other),
    }
}

private fun parseSeenByMap(raw: String?): Map<String, Long> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        val element = Json.decodeFromString(JsonElement.serializer(), raw)
        val obj = element as? JsonObject ?: return emptyMap()
        obj.mapNotNull { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            val ts = if (primitive.isString) primitive.content.toLongOrNull() else primitive.toString().toLongOrNull()
            ts?.let { key to it }
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}
