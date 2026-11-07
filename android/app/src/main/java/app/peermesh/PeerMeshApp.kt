package app.peermesh

import android.app.Application
import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.peermesh.ble.BleMeshRuntime
import app.peermesh.ble.BleMeshWorkManager
import app.peermesh.transport.ble.BleConfig
import android.bluetooth.le.ScanSettings
import app.peermesh.relay.RelaySyncLifecycleCoordinator
import app.peermesh.ble.MeshAckManager
import app.peermesh.TrailRecorder
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.ingest.IngestNotifier
import app.peermesh.core.types.EventTypes
import app.peermesh.feedback.FeedbackSyncWorkManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import app.peermesh.beta.BetaProgramConfig
import app.peermesh.beta.PeerMeshBetaProgram
import app.peermesh.data.debug.ActivityLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class PeerMeshApp : Application(), DefaultLifecycleObserver {
    @Volatile private var lifecycleKnown = false
    @Volatile private var appInForeground = false

    private val entitlementListener: (EntitlementState) -> Unit = {
        applyEntitlementLifecycle()
    }
    private val betaProgramListener: (BetaProgramConfig?) -> Unit = {
        applyEntitlementLifecycle()
    }

    override fun onCreate() {
        super<Application>.onCreate()
        PeerMeshPrefs.migrateLegacyNearbyControlToAutomatic(applicationContext)
        app.peermesh.relay.RelaySyncSettings.migrateLegacyHeaderControlToAutomatic(applicationContext)
        ActivityLog.setEnabled(BuildConfig.ENABLE_ACTIVITY_LOG)
        PeerMeshEntitlements.initialize(applicationContext)
        PeerMeshBetaProgram.initialize(applicationContext)
        PeerMeshBillingManager.initialize(applicationContext)
        installCrashLogger()
        BleConfig.useManufacturerData = true
        BleConfig.scanFilterEnabled = true
        BleConfig.useNordicBackend = true
        MeshAckManager.init(applicationContext)
        IngestNotifier.add(object : IngestNotifier.Listener {
            override fun onEventInserted(gid: String, eventId: String) {
                CloudToBleBridge.onEventInserted(applicationContext, gid, eventId)
                handleLiveTrailPendingNotify(gid, eventId)
                handleLiveTrailBackfill(gid, eventId)
                RemovedMemberLocalAccessManager.handleEventInserted(applicationContext, gid, eventId)
            }

            override fun onCanonicalMapRepairRequested(gid: String, rootEventId: String) {
                app.peermesh.relay.RelaySyncWorkManager.requestMapDependencyRepair(
                    context = applicationContext,
                    gid = gid,
                    rootEventId = rootEventId,
                )
            }
        })
        LiveTrailCleanupWorkManager.enqueue(applicationContext)
        NearbyRoomCleanupWorkManager.enqueue(applicationContext)
        BleMeshWorkManager.enqueue(applicationContext)
        FeedbackSyncWorkManager.enqueue(applicationContext)
        PeerMeshEntitlements.addListener(entitlementListener)
        PeerMeshBetaProgram.addListener(betaProgramListener)
        PeerMeshBetaProgram.refreshAsyncIfStale(applicationContext, minIntervalMs = 0L)
        if (!ActiveGroupManager.getActiveGroup(applicationContext).isNullOrBlank()) {
            SysMetadataSyncWorkManager.enqueue(applicationContext)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyKeepScreenOn(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                applyKeepScreenOn(activity)
                PeerMeshBillingManager.refreshEntitlementsIfStale(activity.applicationContext)
                PeerMeshBetaProgram.refreshAsyncIfStale(activity.applicationContext)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun onStart(owner: LifecycleOwner) {
        lifecycleKnown = true
        appInForeground = true
        ActivityLog.add(label = "App foreground", source = "app", message = "process onStart")
        // App in foreground: prefer low-latency scan.
        BleConfig.scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
        BleConfig.scanReportDelayMs = 0L
        BleMeshRuntime.get(applicationContext).updateScanSettings()
        if (PeerMeshPrefs.isBleMeshUserEnabled(applicationContext) &&
            !app.peermesh.ble.BleForegroundService.isServiceRunning() &&
            app.peermesh.ble.BleForegroundService.canStartMesh(applicationContext)
        ) {
            app.peermesh.ble.BleForegroundService.startMesh(
                applicationContext,
                caller = "PeerMeshApp.onStart",
                reason = "process_foreground",
            )
        }
        applyTrailLifecycle()
        applyRelayLifecycle()
    }

    override fun onStop(owner: LifecycleOwner) {
        lifecycleKnown = true
        appInForeground = false
        ActivityLog.add(label = "App background", source = "app", message = "process onStop")
        // App in background: reduce scan overhead.
        BleConfig.scanMode = ScanSettings.SCAN_MODE_BALANCED
        BleConfig.scanReportDelayMs = 2000L
        BleMeshRuntime.get(applicationContext).updateScanSettings()
        applyTrailLifecycle()
        applyRelayLifecycle()
    }

    private fun applyEntitlementLifecycle() {
        if (PeerMeshCapabilities.canUseSharedLiveTrail) {
            LiveTrailCleanupWorkManager.enqueue(applicationContext)
        } else {
            LiveTrailCleanupWorkManager.cancel(applicationContext)
        }
        applyTrailLifecycle()
        RelaySyncLifecycleCoordinator.onEntitlementOrSettingsChanged(
            context = applicationContext,
            lifecycleKnown = lifecycleKnown,
            appInForeground = appInForeground,
        )
    }

    private fun applyTrailLifecycle() {
        if (!PeerMeshCapabilities.canUseTrail) {
            TrailRecorder.disableForEntitlement(applicationContext)
            return
        }
        if (!lifecycleKnown) return
        if (appInForeground) {
            TrailRecorder.onAppForeground(applicationContext)
        } else {
            TrailRecorder.onAppBackground(applicationContext)
        }
    }

    private fun applyRelayLifecycle() {
        RelaySyncLifecycleCoordinator.onProcessForegroundChanged(
            context = applicationContext,
            lifecycleKnown = lifecycleKnown,
            appInForeground = appInForeground,
        )
    }

    private fun applyKeepScreenOn(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { CrashLogger.log(applicationContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    @Suppress("MissingPermission")
    private fun handleLiveTrailPendingNotify(gid: String, eventId: String) {
        if (!PeerMeshCapabilities.canUseSharedLiveTrail) return
        if (GroupMapActivity.isForeground) return
        if (!canPostNotifications()) return
        val db = AppDatabaseProvider.get(applicationContext)
        val event = db.eventsDao().findById(gid, eventId) ?: return
        if (event.type != EventTypes.LIVE_TRAIL_START) return
        val trailId = event.ref_target ?: return
        val trail = db.liveTrailDao().findTrail(gid, trailId) ?: return
        val actorDid = DeviceIdProvider.get(applicationContext)
        if (!isLiveTrailVisible(trail, actorDid)) return
        if (trail.owner_did == actorDid) return

        val prefs = getSharedPreferences("live_trail_pending_notify", MODE_PRIVATE)
        val key = "notified_${trail.trail_id}"
        if (prefs.getBoolean(key, false)) return
        ensureLiveTrailNotificationChannel()
        val ownerName = db.userProfileDao().find(gid, trail.owner_did)?.display_name
            ?.takeIf { it.isNotBlank() }
            ?: shortAuthorId(trail.owner_did)
        val title = getString(R.string.live_trail_pending_title)
        val body = getString(R.string.live_trail_pending_body, ownerName)
        val intent = Intent(applicationContext, GroupMapActivity::class.java).apply {
            putExtra(GroupMapActivity.EXTRA_GID, gid)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            trail.trail_id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(applicationContext, LIVE_TRAIL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mesh)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        val notification = builder.build()
        NotificationManagerCompat.from(applicationContext)
            .notify(trail.trail_id.hashCode(), notification)
        prefs.edit().putBoolean(key, true).apply()
    }

    private fun handleLiveTrailBackfill(gid: String, eventId: String) {
        if (!PeerMeshCapabilities.canUseSharedLiveTrail) return
        Thread {
            val db = AppDatabaseProvider.get(applicationContext)
            val event = db.eventsDao().findById(gid, eventId) ?: return@Thread
            if (event.type != EventTypes.LIVE_TRAIL_END) return@Thread
            val trailId = event.ref_target ?: return@Thread
            LiveTrailBackfillManager.backfillIfEnded(applicationContext, gid, trailId)
        }.start()
    }

    private fun isLiveTrailVisible(
        trail: app.peermesh.data.db.entity.LiveTrailEntity,
        actorDid: String,
    ): Boolean {
        if (trail.owner_did == actorDid) return true
        return when (trail.share_scope) {
            LiveTrailShareManager.SCOPE_GROUP -> true
            LiveTrailShareManager.SCOPE_MEMBERS -> {
                val members = parseShareMembers(trail.share_to_json)
                members.contains(actorDid)
            }
            else -> true
        }
    }

    private fun parseShareMembers(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val element = Json.parseToJsonElement(raw)
            val arr = element as? JsonArray ?: return emptySet()
            arr.mapNotNull { (it as? JsonPrimitive)?.content }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun shortAuthorId(actorDid: String): String {
        return if (actorDid.length <= 6) actorDid else actorDid.takeLast(6)
    }

    private fun canPostNotifications(): Boolean {
        return NotificationGate.status(applicationContext, LIVE_TRAIL_CHANNEL_ID).enabled
    }

    private fun ensureLiveTrailNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(LIVE_TRAIL_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            LIVE_TRAIL_CHANNEL_ID,
            getString(R.string.live_trail_pending_channel),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val PREFS_NAME = PeerMeshPrefs.NAME
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val LIVE_TRAIL_CHANNEL_ID = "live_trail_share_pending"
    }
}
