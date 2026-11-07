package app.peermesh

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.EditText
import android.widget.Button
import android.view.View
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.GroupEntity
import app.peermesh.data.membership.GroupMembershipPolicy
import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.id.GidFactory
import app.peermesh.core.id.GroupKeyIdFactory
import app.peermesh.security.crypto.GroupKeyDeriver
import app.peermesh.security.crypto.SecureKeyStore
import app.peermesh.crash.CrashPromptController
import app.peermesh.beta.BetaProgramUpdatePromptController
import com.google.android.material.navigation.NavigationView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {
    private var activeGid: String? = null
    private var joinInviteInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        headerTitle.text = getString(R.string.page_title_home)

        val groupInviteInput = findViewById<EditText>(R.id.group_gid_input)
        val groupDisplayNameInput = findViewById<EditText>(R.id.group_display_name_input)
        val groupJoin = findViewById<Button>(R.id.group_join_button)
        val groupPasteLink = findViewById<Button>(R.id.group_paste_link)
        val groupPasteHint = findViewById<TextView>(R.id.group_paste_hint)
        val groupCreateLink = findViewById<TextView>(R.id.group_create_hint)
        val groupsEmpty = findViewById<TextView>(R.id.groups_empty)
        val groupsList = findViewById<RecyclerView>(R.id.groups_list)
        val notificationsBanner = findViewById<View>(R.id.main_notifications_banner)
        val notificationsText = findViewById<TextView>(R.id.main_notifications_text)
        val notificationsAction = findViewById<Button>(R.id.main_notifications_action)
        val groupsAdapter = GroupAdapter { group ->
            val intent = Intent(this, GroupChatActivity::class.java).apply {
                putExtra(GroupChatActivity.EXTRA_GID, group.gid)
                putExtra(GroupChatActivity.EXTRA_GROUP_NAME, group.name)
            }
            startActivity(intent)
        }
        joinInviteInput = groupInviteInput
        groupsList.layoutManager = LinearLayoutManager(this)
        groupsList.adapter = groupsAdapter

        handleInviteIntent(intent)
        maybeOpenActiveGroup(savedInstanceState)

        groupDisplayNameInput.filters = arrayOf(android.text.InputFilter.LengthFilter(DISPLAY_NAME_MAX_LEN))

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
        navView.setCheckedItem(R.id.menu_home)
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_nearby_rooms -> {
                    startActivity(Intent(this, NearbyRoomsActivity::class.java))
                }
                R.id.menu_test -> {
                    startActivity(Intent(this, TestActivity::class.java))
                }
                R.id.menu_trails -> {
                    PeerMeshFeatureUi.openTrailOrPaywall(this)
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                R.id.menu_statistics -> {
                    startActivity(Intent(this, StatisticsActivity::class.java))
                }
                R.id.menu_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                }
            }
            drawer.closeDrawer(GravityCompat.END)
            true
        }

        groupCreateLink.setOnClickListener {
            startActivity(Intent(this, CreateGroupActivity::class.java))
        }

        val scanLauncher = registerForActivityResult(ScanContract()) { result ->
            val raw = result.contents
            if (raw.isNullOrBlank()) return@registerForActivityResult
            val parsed = parseJoinInput(raw)
            if (parsed != null) {
                applyParsedInvite(parsed, groupInviteInput)
                toast(getString(R.string.qr_invite_applied))
            } else {
                toast(getString(R.string.qr_invite_invalid))
            }
        }
        groupPasteHint.visibility = View.VISIBLE
        groupPasteLink.setOnClickListener {
            val options = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.qr_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(QrScanActivity::class.java)
            scanLauncher.launch(options)
        }

        groupJoin.setOnClickListener {
            val displayName = groupDisplayNameInput.text.toString()
            val inviteRaw = groupInviteInput.text.toString()
            if (displayName.isBlank()) {
                toast(getString(R.string.group_display_name_required))
                return@setOnClickListener
            }
            val parsed = parseJoinInput(inviteRaw)
            if (parsed == null) {
                toast(getString(R.string.group_join_need_invite))
                return@setOnClickListener
            }
            Thread {
                val result = joinGroup(parsed, displayName)
                runOnUiThread {
                    if (result.ok) {
                        groupInviteInput.setText("")
                        groupDisplayNameInput.setText("")
                        toast(getString(R.string.group_joined))
                        loadGroups(groupsAdapter, groupsEmpty)
                        val intent = Intent(this, GroupChatActivity::class.java).apply {
                            putExtra(GroupChatActivity.EXTRA_GID, parsed.gid)
                            putExtra(GroupChatActivity.EXTRA_GROUP_NAME, "Group ${parsed.gid.take(6)}")
                        }
                        startActivity(intent)
                    } else {
                        toast(result.error ?: "Join group failed")
                    }
                }
            }.start()
        }

        loadGroups(groupsAdapter, groupsEmpty)
        updateNotificationBanner(notificationsBanner, notificationsText, notificationsAction)
    }

    override fun onResume() {
        super.onResume()
        activeGid = ActiveGroupManager.getActiveGroup(this)
        if (PeerMeshPrefs.isBleMeshUserEnabled(this) &&
            !app.peermesh.ble.BleForegroundService.isMeshRunning() &&
            app.peermesh.ble.BleForegroundService.canStartMesh(this)
        ) {
            app.peermesh.ble.BleForegroundService.startMesh(
                this,
                caller = "MainActivity.onResume",
                reason = if (activeGid.isNullOrBlank()) "carrier_only_resume" else "active_group_resume",
            )
            app.peermesh.ble.BleMeshWorkManager.enqueue(this)
        }
        StatusIndicatorUpdater.start(this)
        CrashPromptController.maybeShowPrompt(this)
        BetaProgramUpdatePromptController.maybeShowPrompt(this)
        maybeStartRelaySync()
        loadGroups(
            (findViewById<RecyclerView>(R.id.groups_list)).adapter as GroupAdapter,
            findViewById(R.id.groups_empty)
        )
        updateNotificationBanner(
            findViewById(R.id.main_notifications_banner),
            findViewById(R.id.main_notifications_text),
            findViewById(R.id.main_notifications_action),
        )
    }

    override fun onPause() {
        super.onPause()
        CrashPromptController.dismissPrompt()
        StatusIndicatorUpdater.stop(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInviteIntent(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF_PERM) {
            updateNotificationBanner(
                findViewById(R.id.main_notifications_banner),
                findViewById(R.id.main_notifications_text),
                findViewById(R.id.main_notifications_action),
            )
        }
    }

    private fun updateNotificationBanner(container: View, body: TextView, action: Button) {
        val status = NotificationGate.status(this, app.peermesh.ble.BleForegroundService.CHANNEL_ID)
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
            val current = NotificationGate.status(this, app.peermesh.ble.BleForegroundService.CHANNEL_ID)
            if (current.needsPermission) {
                NotificationGate.requestPermission(this, REQ_NOTIF_PERM)
            } else if (current.channelDisabled) {
                NotificationGate.openChannelSettings(this, app.peermesh.ble.BleForegroundService.CHANNEL_ID)
            } else {
                NotificationGate.openAppNotificationSettings(this)
            }
        }
    }

    private fun loadGroups(adapter: GroupAdapter, emptyView: TextView) {
        val db = AppDatabaseProvider.get(this)
        val actorDid = DeviceIdProvider.get(this)
        Thread {
            val groups = db.groupDao().listGroupsForActor(actorDid)
            runOnUiThread {
                if (groups.isEmpty()) {
                    emptyView.text = getString(R.string.groups_empty)
                    emptyView.visibility = View.VISIBLE
                } else {
                    emptyView.visibility = View.GONE
                    adapter.submitList(groups, activeGid)
                }
            }
        }.start()
    }

    private fun joinGroup(parsed: JoinInput, displayName: String): JoinResult {
        return try {
            val db = AppDatabaseProvider.get(applicationContext)
            val actorDid = DeviceIdProvider.getActiveActor(applicationContext)
            val gid = parsed.gid
            if (GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)) {
                return JoinResult(false, getString(R.string.group_join_removed_blocked))
            }
            val now = System.currentTimeMillis() / 1000L
            val resolved = when {
                parsed.groupKey != null && !parsed.kid.isNullOrBlank() -> {
                    ResolvedJoinMaterial(
                        groupKey = parsed.groupKey,
                        kid = parsed.kid,
                        salt = null,
                        passphrase = null,
                    )
                }
                parsed.salt != null && parsed.passphrase != null -> {
                    val derivedGid = GidFactory.derive(parsed.passphrase.toCharArray(), parsed.salt)
                    if (!derivedGid.equals(gid, ignoreCase = false)) {
                        return JoinResult(false, "Invalid passphrase/salt for this gid")
                    }
                    val groupKey = GroupKeyDeriver.deriveGroupKey(parsed.passphrase.toCharArray(), parsed.salt)
                    ResolvedJoinMaterial(
                        groupKey = groupKey,
                        kid = GroupKeyIdFactory.fromKey(groupKey, parsed.salt),
                        salt = parsed.salt,
                        passphrase = parsed.passphrase,
                    )
                }
                else -> return JoinResult(false, getString(R.string.group_join_need_invite))
            }
            val group = GroupEntity(
                gid = gid,
                kid = resolved.kid,
                name = "Group ${gid.take(6)}",
                owner_did = parsed.ownerDid?.takeIf { it.isNotBlank() },
                context_profile = MapKinds.PROFILE_URBAN,
                created_at = now,
                updated_at = now,
            )
            db.runInTransaction {
                db.keyDao().upsert(
                    app.peermesh.data.db.entity.KeyEntity(
                        gid = gid,
                        kid = resolved.kid,
                        created_at = now,
                        updated_at = now,
                    )
                )
                db.groupDao().upsert(group)
            }
            val secureStore = SecureKeyStore(applicationContext)
            secureStore.storeGroupKey(gid, resolved.groupKey)
            if (resolved.salt != null && resolved.passphrase != null) {
                secureStore.storeSalt(gid, resolved.salt)
                secureStore.storePassphrase(gid, resolved.passphrase)
            } else {
                secureStore.clearJoinMaterial(gid)
            }
            secureStore.storeGroupKeyVersion(gid, resolved.kid, resolved.groupKey)
            SysEventWriter.updateUserProfile(applicationContext, gid, displayName, null)
            JoinResult(true, null)
        } catch (_: Exception) {
            JoinResult(false, null)
        }
    }

    private fun parseJoinInput(raw: String): JoinInput? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.contains("gid=") && trimmed.contains("kid=") && trimmed.contains("group_key=")) {
            val uri = Uri.parse(trimmed)
            val gid = uri.getQueryParameter("gid") ?: return null
            val kid = uri.getQueryParameter("kid")?.takeIf { it.isNotBlank() } ?: return null
            val groupKeyEncoded = uri.getQueryParameter("group_key") ?: return null
            val ownerDid = uri.getQueryParameter("owner_did")
            val groupKey = runCatching { Base64Url.decode(groupKeyEncoded) }.getOrNull() ?: return null
            JoinInput(
                gid = gid,
                salt = null,
                passphrase = null,
                kid = kid,
                groupKey = groupKey,
                ownerDid = ownerDid,
                raw = trimmed,
            )
        } else if (trimmed.contains("&") && trimmed.contains("gid=") && trimmed.contains("kid=") && trimmed.contains("group_key=")) {
            val uri = Uri.parse("peermesh://join?$trimmed")
            val gid = uri.getQueryParameter("gid") ?: return null
            val kid = uri.getQueryParameter("kid")?.takeIf { it.isNotBlank() } ?: return null
            val groupKeyEncoded = uri.getQueryParameter("group_key") ?: return null
            val ownerDid = uri.getQueryParameter("owner_did")
            val groupKey = runCatching { Base64Url.decode(groupKeyEncoded) }.getOrNull() ?: return null
            JoinInput(
                gid = gid,
                salt = null,
                passphrase = null,
                kid = kid,
                groupKey = groupKey,
                ownerDid = ownerDid,
                raw = "peermesh://join?$trimmed",
            )
        } else if (trimmed.contains("gid=") && trimmed.contains("salt=") && trimmed.contains("passphrase=")) {
            val uri = Uri.parse(trimmed)
            val gid = uri.getQueryParameter("gid") ?: return null
            val saltEncoded = uri.getQueryParameter("salt") ?: return null
            val passphrase = uri.getQueryParameter("passphrase") ?: return null
            val salt = runCatching { Base64Url.decode(saltEncoded) }.getOrNull() ?: return null
            val ownerDid = uri.getQueryParameter("owner_did")
            JoinInput(gid, salt, passphrase, null, null, ownerDid, trimmed)
        } else if (trimmed.contains("&") && trimmed.contains("gid=") && trimmed.contains("salt=") && trimmed.contains("passphrase=")) {
            val uri = Uri.parse("peermesh://join?$trimmed")
            val gid = uri.getQueryParameter("gid") ?: return null
            val saltEncoded = uri.getQueryParameter("salt") ?: return null
            val passphrase = uri.getQueryParameter("passphrase") ?: return null
            val ownerDid = uri.getQueryParameter("owner_did")
            val salt = runCatching { Base64Url.decode(saltEncoded) }.getOrNull() ?: return null
            JoinInput(gid, salt, passphrase, null, null, ownerDid, "peermesh://join?$trimmed")
        } else {
            null
        }
    }

    private fun applyParsedInvite(parsed: JoinInput, inviteInput: EditText) {
        inviteInput.setText(parsed.raw)
    }

    private fun handleInviteIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        val parsed = parseJoinInput(data.toString())
        if (parsed == null) {
            toast(getString(R.string.qr_invite_invalid))
            return
        }
        val inviteInput = joinInviteInput ?: return
        applyParsedInvite(parsed, inviteInput)
        toast(getString(R.string.qr_invite_applied))
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private data class JoinResult(val ok: Boolean, val error: String?)

    private data class JoinInput(
        val gid: String,
        val salt: ByteArray?,
        val passphrase: String?,
        val kid: String?,
        val groupKey: ByteArray?,
        val ownerDid: String?,
        val raw: String,
    )

    private data class ResolvedJoinMaterial(
        val groupKey: ByteArray,
        val kid: String,
        val salt: ByteArray?,
        val passphrase: String?,
    )

    companion object {
        private const val DISPLAY_NAME_MAX_LEN = 40
        private const val REQ_NOTIF_PERM = 1201
    }

    private fun maybeStartRelaySync() {
        if (!PeerMeshCapabilities.canUseRelaySync) return
        if (!app.peermesh.relay.RelaySyncSettings.isEnabled(this)) return
        if (app.peermesh.relay.RelaySyncSettings.load(this) != app.peermesh.relay.SyncMode.NORMAL) return
        if (!isOnline()) return
        app.peermesh.relay.RelaySyncLifecycleCoordinator.onUserRequestedImmediateSync(this)
    }

    private fun isOnline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
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

    private fun maybeOpenActiveGroup(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        val isLauncher = intent?.action == Intent.ACTION_MAIN &&
            intent?.categories?.contains(Intent.CATEGORY_LAUNCHER) == true
        if (!isLauncher) return
        val gid = ActiveGroupManager.getActiveGroup(this) ?: return
        val db = AppDatabaseProvider.get(this)
        Thread {
            val group = db.groupDao().findGroup(gid)
            runOnUiThread {
                if (group == null) {
                    ActiveGroupManager.clearActiveGroup(this)
                    return@runOnUiThread
                }
                val intent = Intent(this, GroupChatActivity::class.java).apply {
                    putExtra(GroupChatActivity.EXTRA_GID, group.gid)
                    putExtra(GroupChatActivity.EXTRA_GROUP_NAME, group.name)
                }
                startActivity(intent)
                finish()
            }
        }.start()
    }
}
