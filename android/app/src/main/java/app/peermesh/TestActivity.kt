package app.peermesh

import android.content.Intent
import android.provider.Settings
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import app.peermesh.transport.ble.BleConfig
import app.peermesh.transport.adapter.NearbyForegroundDiagnosticLabels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import app.peermesh.relay.RelaySyncRunner
import app.peermesh.relay.RelaySyncService
import app.peermesh.relay.RelaySyncSettings
import app.peermesh.relay.RelaySyncWorkManager
import app.peermesh.core.id.GidFactory
import app.peermesh.core.id.GroupKeyIdFactory
import app.peermesh.security.crypto.GroupKeyDeriver
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.db.entity.GroupEntity
import app.peermesh.security.crypto.SecureKeyStore
import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.model.EventMeta
import app.peermesh.core.types.EventTypes
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.id.EventIdFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.google.android.material.navigation.NavigationView
import app.peermesh.ble.BleForegroundService
import app.peermesh.debug.ActivityLogCaptureService
import com.google.android.material.button.MaterialButton
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.LinkedHashSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import app.peermesh.data.debug.ActivityLog
import app.peermesh.core.encoding.Base64Url
import app.peermesh.relay.RelaySyncLifecycleCoordinator
import app.peermesh.beta.PeerMeshBetaProgram
import java.security.SecureRandom
import org.json.JSONArray

class TestActivity : AppCompatActivity() {
    private data class ActivityLogLabelGroup(
        val id: String,
        val title: String,
        val labels: List<String>,
    )

    private val relayStressExecutor = Executors.newCachedThreadPool()
    private val activityLogHandler = Handler(Looper.getMainLooper())
    private var activityLogStopRunnable: Runnable? = null
    private var activityLogFlushRunnable: Runnable? = null
    private var activityLogFlushFile: File? = null
    private var activityLogLastFlushMs: Long = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        if (!BuildConfig.ENABLE_DEBUG_TOOLS) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        headerTitle.text = getString(R.string.page_title_test)

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
        navView.setCheckedItem(R.id.menu_test)
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                }
                R.id.menu_nearby_rooms -> {
                    startActivity(Intent(this, NearbyRoomsActivity::class.java))
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

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val help = findViewById<Button>(R.id.btn_help)
        val seedInput = findViewById<EditText>(R.id.seed_relays_input)
        val seedSave = findViewById<Button>(R.id.seed_relays_save)
        val seedList = findViewById<LinearLayout>(R.id.seed_relays_list)
        val seedApply = findViewById<Button>(R.id.seed_relays_apply)
        val relayTest = findViewById<Button>(R.id.relay_test_now)
        val relayDebug = findViewById<Button>(R.id.relay_debug_seed)
        val relayClear = findViewById<Button>(R.id.relay_clear_db)
        val crashTest = findViewById<Button>(R.id.crash_test_now)
        val altUsersTitle = findViewById<TextView>(R.id.test_alt_users_title)
        val altUsersCurrent = findViewById<TextView>(R.id.test_alt_users_current)
        val altUsersAdd = findViewById<Button>(R.id.test_alt_users_add)
        val altUsersList = findViewById<LinearLayout>(R.id.test_alt_users_list)
        val devMode = findViewById<CheckBox>(R.id.dev_mode_toggle)
        val proEntitlementDebug = findViewById<LinearLayout>(R.id.pro_entitlement_debug_container)
        val proEntitlementStatus = findViewById<TextView>(R.id.pro_entitlement_debug_status)
        val proEntitlementFree = findViewById<Button>(R.id.pro_entitlement_debug_free)
        val proEntitlementTrial = findViewById<Button>(R.id.pro_entitlement_debug_trial)
        val proEntitlementPro = findViewById<Button>(R.id.pro_entitlement_debug_pro)
        val proEntitlementExpired = findViewById<Button>(R.id.pro_entitlement_debug_expired)
        val proEntitlementUnknown = findViewById<Button>(R.id.pro_entitlement_debug_unknown)
        val proEntitlementClear = findViewById<Button>(R.id.pro_entitlement_debug_clear)
        val proEntitlementSyncBeta = findViewById<Button>(R.id.pro_entitlement_debug_sync_beta)
        val activityLogFiles = findViewById<LinearLayout>(R.id.activity_log_files)
        val activityLogStart = findViewById<Button>(R.id.activity_log_start)
        val activityLogPrefix = findViewById<EditText>(R.id.activity_log_prefix)
        val activityLogDuration = findViewById<EditText>(R.id.activity_log_duration)
        val activityLogLabelGroups = findViewById<LinearLayout>(R.id.activity_log_label_groups)
        val activityLogPatterns = findViewById<EditText>(R.id.activity_log_patterns)
        val activityLogAllowedLabels = findViewById<EditText>(R.id.activity_log_allowed_labels)
        val activityLogAfterReboot = findViewById<CheckBox>(R.id.activity_log_start_after_reboot)
        val bleScanNoFilter = findViewById<CheckBox>(R.id.ble_scan_no_filter)
        val bleAdvertiseSingle = findViewById<CheckBox>(R.id.ble_advertise_single_chunk)
        val bleRestart = findViewById<Button>(R.id.ble_restart_mesh)
        val bleCorruptNextChunk = findViewById<Button>(R.id.ble_corrupt_next_chunk)

        loadActivityLogPrefs(
            prefs = prefs,
            prefixView = activityLogPrefix,
            durationView = activityLogDuration,
            patternsView = activityLogPatterns,
            labelsView = activityLogAllowedLabels,
            autoAfterRebootView = activityLogAfterReboot,
        )
        setupActivityLogLabelGroups(
            container = activityLogLabelGroups,
            labelsView = activityLogAllowedLabels,
        )
        activityLogAfterReboot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_ACTIVITY_LOG_AUTO_AFTER_REBOOT, isChecked).apply()
        }
        activityLogStart.setOnClickListener {
            val durationMs = parseDurationMs(activityLogDuration.text?.toString().orEmpty())
            if (durationMs == null || durationMs <= 0L) {
                Toast.makeText(this, "Invalid duration", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val patterns = parsePatterns(activityLogPatterns.text?.toString().orEmpty())
            val labels = ensureDiscoveryLabels(
                parsePatterns(activityLogAllowedLabels.text?.toString().orEmpty())
            )
            saveActivityLogPrefs(
                prefixView = activityLogPrefix,
                durationView = activityLogDuration,
                patternsView = activityLogPatterns,
                labelsView = activityLogAllowedLabels,
                autoAfterRebootView = activityLogAfterReboot,
            )
            ActivityLogCaptureService.startCapture(
                context = this,
                durationMs = durationMs,
                patterns = patterns,
                labels = labels,
                filePrefix = activityLogPrefix.text?.toString().orEmpty(),
            )
            activityLogFiles.visibility = View.GONE
            Toast.makeText(this, "Activity log capture started", Toast.LENGTH_SHORT).show()
        }

        help.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        devMode.isChecked = RelaySyncSettings.isDeveloperMode(this)
        devMode.setOnCheckedChangeListener { _, isChecked ->
            RelaySyncSettings.setDeveloperMode(this, isChecked)
            val message = if (isChecked) {
                "Developer Mode enabled (HTTP allowed)"
            } else {
                "Developer Mode disabled (HTTPS only)"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        configureProEntitlementDebug(
            container = proEntitlementDebug,
            status = proEntitlementStatus,
            free = proEntitlementFree,
            trial = proEntitlementTrial,
            pro = proEntitlementPro,
            expired = proEntitlementExpired,
            unknown = proEntitlementUnknown,
            clear = proEntitlementClear,
            syncBeta = proEntitlementSyncBeta,
        )
        val scanNoFilterEnabled = prefs.getBoolean(KEY_BLE_SCAN_NO_FILTER, false)
        bleScanNoFilter.isChecked = scanNoFilterEnabled
        BleConfig.scanFilterEnabled = !scanNoFilterEnabled
        bleScanNoFilter.setOnCheckedChangeListener { _, isChecked ->
            BleConfig.scanFilterEnabled = !isChecked
            prefs.edit().putBoolean(KEY_BLE_SCAN_NO_FILTER, isChecked).apply()
        }
        val advertiseSingleEnabled = prefs.getBoolean(KEY_BLE_AD_SINGLE, false)
        bleAdvertiseSingle.isChecked = advertiseSingleEnabled
        BleConfig.advertiseSingleChunk = advertiseSingleEnabled
        bleAdvertiseSingle.setOnCheckedChangeListener { _, isChecked ->
            BleConfig.advertiseSingleChunk = isChecked
            prefs.edit().putBoolean(KEY_BLE_AD_SINGLE, isChecked).apply()
        }
        BleConfig.useManufacturerData = true
        bleRestart.setOnClickListener {
            BleForegroundService.stopMesh(
                this,
                caller = "TestActivity.bleRestart",
                reason = "manual_restart",
            )
            Handler(Looper.getMainLooper()).postDelayed({
                BleForegroundService.startMesh(
                    this,
                    caller = "TestActivity.bleRestart",
                    reason = "manual_restart",
                )
            }, 500)
        }
        bleCorruptNextChunk.setOnClickListener {
            BleConfig.debugCorruptNextChunk = true
            Toast.makeText(this, "Next BLE chunk will be corrupted", Toast.LENGTH_SHORT).show()
        }

        seedInput.setText(RelaySyncSettings.loadSeedRelaysRaw(this))
        seedSave.setOnClickListener {
            RelaySyncSettings.saveSeedRelaysRaw(this, seedInput.text.toString())
            ensureDefaultSelection()
            renderSeedList(seedList, seedInput)
            warnIfHttpBlocked()
            if (RelaySyncSettings.isEnabled(this)) {
                RelaySyncLifecycleCoordinator.onUserRequestedImmediateSync(this)
            }
        }

        seedApply.setOnClickListener {
            val selected = collectSelected(seedList)
            RelaySyncSettings.saveSelectedRelays(this, selected)
            warnIfHttpBlocked()
            if (RelaySyncSettings.isEnabled(this)) {
                RelaySyncLifecycleCoordinator.onUserRequestedImmediateSync(this)
            }
        }

        relayTest.setOnClickListener {
            Thread {
                val report = RelaySyncRunner(applicationContext).runOnce()
                runOnUiThread {
                    val rejectedNote = report.rejectedSummary?.let { " (rejected: $it)" } ?: ""
                    val note = report.note?.let { " | $it" } ?: ""
                    val message = if (report.success) {
                        "Relay test: success (push accepted ${report.pushAccepted}/${report.pushAttempted}, pull accepted ${report.pullAccepted}/${report.pullReceived})$rejectedNote$note"
                    } else {
                        "Relay test: failed (${report.errorSummary ?: "unknown"})$rejectedNote$note"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        relayTest.setOnLongClickListener {
            runRelayStressTest()
            true
        }

        relayDebug.setOnClickListener {
            Thread {
                val ok = createDebugSeed()
                runOnUiThread {
                    val message = if (ok) {
                        "Debug seed created"
                    } else {
                        "Debug seed failed"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        relayClear.setOnClickListener {
            Thread {
                val ok = clearLocalDatabase()
                runOnUiThread {
                    val message = if (ok) {
                        "Local DB cleared"
                    } else {
                        "Clear DB failed"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        crashTest.setOnClickListener {
            throw RuntimeException("Test crash")
        }

        altUsersTitle.text = getString(R.string.test_alt_users_title)
        refreshAltUsers(altUsersCurrent, altUsersList)
        altUsersAdd.setOnClickListener {
            val id = generateAltUserId()
            addAltUser(id)
            refreshAltUsers(altUsersCurrent, altUsersList)
        }

        ensureDefaultSelection()
        renderSeedList(seedList, seedInput)
    }

    override fun onStart() {
        super.onStart()
        if (!RelaySyncSettings.isEnabled(this)) {
            RelaySyncLifecycleCoordinator.onUserDisabledRelay(this)
        } else {
            RelaySyncLifecycleCoordinator.onUserRequestedImmediateSync(this)
        }
    }

    override fun onResume() {
        super.onResume()
        StatusIndicatorUpdater.start(this)
        renderActivityLogFiles(findViewById(R.id.activity_log_files))
    }

    override fun onPause() {
        super.onPause()
        StatusIndicatorUpdater.stop(this)
        saveActivityLogPrefs(
            prefixView = findViewById(R.id.activity_log_prefix),
            durationView = findViewById(R.id.activity_log_duration),
            patternsView = findViewById(R.id.activity_log_patterns),
            labelsView = findViewById(R.id.activity_log_allowed_labels),
            autoAfterRebootView = findViewById(R.id.activity_log_start_after_reboot),
        )
        stopActivityLogFlush(flushFinal = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        activityLogStopRunnable?.let { activityLogHandler.removeCallbacks(it) }
        activityLogStopRunnable = null
        stopActivityLogFlush(flushFinal = false)
    }

    private fun startActivityLogFlush() {
        activityLogFlushRunnable?.let { activityLogHandler.removeCallbacks(it) }
        activityLogFlushFile = null
        activityLogLastFlushMs = 0L
        val r = object : Runnable {
            override fun run() {
                flushActivityLog()
                activityLogHandler.postDelayed(this, 15_000L)
            }
        }
        activityLogFlushRunnable = r
        activityLogHandler.postDelayed(r, 15_000L)
    }

    private fun stopActivityLogFlush(flushFinal: Boolean) {
        activityLogFlushRunnable?.let { activityLogHandler.removeCallbacks(it) }
        activityLogFlushRunnable = null
        if (flushFinal) {
            flushActivityLog()
        }
    }

    private fun flushActivityLog() {
        if (!ActivityLog.isCapturing()) return
        val snapshot = ActivityLog.snapshot()
        val fresh = snapshot.filter { it.timeMs > activityLogLastFlushMs }
        if (fresh.isEmpty()) return
        val file = activityLogFlushFile ?: run {
            val logsDir = activityLogDir()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val newFile = File(logsDir, "activity_log_live_$stamp.txt")
            val header = "Captured at: $stamp\n\n"
            runCatching { newFile.writeText(header) }
            activityLogFlushFile = newFile
            newFile
        }
        val content = ActivityLog.format(fresh) + "\n"
        runCatching { file.appendText(content) }
        activityLogLastFlushMs = fresh.maxOf { it.timeMs }
    }

    private fun runRelayStressTest() {
        val threadCount = RELAY_STRESS_THREADS
        val rounds = RELAY_STRESS_ROUNDS
        val totalCalls = threadCount * rounds
        val coalesced = AtomicInteger(0)
        val success = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val latch = CountDownLatch(totalCalls)
        val startedAt = System.currentTimeMillis()
        repeat(rounds) { round ->
            repeat(threadCount) {
                relayStressExecutor.execute {
                    try {
                        val report = RelaySyncRunner(applicationContext).runOnce()
                        if (report.errorSummary == "sync_in_progress") {
                            coalesced.incrementAndGet()
                        } else if (report.success) {
                            success.incrementAndGet()
                        } else {
                            failures.incrementAndGet()
                        }
                    } catch (_: Throwable) {
                        failures.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }
            }
            Log.i(TAG, "Relay stress: dispatched round ${round + 1}/$rounds ($threadCount calls)")
            try {
                Thread.sleep(RELAY_STRESS_ROUND_DELAY_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
        relayStressExecutor.execute {
            try {
                latch.await(RELAY_STRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // ignore
            }
            val elapsed = System.currentTimeMillis() - startedAt
            val message = "Relay stress done: calls=$totalCalls, coalesced=${coalesced.get()}, " +
                "success=${success.get()}, failed=${failures.get()}, elapsedMs=$elapsed"
            Log.i(TAG, message)
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseDurationMs(raw: String): Long? {
        val value = raw.trim().lowercase(Locale.US)
        if (value.isEmpty()) return null
        return when {
            value.endsWith("ms") -> value.removeSuffix("ms").trim().toLongOrNull()
            value.endsWith("s") -> value.removeSuffix("s").trim().toLongOrNull()?.times(1000L)
            else -> value.toLongOrNull()?.times(1000L)
        }
    }

    private fun parsePatterns(raw: String): List<String> {
        return raw.split("\n").mapNotNull { it.trim().ifEmpty { null } }
    }

    private fun ensureDiscoveryLabels(labels: List<String>): List<String> {
        return (labels + DISCOVERY_MARKER_LABELS + NearbyForegroundDiagnosticLabels.ALL).distinct()
    }

    private fun setupActivityLogLabelGroups(
        container: LinearLayout,
        labelsView: EditText,
    ) {
        container.removeAllViews()
        val checkboxesByGroupId = LinkedHashMap<String, CheckBox>()
        val currentLabels = parsePatterns(labelsView.text?.toString().orEmpty())
        val selectedGroupIds = inferSelectedActivityLogGroupIds(currentLabels)
        ACTIVITY_LOG_LABEL_GROUPS.forEach { group ->
            val checkbox = CheckBox(this).apply {
                text = "${group.title} (${group.labels.size})"
                isChecked = group.id in selectedGroupIds
                setTextColor(ContextCompat.getColor(this@TestActivity, R.color.peer_mesh_text_primary))
            }
            checkbox.setOnCheckedChangeListener { _, _ ->
                syncAllowedLabelsFromGroups(
                    labelsView = labelsView,
                    checkboxesByGroupId = checkboxesByGroupId,
                )
            }
            checkboxesByGroupId[group.id] = checkbox
            container.addView(checkbox)
        }
    }

    private fun inferSelectedActivityLogGroupIds(labels: List<String>): Set<String> {
        val labelSet = labels.toSet()
        return ACTIVITY_LOG_LABEL_GROUPS
            .filter { group -> group.labels.isNotEmpty() && group.labels.all { it in labelSet } }
            .mapTo(LinkedHashSet()) { it.id }
    }

    private fun syncAllowedLabelsFromGroups(
        labelsView: EditText,
        checkboxesByGroupId: Map<String, CheckBox>,
    ) {
        val currentLabels = parsePatterns(labelsView.text?.toString().orEmpty())
        val allGroupedLabels = ACTIVITY_LOG_LABEL_GROUPS
            .flatMap { it.labels }
            .distinct()
            .toSet()
        val selectedGroupedLabels = ACTIVITY_LOG_LABEL_GROUPS
            .filter { group -> checkboxesByGroupId[group.id]?.isChecked == true }
            .flatMap { it.labels }
            .distinct()
        val customLabels = currentLabels.filter { it !in allGroupedLabels }
        val mergedLabels = (selectedGroupedLabels + customLabels).distinct()
        labelsView.setText(mergedLabels.joinToString(separator = "\n"))
        labelsView.setSelection(labelsView.text?.length ?: 0)
    }

    private fun loadActivityLogPrefs(
        prefs: android.content.SharedPreferences,
        prefixView: EditText,
        durationView: EditText,
        patternsView: EditText,
        labelsView: EditText,
        autoAfterRebootView: CheckBox,
    ) {
        prefixView.setText(prefs.getString(KEY_ACTIVITY_LOG_PREFIX, "").orEmpty())
        durationView.setText(prefs.getString(KEY_ACTIVITY_LOG_DURATION, "").orEmpty())
        patternsView.setText(prefs.getString(KEY_ACTIVITY_LOG_PATTERNS, "").orEmpty())
        val savedLabels = prefs.getString(KEY_ACTIVITY_LOG_ALLOWED_LABELS, "").orEmpty()
        labelsView.setText(savedLabels.ifBlank { DEFAULT_ACTIVITY_LOG_ALLOWED_LABELS_TEXT })
        autoAfterRebootView.isChecked = prefs.getBoolean(KEY_ACTIVITY_LOG_AUTO_AFTER_REBOOT, false)
    }

    private fun saveActivityLogPrefs(
        prefixView: EditText,
        durationView: EditText,
        patternsView: EditText,
        labelsView: EditText,
        autoAfterRebootView: CheckBox,
    ) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_ACTIVITY_LOG_PREFIX, prefixView.text?.toString().orEmpty())
            .putString(KEY_ACTIVITY_LOG_DURATION, durationView.text?.toString().orEmpty())
            .putString(KEY_ACTIVITY_LOG_PATTERNS, patternsView.text?.toString().orEmpty())
            .putString(KEY_ACTIVITY_LOG_ALLOWED_LABELS, labelsView.text?.toString().orEmpty())
            .putBoolean(KEY_ACTIVITY_LOG_AUTO_AFTER_REBOOT, autoAfterRebootView.isChecked)
            .apply()
    }

    private fun exportActivityLog(result: ActivityLog.CaptureResult): File? {
        val logsDir = activityLogDir()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(logsDir, "activity_log_$stamp.txt")
        val header = buildString {
            append("Captured at: ").append(stamp).append('\n')
            if (result.patternCounts.isNotEmpty()) {
                append("Pattern counts:\n")
                result.patternCounts.forEach { (pattern, count) ->
                    append(" - ").append(pattern).append(" x ").append(count).append('\n')
                }
                append('\n')
            }
        }
        val content = header + ActivityLog.format(result.entries)
        return runCatching {
            file.writeText(content)
            file
        }.getOrNull()
    }

    private fun activityLogDir(): File {
        val dir = getExternalFilesDir(null) ?: filesDir
        val logsDir = File(dir, "activity_logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        return logsDir
    }

    private fun renderActivityLogFiles(container: LinearLayout) {
        val logsDir = activityLogDir()
        val files = logsDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        container.removeAllViews()
        if (files.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = getString(R.string.activity_log_no_files)
                setTextColor(ContextCompat.getColor(this@TestActivity, R.color.peer_mesh_text_secondary))
            }
            container.addView(emptyView)
            container.visibility = View.VISIBLE
            return
        }
        files.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }
            val nameView = TextView(this).apply {
                text = file.name
                setTextColor(ContextCompat.getColor(this@TestActivity, R.color.peer_mesh_text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val deleteButton = createSecondaryButton().apply {
                text = getString(R.string.activity_log_delete)
                setOnClickListener {
                    file.delete()
                    renderActivityLogFiles(container)
                }
            }
            val shareButton = createSecondaryButton().apply {
                text = getString(R.string.activity_log_share)
                setOnClickListener {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this@TestActivity,
                        "${packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = when {
                            file.name.endsWith(".json", ignoreCase = true) -> "application/json"
                            else -> "text/plain"
                        }
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.activity_log_share)))
                }
            }
            row.addView(nameView)
            row.addView(deleteButton)
            row.addView(shareButton)
            container.addView(row)
        }
        container.visibility = View.VISIBLE
    }

    private fun refreshAltUsers(currentView: TextView, listView: LinearLayout) {
        val current = DeviceIdProvider.getActiveActor(this)
        val main = DeviceIdProvider.getMainActor(this)
        val label = if (main != null && main == current) {
            "$current (main)"
        } else {
            current
        }
        currentView.text = getString(R.string.test_alt_users_current_format, label)
        listView.removeAllViews()
        val users = buildList {
            if (!main.isNullOrBlank()) {
                add(main)
            }
            if (!current.isNullOrBlank() && current != main) {
                add(current)
            }
            loadAltUsers().forEach { user ->
                if (user != main && user != current) {
                    add(user)
                }
            }
        }
        users.forEach { user ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val label = TextView(this).apply {
                text = if (main != null && user == main) {
                    "$user (main)"
                } else {
                    user
                }
                setTextColor(ContextCompat.getColor(this@TestActivity, R.color.peer_mesh_text_secondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val switchBtn = createSecondaryButton().apply {
                text = getString(R.string.test_alt_users_switch)
                setOnClickListener {
                    switchToAltUser(user)
                }
            }
            val deleteBtn = createSecondaryButton().apply {
                text = getString(R.string.test_alt_users_delete)
                setOnClickListener {
                    if (user == current) {
                        Toast.makeText(
                            this@TestActivity,
                            getString(R.string.test_alt_users_cannot_delete_active),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    removeAltUser(user)
                    refreshAltUsers(currentView, listView)
                }
            }
            row.addView(label)
            if (user != current) {
                row.addView(switchBtn)
                if (main == null || user != main) {
                    row.addView(deleteBtn)
                }
            }
            listView.addView(row)
        }
    }

    private fun createSecondaryButton(): MaterialButton {
        return MaterialButton(ContextThemeWrapper(this, R.style.PeerMeshButtonSecondary), null)
    }

    private fun loadAltUsers(): List<String> {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val raw = prefs.getString(KEY_ALT_USERS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (!v.isNullOrBlank()) list.add(v)
        }
        return list
    }

    private fun saveAltUsers(users: List<String>) {
        val arr = JSONArray()
        users.distinct().forEach { arr.put(it) }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_ALT_USERS, arr.toString())
            .apply()
    }

    private fun addAltUser(id: String) {
        val users = loadAltUsers().toMutableList()
        users.add(id)
        saveAltUsers(users)
    }

    private fun removeAltUser(id: String) {
        val users = loadAltUsers().filterNot { it == id }
        saveAltUsers(users)
    }

    private fun generateAltUserId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64Url.encode(bytes)
    }

    private fun switchToAltUser(id: String) {
        DeviceIdProvider.setActiveActor(this, id)
        ActiveGroupManager.clearActiveGroup(this, id)
        app.peermesh.ble.BleMeshRuntime.resetForActorSwitch(this)
        Toast.makeText(
            this,
            getString(R.string.test_alt_users_switched),
            Toast.LENGTH_SHORT
        ).show()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    companion object {
        private const val TAG = "TestActivity"
        private const val PREFS_NAME = PeerMeshPrefs.NAME
        private const val KEY_ALT_USERS = "test_alt_users"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_BLE_SCAN_NO_FILTER = "ble_scan_no_filter"
        private const val KEY_BLE_AD_SINGLE = "ble_ad_single"
        private const val KEY_ACTIVITY_LOG_PREFIX = "activity_log_prefix"
        private const val KEY_ACTIVITY_LOG_DURATION = "activity_log_duration"
        private const val KEY_ACTIVITY_LOG_PATTERNS = "activity_log_patterns"
        private const val KEY_ACTIVITY_LOG_ALLOWED_LABELS = "activity_log_allowed_labels"
        private const val KEY_ACTIVITY_LOG_AUTO_AFTER_REBOOT = "activity_log_auto_after_reboot"
        private const val RELAY_STRESS_THREADS = 8
        private const val RELAY_STRESS_ROUNDS = 5
        private const val RELAY_STRESS_ROUND_DELAY_MS = 150L
        private const val RELAY_STRESS_TIMEOUT_MS = 60_000L
        /**
         * End-to-end map activity labels. Some ingestion labels are shared with
         * other event domains, but are included because they are needed to trace
         * a map event from receipt through durable recovery.
         */
        private val MAP_ACTIVITY_LOG_LABELS = listOf(
            "Local map event committed",
            "Map event blocked",
            "Local map write outcome",
            "Map transport enqueue failed",
            "APP MAP batch window armed",
            "Map event deferred",
            "Map event semantic rejection",
            "Map event invalid persisted state",
            "Map event persistence failure",
            "Map canonical repair requested",
            "Map canonical repair scheduled",
            "Map canonical repair scheduling skipped",
            "Map canonical repair pull started",
            "Map canonical repair pull finished",
            "Relay map dependency repair",
            "DB event insert",
            "DB event duplicate drop",
            "DB event conflicting duplicate",
            "Ingest notify",
            "Event transport outcome",
        )
        private val ACTIVITY_LOG_ALL_LABELS = (listOf(
            "BLE traffic throttle",
            "BLE traffic coalesce",
            "BLE traffic drop",
            "BLE traffic enqueue",
            "BLE traffic send",
            "BLE traffic queue",
            "BLE connect cap blocked",
            "BLE connect cap snapshot",
            "BLE mesh mode",
            "BLE mfg match",
            "BLE mfg skip",
            "BLE target selected",
            "BLE target pinned",
            "BLE tx target mismatch",
            "BLE rx target mismatch",
            "BLE usage context",
            "BLE candidate grace used",
            "BLE backpressure metrics",
            "BLE backpressure level",
            "BLE scan settings update",
            "BLE scan start",
            "BLE scan start skipped",
            "BLE scan callback",
            "BLE scan auto-recover",
            "BLE advertise started",
            "BLE advertise retry",
            "BLE advertise failed",
            "BLE target select empty",
            "BLE relay compensate queued",
            "BLE relay compensate skipped",
            "BLE batch forward decision",
            "BLE send progress",
            "BLE send complete",
            "BLE send failed",
            "BLE throughput snapshot",
            "BLE scheduler probe",
            "Private invitation submit",
            "Private invitation preparation",
            "Local map event committed",
            "Map event deferred",
            "Map canonical repair requested",
            "Map canonical repair scheduled",
            "Map canonical repair scheduling skipped",
            "Map canonical repair pull started",
            "Map canonical repair pull finished",
            "Relay map dependency repair",
            "Queued BLE targets",
            "Nearby room metadata fetch started",
            "Nearby room metadata fetch finished",
            "Nearby room metadata fetch send",
            "Nearby room metadata fetch skipped",
            "Nearby room metadata fetch timeout",
            "Nearby room metadata fetch queued",
            "Nearby room metadata fetch resolved",
            "Nearby room metadata refresh skipped",
            "Nearby room advertise payload",
            "Nearby room advertisement refresh rejected",
            "Nearby room discovery payload",
            "Nearby room self suppressed",
            "Nearby changed",
            "Nearby rooms discovery burst",
            "Nearby discovery metrics",
            "Nearby sender suppression metrics",
            "Adaptive duty",
            "APP batch emit",
            "APP batch extract",
            "APP batch bypass",
            "APP batch window armed",
            "APP batch flush start",
            "APP batch transport queued",
            "APP batch transport skipped",
            "APP batch target pending",
            "APP batch dispatch deferred",
            "APP batch dispatch released",
            "APP dispatch job claimed",
            "APP dispatch job completed",
            "APP dispatch job failed",
            "ACK batch dispatch claimed",
            "ACK batch transport queued",
            "ACK batch dispatch completed",
            "ACK batch dispatch failed",
            "ACK batch reconcile",
            "BLE advertised device_id",
            "BLE mesh startup stalled",
            "BLE mesh transport stalled",
            "BLE batch forward queued",
            "BLE batch forward skipped",
            "BLE batch forward delivered",
            "BLE batch forward failed",
            "BLE batch send progress",
            "BLE batch send complete",
            "BLE batch send failed",
            "BLE batch in-progress stuck watchdog",
            "BLE batch in-progress stuck snapshot",
            "BLE batch state transition",
            "BLE notification status",
            "BLE peer fairness",
            "BLE service lifecycle",
            "BLE service start command",
            "BLE service evaluate state",
            "BLE service evaluate decision",
            "BLE service stop mesh",
            "BLE service start request",
            "BLE bluetooth state",
            "BLE location state",
            "BLE relay decision",
            "BLE delayed relay scheduled",
            "BLE delayed relay deferred",
            "BLE delayed relay fired",
            "BLE delayed relay dropped",
            "Map event deferred",
            "Map canonical repair requested",
            "Map canonical repair scheduled",
            "Map canonical repair scheduling skipped",
            "Map canonical repair pull started",
            "Map canonical repair pull finished",
            "Relay map dependency repair",
            "Received message at",
            "Rendered message at",
            "Local map event committed",
            "Mesh transport start",
            "Sent message at",
            "MTU negotiation",
            "GATT addService failed",
            "GATT cleanup scheduled",
            "GATT address cooldown set",
            "GATT connect backoff",
            "GATT connect failed",
            "GATT connect skipped",
            "GATT connect timeout",
            "GATT connected",
            "GATT disconnect reason",
            "GATT hard reset",
            "GATT write failed",
            "GATT write rejected",
            "GATT write skipped",
            "GATT recv chunk",
            "GATT reassembly",
            "GATT reassembled",
            "GATT deliver envelope",
            "GATT server start failed",
            "GATT server started",
            "GATT services discovered",
            "GATT stalled",
            "DISPATCH pull attempt",
            "DISPATCH pull terminal state",
            "DISPATCH job pulled",
            "DISPATCH job terminal",
            "DISPATCH job pull rejected",
            "DISPATCH job pull skipped",
            "mesh chunk geometry update",
            "CANDIDATE_POOL_SNAPSHOT",
            "TARGET_SELECTION_DECISION",
            "BLE target fallback preferred",
            "DISPATCH_NO_JOB_STREAK",
            "BROADCAST_REJECT_DECISION",
            "NEARBY_COUNT_BREAKDOWN",
            "PIPELINE connect backoff gate",
            "PIPELINE drain idle",
            "PIPELINE enqueue drop all",
            "PIPELINE label enqueue",
            "PIPELINE label execute",
            "PIPELINE label requeue",
            "PIPELINE labels changed",
            "PIPELINE staged to pending",
            "mesh active packet released",
            "mesh stale packet dropped",
            "DTN control envelope detected",
            "DTN control handle result",
            "DTN control queue decision",
            "DTN control queue state",
            "DTN control wire start",
            "DTN control wire complete",
            "DTN control wire failed",
            "DTN hello sent",
            "DTN hello recv",
            "DTN hello drop",
            "DTN hello send_failed",
            "DTN inv_have sent",
            "DTN inv_have recv",
            "DTN onHave enter",
            "DTN onHave compare start",
            "DTN onHave compare complete",
            "DTN onHave compare failed",
            "DTN onHave outcome",
            "DTN control handle failed",
            "DTN inv_have send_failed",
            "DTN inv_want sent",
            "DTN inv_want recv",
            "DTN inv_want send_failed",
            "DTN dependency want queued",
            "DTN dependency want send_failed",
            "DTN dependency want recv",
            "DTN dependency data served",
            "DTN dependency data recv",
            "DTN data sent",
            "DTN data recv",
            "DTN data send_failed",
            "DTN ack sent",
            "DTN ack recv",
            "DTN ack send_failed",
            "DTN sync summary",
            "DTN manifest_key sent",
            "DTN manifest_key recv",
            "DTN manifest_key send_failed",
            "DTN manifest_offer sent",
            "DTN manifest_offer recv",
            "DTN manifest_offer send_failed",
            "DTN manifest_accept sent",
            "DTN manifest_accept recv",
            "DTN manifest_accept send_failed",
            "DTN carrier_data sent",
            "DTN carrier_data recv",
            "DTN carrier_data send_failed",
            "DTN opaque retry store",
            "DTN opaque retry queued",
            "DTN opaque retry skipped",
            "DTN opaque retry bookkeeping_probe",
            "DTN opaque retry mark_forwarded",
            "DTN opaque retry pre_mark_probe",
            "DTN opaque retry pre_mark_result",
            "DTN opaque retry queued_state",
            "DTN opaque retry send_complete",
            "DTN opaque retry delete",
            "DTN opaque retry retry_failed",
            "ACK applied delivered",
            "ACK applied seen",
            "App foreground",
            "App background",
            "Nearby room advertise provider",
            "Nearby room advertisement state",
            "Nearby room advertisement refresh skipped",
            "Nearby room advertisement refresh deferred",
            "Nearby room key request send",
            "Nearby room key request target select",
            "Nearby room key request wait start",
            "Nearby room key request wait skip",
            "Nearby room key request wait timeout",
            "Nearby room key request wait cleared",
            "Nearby room key request recv",
            "Nearby room key reply ready",
            "Nearby room key reply skipped",
            "Nearby room key bootstrap send",
            "Nearby room key bootstrap recv",
            "Nearby room key bootstrap stored",
            "Nearby room key bootstrap store skipped",
            "Nearby room key bootstrap forwarded",
            "Nearby room key bootstrap forward skipped",
            "Nearby room key bootstrap dropped",
            "Nearby room key bootstrap receive skipped",
            "Nearby room key bootstrap bounce skipped",
            "Nearby room key access ready",
            "Nearby room join retry stopped",
            "Nearby room metadata pending after key ready",
            "Nearby room post-join metadata refresh trigger",
            "Nearby room pending join intent",
            "Nearby room auto-open pending join",
            "Nearby room key request needed",
            "Nearby room transport skipped",
            "Nearby room message decrypt failed",
            "Nearby room message send",
            "Nearby room message recv",
            "Nearby room message applied",
            "Nearby room message skipped",
            "Nearby room edit applied",
            "Nearby room edit skipped",
            "Nearby room delete applied",
            "Nearby room delete skipped",
            "Nearby room message data send",
            "Nearby room message data recv",
            "Nearby room discovery metadata recv",
            "Nearby room discovery metadata skipped",
            "Nearby room discovery metadata decode failed",
            "Nearby room discovery metadata decoded",
            "Nearby room discovery metadata upserted",
            "Nearby room discovery metadata ignored",
            "Nearby room discovery burst",
            "Nearby room join ready",
            "Nearby room join success",
            "Nearby room activity",
            "Nearby room participant",
            "Nearby room member",
            "Nearby room session",
            "Nearby room closed relay skipped",
            "Nearby rooms refresh trigger",
            "Nearby rooms refresh gesture",
            "Nearby rooms refresh skipped",
            "Nearby rooms refresh db start",
            "Nearby rooms refresh db done",
            "JOIN_READY",
            "NEARBY_ROOM_KEY_REQUEST_V1",
            "NEARBY_ROOM_KEY_BOOTSTRAP_V1",
            "NEARBY_ROOM_MESSAGE_V2",
            "backpressure_active",
            "inactive_room",
            "relay_ineligible",
            "Member removal requested",
            "Member removal queued",
            "Member removal applied",
            "Removed member event rejected",
            "Removed member outbound blocked",
            "Group chat participation state",
            "Group rekey",
            "Group key share",
            "Group key activated",
            "SYS ACK blocked removed member",
        ) + MAP_ACTIVITY_LOG_LABELS + NearbyForegroundDiagnosticLabels.ALL).distinct()
        private val ACTIVITY_LOG_LABEL_GROUPS = listOf(
            ActivityLogLabelGroup(
                id = "map_sync_recovery",
                title = "Map Events, Sync & Recovery",
                labels = MAP_ACTIVITY_LOG_LABELS,
            ),
            ActivityLogLabelGroup(
                id = "ble_traffic",
                title = "BLE Traffic",
                labels = ACTIVITY_LOG_ALL_LABELS.filter { it.startsWith("BLE traffic ") },
            ),
            ActivityLogLabelGroup(
                id = "ble_runtime",
                title = "BLE Runtime",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                    it.startsWith("BLE scan ") ||
                        it.startsWith("BLE advertise ") ||
                        it.startsWith("BLE mesh ") ||
                        it.startsWith("BLE service ") ||
                        it == "BLE bluetooth state" ||
                        it == "BLE location state" ||
                        it == "Adaptive duty" ||
                        it == "BLE usage context" ||
                        it == "BLE advertised device_id" ||
                        it == "BLE notification status" ||
                        it == "BLE throughput snapshot" ||
                        it == "App foreground" ||
                        it == "App background"
                },
            ),
            ActivityLogLabelGroup(
                id = "ble_scheduler",
                title = "BLE Scheduler & Targeting",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                    it.startsWith("BLE target ") ||
                        it.startsWith("BLE tx target ") ||
                        it.startsWith("BLE rx target ") ||
                        it.startsWith("BLE candidate ") ||
                        it.startsWith("BLE backpressure ") ||
                        it.startsWith("BLE connect cap ") ||
                        it == "BLE peer fairness" ||
                        it == "BLE mfg match" ||
                        it == "BLE mfg skip" ||
                        it == "BLE scheduler probe" ||
                        it == "Queued BLE targets" ||
                        it == "CANDIDATE_POOL_SNAPSHOT" ||
                        it == "TARGET_SELECTION_DECISION" ||
                        it == "BLE target fallback preferred" ||
                        it == "BROADCAST_REJECT_DECISION" ||
                        it == "NEARBY_COUNT_BREAKDOWN" ||
                        it == "DISPATCH_NO_JOB_STREAK"
                },
            ),
            ActivityLogLabelGroup(
                id = "ble_batch_relay",
                title = "BLE Batch & Relay",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                    it.startsWith("BLE batch ") ||
                    it.startsWith("BLE delayed relay ") ||
                    it.startsWith("BLE relay ") ||
                        it.startsWith("BLE send ") ||
                        it == "mesh active packet released" ||
                        it.startsWith("PIPELINE ") ||
                        it == "mesh stale packet dropped"
                },
            ),
            ActivityLogLabelGroup(
                id = "transport_gatt",
                title = "Transport & GATT",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                    it.startsWith("GATT ") ||
                        it == "MTU negotiation" ||
                        it == "Mesh transport start" ||
                        it == "mesh chunk geometry update"
                },
            ),
            ActivityLogLabelGroup(
                id = "messages_timeline",
                title = "Message Timeline",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                    it == "Received message at" ||
                        it == "Rendered message at" ||
                        it == "Sent message at"
                },
            ),
            ActivityLogLabelGroup(
                id = "nearby_discovery",
                title = "Nearby Discovery",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                    it.startsWith("Nearby room advertise") ||
                    it.startsWith("Nearby room advertisement") ||
                    it.startsWith("Nearby room discovery") ||
                    it.startsWith("Nearby rooms discovery") ||
                    it.startsWith("Nearby rooms refresh") ||
                        it == "Nearby changed" ||
                        it == "Nearby discovery metrics" ||
                        it == "Nearby room self suppressed"
                },
            ),
            ActivityLogLabelGroup(
                id = "nearby_room_flow",
                title = "Nearby Room Flow",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                        it.startsWith("Nearby room metadata fetch") ||
                        it.startsWith("Nearby room metadata refresh") ||
                        it.startsWith("Nearby room key ") ||
                        it.startsWith("Nearby room post-join") ||
                        it.startsWith("Nearby room pending join") ||
                        it.startsWith("Nearby room auto-open") ||
                        it.startsWith("Nearby room message ") ||
                        it.startsWith("Nearby room transport ") ||
                        it.startsWith("Nearby room join ") ||
                        it.startsWith("Nearby room activity") ||
                        it.startsWith("Nearby room participant") ||
                        it.startsWith("Nearby room member") ||
                        it.startsWith("Nearby room owner mute") ||
                        it.startsWith("Nearby room session") ||
                        it.startsWith("Nearby room closed") ||
                        it == "Nearby sender suppression metrics" ||
                        it == "JOIN_READY" ||
                        it == "NEARBY_ROOM_KEY_REQUEST_V1" ||
                        it == "NEARBY_ROOM_KEY_BOOTSTRAP_V1" ||
                        it == "NEARBY_ROOM_MESSAGE_V2" ||
                        it == "inactive_room" ||
                        it == "relay_ineligible"
                },
            ),
            ActivityLogLabelGroup(
                id = "nearby_foreground_runtime",
                title = "Nearby Foreground Runtime",
                labels = NearbyForegroundDiagnosticLabels.ALL,
            ),
            ActivityLogLabelGroup(
                id = "app_dispatch",
                title = "App Dispatch & ACK Batch",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                    it.startsWith("APP ") ||
                        it.startsWith("ACK batch ") ||
                        it.startsWith("DISPATCH ")
                },
            ),
            ActivityLogLabelGroup(
                id = "dtn_ack",
                title = "DTN & ACK Apply",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                    it.startsWith("DTN ") ||
                        it.startsWith("ACK applied ")
                },
            ),
            ActivityLogLabelGroup(
                id = "group_governance",
                title = "Group Governance",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                    it.startsWith("Member removal") ||
                        it.startsWith("Removed member") ||
                        it.startsWith("Group ") ||
                        it == "SYS ACK blocked removed member"
                },
            ),
            ActivityLogLabelGroup(
                id = "internal_markers",
                title = "Internal Markers",
                labels = ACTIVITY_LOG_ALL_LABELS.filter {
                    it == "backpressure_active"
                },
            ),
        ).filter { it.labels.isNotEmpty() }
        private val DISCOVERY_MARKER_LABELS = listOf(
            "BLE scan callback",
            "BLE mfg match",
            "BLE mfg skip",
            "Nearby changed",
            "Nearby rooms discovery burst",
            "Nearby room self suppressed",
            "Nearby room advertise provider",
            "Nearby room advertisement state",
            "Nearby room advertisement refresh skipped",
            "Nearby room advertisement refresh deferred",
            "Nearby room advertisement refresh rejected",
            "Nearby room advertise payload",
            "Nearby room discovery payload",
            "Queued BLE targets",
            "BLE target selected",
            "BLE target pinned",
            "GATT connect failed",
            "GATT disconnect reason",
            "GATT connect backoff",
            "GATT connected",
            "GATT services discovered",
            "GATT server started",
            "BLE advertise started",
            "App foreground",
            "App background",
        )
        private val DEFAULT_ACTIVITY_LOG_ALLOWED_LABELS = listOf(
            "APP batch emit",
            "APP batch extract",
            "APP batch bypass",
            "APP batch window armed",
            "APP batch flush start",
            "APP batch target pending",
            "APP batch transport queued",
            "APP batch transport skipped",
            "APP batch dispatch deferred",
            "APP batch dispatch released",
            "APP dispatch job claimed",
            "APP dispatch job completed",
            "APP dispatch job failed",
            "ACK batch dispatch claimed",
            "ACK batch transport queued",
            "ACK batch dispatch completed",
            "ACK batch dispatch failed",
            "ACK batch reconcile",
            "BLE advertised device_id",
            "BLE mesh mode",
            "BLE mesh startup stalled",
            "BLE mesh transport stalled",
            "BLE batch forward queued",
            "BLE batch forward skipped",
            "BLE batch forward delivered",
            "BLE batch forward failed",
            "BLE batch send progress",
            "BLE batch send complete",
            "BLE batch send failed",
            "BLE send progress",
            "BLE send complete",
            "BLE send failed",
            "BLE batch in-progress stuck watchdog",
            "BLE batch in-progress stuck snapshot",
            "BLE batch state transition",
            "BLE notification status",
            "BLE peer fairness",
            "BLE connect cap blocked",
            "BLE traffic throttle",
            "BLE service lifecycle",
            "BLE service start command",
            "BLE service evaluate state",
            "BLE service evaluate decision",
            "BLE service stop mesh",
            "BLE service start request",
            "BLE bluetooth state",
            "BLE location state",
            "BLE relay decision",
            "BLE target selected",
            "BLE delayed relay scheduled",
            "BLE delayed relay deferred",
            "BLE delayed relay fired",
            "BLE delayed relay dropped",
            "Received message at",
            "Rendered message at",
            "Mesh transport start",
            "Sent message at",
            "MTU negotiation",
            "GATT addService failed",
            "GATT cleanup scheduled",
            "GATT connect backoff",
            "GATT connect failed",
            "GATT connect skipped",
            "GATT connect timeout",
            "GATT connected",
            "GATT disconnect reason",
            "GATT hard reset",
            "GATT write failed",
            "GATT write rejected",
            "GATT write skipped",
            "GATT recv chunk",
            "GATT reassembly",
            "GATT reassembled",
            "GATT deliver envelope",
            "GATT server start failed",
            "GATT server started",
            "GATT services discovered",
            "GATT stalled",
            "Queued BLE targets",
            "DISPATCH pull attempt",
            "DISPATCH pull terminal state",
            "DISPATCH job pulled",
            "DISPATCH job terminal",
            "DISPATCH job pull rejected",
            "DISPATCH job pull skipped",
            "mesh chunk geometry update",
            "CANDIDATE_POOL_SNAPSHOT",
            "TARGET_SELECTION_DECISION",
            "BLE target fallback preferred",
            "DISPATCH_NO_JOB_STREAK",
            "BROADCAST_REJECT_DECISION",
            "NEARBY_COUNT_BREAKDOWN",
            "PIPELINE connect backoff gate",
            "PIPELINE drain idle",
            "PIPELINE enqueue drop all",
            "PIPELINE label enqueue",
            "PIPELINE label execute",
            "PIPELINE label requeue",
            "PIPELINE labels changed",
            "BLE mfg match",
            "BLE mfg skip",
            "BLE advertise started",
            "Nearby rooms discovery burst",
            "Nearby changed",
            "Nearby room self suppressed",
            "Nearby room advertise provider",
            "Nearby room advertisement state",
            "Nearby room advertisement refresh skipped",
            "Nearby room advertisement refresh deferred",
            "Nearby room advertisement refresh rejected",
            "Nearby room advertise payload",
            "Nearby room discovery payload",
            "PIPELINE staged to pending",
            "mesh active packet released",
            "mesh stale packet dropped",
            "DTN control envelope detected",
            "DTN control handle result",
            "DTN control queue decision",
            "DTN control queue state",
            "DTN control wire start",
            "DTN control wire complete",
            "DTN control wire failed",
            "DTN hello sent",
            "DTN hello recv",
            "DTN hello drop",
            "DTN hello send_failed",
            "DTN inv_have sent",
            "DTN inv_have recv",
            "DTN onHave enter",
            "DTN onHave compare start",
            "DTN onHave compare complete",
            "DTN onHave compare failed",
            "DTN onHave outcome",
            "DTN control handle failed",
            "DTN inv_have send_failed",
            "DTN inv_want sent",
            "DTN inv_want recv",
            "DTN inv_want send_failed",
            "DTN dependency want queued",
            "DTN dependency want send_failed",
            "DTN dependency want recv",
            "DTN dependency data served",
            "DTN dependency data recv",
            "DTN data sent",
            "DTN data recv",
            "DTN data send_failed",
            "DTN ack sent",
            "DTN ack recv",
            "DTN ack send_failed",
            "DTN sync summary",
            "DTN manifest_key sent",
            "DTN manifest_key recv",
            "DTN manifest_key send_failed",
            "DTN manifest_offer sent",
            "DTN manifest_offer recv",
            "DTN manifest_offer send_failed",
            "DTN manifest_accept sent",
            "DTN manifest_accept recv",
            "DTN manifest_accept send_failed",
            "DTN carrier_data sent",
            "DTN carrier_data recv",
            "DTN carrier_data send_failed",
            "DTN opaque retry store",
            "DTN opaque retry queued",
            "DTN opaque retry skipped",
            "DTN opaque retry bookkeeping_probe",
            "DTN opaque retry mark_forwarded",
            "DTN opaque retry pre_mark_probe",
            "DTN opaque retry pre_mark_result",
            "DTN opaque retry queued_state",
            "DTN opaque retry send_complete",
            "DTN opaque retry delete",
            "DTN opaque retry retry_failed",
            "ACK applied delivered",
            "ACK applied seen",
        )
        private val DEFAULT_ACTIVITY_LOG_ALLOWED_LABELS_TEXT =
            DEFAULT_ACTIVITY_LOG_ALLOWED_LABELS.joinToString(separator = "\n")
    }

    private fun renderSeedList(container: LinearLayout, seedInput: EditText) {
        container.removeAllViews()
        val seeds = RelaySyncSettings.parseSeedRelays(
            RelaySyncSettings.loadSeedRelaysRaw(this)
        )
        val selected = RelaySyncSettings.loadSelectedRelays(this).toSet()
        seeds.forEach { url ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val checkbox = CheckBox(this).apply {
                isChecked = selected.isEmpty() || selected.contains(url)
            }
            val label = TextView(this).apply {
                text = url
                setPadding(8, 0, 8, 0)
            }
            val del = Button(this).apply {
                setText(R.string.seed_relays_delete)
                setTextColor(ContextCompat.getColor(this@TestActivity, R.color.peer_mesh_button_secondary_text))
                setOnClickListener {
                    val updated = seeds.filterNot { it == url }
                    RelaySyncSettings.saveSeedRelaysRaw(this@TestActivity, updated.joinToString("\n"))
                    val updatedSelected = RelaySyncSettings.loadSelectedRelays(this@TestActivity)
                        .filterNot { it == url }
                    RelaySyncSettings.saveSelectedRelays(this@TestActivity, updatedSelected)
                    seedInput.setText(updated.joinToString("\n"))
                    renderSeedList(container, seedInput)
                }
            }
            row.addView(checkbox)
            row.addView(label)
            row.addView(del)
            row.tag = url
            container.addView(row)
        }
    }

    private fun configureProEntitlementDebug(
        container: LinearLayout,
        status: TextView,
        free: Button,
        trial: Button,
        pro: Button,
        expired: Button,
        unknown: Button,
        clear: Button,
        syncBeta: Button,
    ) {
        if (!BuildConfig.ALLOW_PRO_DEV_OVERRIDE) {
            container.visibility = View.GONE
            return
        }
        fun render() {
            val state = PeerMeshEntitlements.current()
            status.text = getString(
                R.string.pro_entitlement_debug_status,
                state.status.id,
                state.source,
            )
        }
        fun setOverride(entitlementStatus: EntitlementStatus) {
            PeerMeshEntitlements.setDevOverride(this, entitlementStatus)
            render()
            Toast.makeText(
                this,
                getString(R.string.pro_entitlement_debug_changed, entitlementStatus.id),
                Toast.LENGTH_SHORT
            ).show()
        }
        free.setOnClickListener { setOverride(EntitlementStatus.FREE) }
        trial.setOnClickListener { setOverride(EntitlementStatus.TRIAL) }
        pro.setOnClickListener { setOverride(EntitlementStatus.PRO) }
        expired.setOnClickListener { setOverride(EntitlementStatus.EXPIRED) }
        unknown.setOnClickListener { setOverride(EntitlementStatus.UNKNOWN) }
        clear.setOnClickListener {
            PeerMeshEntitlements.clearDevOverride(this)
            render()
            Toast.makeText(this, R.string.pro_entitlement_debug_cleared, Toast.LENGTH_SHORT).show()
        }
        syncBeta.setOnClickListener {
            syncBeta.isEnabled = false
            Thread {
                val refreshResult = PeerMeshBetaProgram.refresh(applicationContext)
                runOnUiThread {
                    syncBeta.isEnabled = true
                    if (refreshResult.errorCode != null && refreshResult.config == null) {
                        Toast.makeText(this, R.string.pro_entitlement_debug_beta_sync_failed, Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    if (refreshResult.config?.enabled == true) {
                        PeerMeshEntitlements.setDevOverride(this, EntitlementStatus.FREE)
                        render()
                        Toast.makeText(this, R.string.pro_entitlement_debug_beta_enabled, Toast.LENGTH_SHORT).show()
                    } else {
                        PeerMeshEntitlements.clearDevOverride(this)
                        render()
                        Toast.makeText(this, R.string.pro_entitlement_debug_beta_disabled, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        render()
    }

    private fun collectSelected(container: LinearLayout): List<String> {
        val selected = mutableListOf<String>()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            val url = row.tag as? String ?: continue
            val checkbox = row.getChildAt(0) as? CheckBox ?: continue
            if (checkbox.isChecked) selected.add(url)
        }
        return selected
    }

    private fun ensureDefaultSelection() {
        val seeds = RelaySyncSettings.parseSeedRelays(RelaySyncSettings.loadSeedRelaysRaw(this))
        val selected = RelaySyncSettings.loadSelectedRelays(this)
        if (seeds.isNotEmpty() && selected.isEmpty()) {
            RelaySyncSettings.saveSelectedRelays(this, listOf(seeds.first()))
        }
    }

    private fun warnIfHttpBlocked() {
        if (RelaySyncSettings.isDeveloperMode(this)) return
        val seeds = RelaySyncSettings.parseSeedRelays(RelaySyncSettings.loadSeedRelaysRaw(this))
        if (seeds.any { it.startsWith("http://") }) {
            Toast.makeText(this, "HTTP blocked (enable Developer Mode)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createDebugSeed(): Boolean {
        return try {
            val db = AppDatabaseProvider.get(applicationContext)
            val passphrase = "debug-passphrase"
            val salt = ByteArray(16) { 1 }
            val groupKey = GroupKeyDeriver.deriveGroupKey(passphrase.toCharArray(), salt)
            val gid = GidFactory.derive(passphrase.toCharArray(), salt)
            val now = System.currentTimeMillis() / 1000L
            val kid = GroupKeyIdFactory.fromKey(groupKey, salt)
            val group = GroupEntity(
                gid = gid,
                kid = kid,
                name = "Debug Group",
                owner_did = "debug-device",
                context_profile = MapKinds.PROFILE_URBAN,
                created_at = now,
                updated_at = now,
            )
            db.runInTransaction {
                db.keyDao().upsert(
                    app.peermesh.data.db.entity.KeyEntity(
                        gid = gid,
                        kid = kid,
                        created_at = now,
                        updated_at = now,
                    )
                )
                db.groupDao().upsert(group)
            }
            SecureKeyStore(applicationContext).storeGroupKey(gid, groupKey)
            SecureKeyStore(applicationContext).storeSalt(gid, salt)
            SecureKeyStore(applicationContext).storeGroupKeyVersion(gid, kid, groupKey)

            val nonce = EventIdFactory.generateNonce()
            val core = EventCore(
                ev = 1,
                id = EventIdFactory.generate(gid, "debug-device", now, nonce),
                type = EventTypes.CHAT_MSG,
                actor = Actor(did = "debug-device"),
                time = EventTime(t = now, lt = 1),
                ref = EventRef(root = null, parent = null, target = null),
                meta = EventMeta(ttl_s = 86400, prio = "normal"),
                data = buildJsonObject {
                    put("text", "Hello relay")
                }
            )
            val coreWithKid = KeyIdHelper.attachKeyId(applicationContext, gid, core)
            val payloadJson = CanonicalJson.json.encodeToString(EventCore.serializer(), coreWithKid)
            val event = EventEntity(
                gid = gid,
                event_id = coreWithKid.id,
                type = coreWithKid.type,
                source = "mesh",
                created_t = now,
                time_lt = coreWithKid.time.lt ?: 0L,
                actor_did = coreWithKid.actor.did,
                ref_root = coreWithKid.ref.root,
                ref_parent = coreWithKid.ref.parent,
                ref_target = coreWithKid.ref.target,
                ttl_s = coreWithKid.meta?.ttl_s,
                payload_json = payloadJson,
                inserted_at = now,
            )
            db.eventsDao().insert(event)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun clearLocalDatabase(): Boolean {
        return try {
            val db = AppDatabaseProvider.get(applicationContext)
            db.clearAllTables()
            true
        } catch (_: Exception) {
            false
        }
    }

}
