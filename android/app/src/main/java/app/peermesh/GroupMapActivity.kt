package app.peermesh

import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.provider.Settings
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.ble.BleMeshRuntime
import app.peermesh.ble.BlePermissionHelper
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.id.EventIdFactory
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import app.peermesh.data.db.entity.TrailEntity
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.ingest.IngestNotifier
import app.peermesh.data.membership.GroupMembershipPolicy
import app.peermesh.data.map.local.LocalMapEventWriterFactory
import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.local.LocalMapWriteRequest
import app.peermesh.data.map.processing.MapEventRejectionReason
import app.peermesh.data.reducer.ReducerConstants
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.rendering.GroupMapController
import app.peermesh.map.application.rendering.GroupMapOperationState
import app.peermesh.map.application.rendering.GroupMapStateListener
import app.peermesh.map.application.rendering.GroupMapViewportRequest
import app.peermesh.map.application.rendering.MapDensityBand
import app.peermesh.map.application.rendering.MapRenderEnvironment
import app.peermesh.map.application.rendering.MapZoomBand
import app.peermesh.map.composition.GroupMapRenderingFactory
import app.peermesh.map.composition.MapCreationFactory
import app.peermesh.map.composition.MapSceneReadFactory
import app.peermesh.map.application.creation.MapCreationShape
import app.peermesh.map.application.creation.MapCreationSubmissionResult
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import app.peermesh.map.presentation.creation.MapCreationController
import app.peermesh.map.presentation.creation.MapCreationFeedback
import app.peermesh.map.presentation.creation.MapCreationOperationState
import app.peermesh.map.presentation.creation.MapCreationPendingFeedbackCoordinator
import app.peermesh.map.presentation.creation.MapCreationPendingFeedbackDecision
import app.peermesh.map.presentation.creation.MapCreationSubmissionId
import app.peermesh.map.presentation.creation.MapCreationStateListener
import app.peermesh.map.presentation.detail.MapItemDetailActivity
import app.peermesh.map.presentation.list.MapOwnerNameProjection
import app.peermesh.map.presentation.map.navigation.DefaultMapItemNavigationCoordinatorFactory
import app.peermesh.map.presentation.map.navigation.MapItemNavigationCoordinator
import app.peermesh.map.presentation.map.navigation.MapItemNavigationDecision
import app.peermesh.map.presentation.map.resources.AndroidMapVisualResourceResolver
import app.peermesh.map.presentation.map.resources.MapVisualResourceResolver
import app.peermesh.map.presentation.map.MapGroupScopePolicy
import app.peermesh.map.presentation.map.MapGroupScopeToken
import app.peermesh.map.presentation.map.MapRefreshTimerPolicy
import app.peermesh.map.presentation.map.MapOwnerDeleteAvailabilityPolicy
import app.peermesh.map.presentation.map.ScopedTrailIdentity
import app.peermesh.map.presentation.map.ScopedRetryOwnership
import app.peermesh.map.presentation.map.RetryAttemptDecision
import app.peermesh.map.presentation.map.ScopedRetryAttemptState
import app.peermesh.map.presentation.map.runtime.MapRuntimeItemClickListener
import app.peermesh.map.presentation.map.runtime.MapRuntimeRenderRegistry
import app.peermesh.map.presentation.map.runtime.MapRuntimeRenderer
import app.peermesh.map.presentation.map.runtime.OsmdroidMapRuntimeRenderer
import app.peermesh.map.presentation.map.runtime.OsmdroidMapCreationDraftRenderer
import app.peermesh.security.crypto.SecureKeyStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Overlay
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GroupMapActivity : AppCompatActivity() {
    private val meshTransport by lazy { BleMeshRuntime.get(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val localMapEventWriter by lazy {
        LocalMapEventWriterFactory.lazy(AppDatabaseProvider.get(applicationContext))
    }
    private var ingestListener: IngestNotifier.Listener? = null
    private var mapRefreshRunnable: Runnable? = null
    private var mapRefreshPaused = false
    private var mapRefreshGid: String? = null
    private var mapRefreshAdapter: MapListAdapter? = null
    private var mapRefreshScopeGeneration: Long? = null
    private var remoteMapRefreshRunnable: Runnable? = null
    private var pendingRemoteMapRefreshGid: String? = null

    private lateinit var mapView: MapView
    private lateinit var mapFrame: android.widget.FrameLayout
    private lateinit var mapItemsList: androidx.recyclerview.widget.RecyclerView
    private lateinit var mapCollapseHandle: android.view.View
    private lateinit var statusText: TextView
    private lateinit var mapActivityBanner: View
    private lateinit var mapActivityBannerText: TextView
    private lateinit var actionBar: android.view.View
    private lateinit var doneBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var groupMapController: GroupMapController
    private lateinit var mapVisualResourceResolver: MapVisualResourceResolver
    private lateinit var mapRuntimeRenderer: MapRuntimeRenderer
    private lateinit var mapItemNavigationCoordinator: MapItemNavigationCoordinator
    private lateinit var mapCreationController: MapCreationController
    private lateinit var mapCreationDraftRenderer: OsmdroidMapCreationDraftRenderer
    private lateinit var mapCreationStateListener: MapCreationStateListener
    private lateinit var groupMapStateListener: GroupMapStateListener
    private val mapSceneExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "group-map-scene").apply { isDaemon = true }
    }
    private var mapRenderingClosed = false
    private data class PendingCreationUi(
        val groupId: String,
        val scopeGeneration: Long,
        val submissionId: MapCreationSubmissionId,
        val dialog: AlertDialog,
        val button: Button,
        val onFinished: (() -> Unit)?,
    )
    private var pendingCreationUi: PendingCreationUi? = null
    private val mapCreationPendingFeedbackCoordinator = MapCreationPendingFeedbackCoordinator()

    private var currentGid: String = ""
    private var currentGroupName: String? = null
    /** Changes only when the authoritative map group changes; dialogs capture it. */
    private var mapGroupScopeGeneration = 0L
    /** Owns raw candidate callbacks; changing groups invalidates every older load. */
    private var mapCandidateGeneration = 0L
    private var groupProfile: String = MapKinds.PROFILE_URBAN
    private var listMarkers: List<MapMarkerEntity> = emptyList()
    private var listAreas: List<MapAreaEntity> = emptyList()
    private var mapMarkerCandidates: List<MapMarkerEntity> = emptyList()
    private var mapAreaCandidates: List<MapAreaEntity> = emptyList()
    private var currentLiveTrails: List<app.peermesh.data.db.entity.LiveTrailEntity> = emptyList()
    private var trailPointsById: Map<String, List<app.peermesh.data.db.entity.TrailPointEntity>> = emptyMap()
    private var selectedTrailId: String? = null
    private var pendingExternalTrailId: String? = null
    private var focusingExternalTrailId: String? = null
    private val trailOverlays = mutableListOf<Overlay>()
    private var trailRenderRunnable: Runnable? = null
    private var trailRenderSuspended = false
    private var trailRenderCacheId: String? = null
    private val trailRenderCache = mutableMapOf<TrailRenderKey, List<TrailRenderSegment>>()
    private var trailOverlaysDirty = false
    private var showMarkers = true
    private var showAreas = true
    private val hiddenMarkers = mutableSetOf<String>()
    private val hiddenAreas = mutableSetOf<String>()
    private var markerVoteCounts: Map<String, Pair<Int, Int>> = emptyMap()
    private var areaVoteCounts: Map<String, Pair<Int, Int>> = emptyMap()
    private var markerDeleteAllowedIds: Set<String> = emptySet()
    private var areaDeleteAllowedIds: Set<String> = emptySet()
    private var mapOwnerNames: Map<String, MapOwnerNameProjection> = emptyMap()
    private var liveTrailOwners: Map<String, String> = emptyMap()
    private var liveTrailOwnerAvatars: Map<String, String?> = emptyMap()
    private var liveTrailStaleIds: Set<String> = emptySet()
    private val liveTrailReconnectedUntil = mutableMapOf<String, Long>()
    private data class ScopedTrailKey(val groupId: String, val trailId: String)
    private val liveTrailRetryAttempts =
        ScopedRetryAttemptState<ScopedTrailKey>(LIVE_TRAIL_RETRY_MAX)
    private val liveTrailRetryOwnership =
        ScopedRetryOwnership<ScopedTrailKey, Runnable>(handler::removeCallbacks)

    private fun scheduleLiveTrailRetry(
        groupId: String,
        generation: Long,
        trailId: String,
        action: () -> Unit,
    ) {
        val key = ScopedTrailKey(groupId, trailId)
        cancelLiveTrailRetryTask(groupId, trailId)
        lateinit var runnable: Runnable
        runnable = Runnable {
            liveTrailRetryOwnership.removeIfOwned(key, runnable)
            if (!isCurrentTrailScope(groupId, generation, trailId)) return@Runnable
            action()
        }
        liveTrailRetryOwnership.replace(key, runnable)
        val testScheduler = GroupMapActivityTestSeams.delayedScheduler
        if (testScheduler != null) {
            testScheduler(runnable, LIVE_TRAIL_RETRY_DELAY_MS)
        } else {
            handler.postDelayed(runnable, LIVE_TRAIL_RETRY_DELAY_MS)
        }
    }

    private fun cancelLiveTrailRetryTask(groupId: String, trailId: String) {
        val key = ScopedTrailKey(groupId, trailId)
        liveTrailRetryOwnership.cancel(key)
    }

    private fun resetLiveTrailRetry(groupId: String, trailId: String) {
        val key = ScopedTrailKey(groupId, trailId)
        liveTrailRetryOwnership.cancel(key)
        liveTrailRetryAttempts.reset(key)
    }

    private fun cancelAllLiveTrailRetries() {
        liveTrailRetryOwnership.cancelAll()
        liveTrailRetryAttempts.resetAll()
    }

    private fun loadSelectedTrailPointsWithRetry(
        groupId: String,
        generation: Long,
        trailId: String,
        adapter: MapListAdapter,
        showMissingToast: Boolean,
        allowRetry: Boolean,
    ) {
        if (!isCurrentTrailScope(groupId, generation, trailId)) return
        loadLiveTrailPointsAsync(trailId) { points ->
            if (!isCurrentTrailScope(groupId, generation, trailId)) return@loadLiveTrailPointsAsync
            if (points.isNotEmpty()) {
                resetLiveTrailRetry(groupId, trailId)
                trailPointsById = mapOf(trailId to points)
                clearTrailRenderCache()
                trailRenderCacheId = trailId
                rebuildTrailRenderCache()
                updateMapUi(adapter)
                focusTrail(trailId)
                return@loadLiveTrailPointsAsync
            }

            val key = ScopedTrailKey(groupId, trailId)
            if (!allowRetry) {
                resetLiveTrailRetry(groupId, trailId)
                if (showMissingToast) toast(getString(R.string.map_trail_missing))
                return@loadLiveTrailPointsAsync
            }
            when (val decision = liveTrailRetryAttempts.onEmpty(key)) {
                RetryAttemptDecision.Exhausted -> resetLiveTrailRetry(groupId, trailId)
                is RetryAttemptDecision.Schedule -> {
                    if (showMissingToast && decision.attempt == 1) {
                        toast(getString(R.string.map_trail_missing))
                    }
                    scheduleLiveTrailRetry(groupId, generation, trailId) {
                        loadSelectedTrailPointsWithRetry(
                            groupId,
                            generation,
                            trailId,
                            adapter,
                            showMissingToast = false,
                            allowRetry = allowRetry,
                        )
                    }
                }
            }
        }
    }
    private var liveTrailRefreshRunnable: Runnable? = null
    private var liveTrailRefreshScheduledAt = 0L
    private var liveTrailLastRefreshAt = 0L
    private var lastContribPreviewRefreshMs = 0L
    private var contribRefreshRunnable: Runnable? = null
    private val mapActivityMessages = mutableListOf<MapActivityMessage>()
    private var mapActivityMessageIndex = 0
    private var mapActivityRotationRunnable: Runnable? = null

    private enum class InputMode { FREE, MARKER_DRAFT, AREA_DRAFT }
    private var inputMode: InputMode = InputMode.FREE
    private enum class HeadingSource { LOCATION, COMPASS }

    private enum class MapActivityPriority { INFO, WARNING, ERROR }

    private data class MapActivityMessage(
        val dedupeKey: String,
        val text: String,
        val priority: MapActivityPriority,
        val createdAt: Long,
        val expiresAt: Long,
        val count: Int = 1,
    )

    private var draftAreaInvalidMessage: String? = null
    private val draftAreaToastCooldown = mutableMapOf<String, Long>()

    // map tap overlay (keep reference so we can preserve across overlay refresh)
    private var mapEventsOverlay: MapEventsOverlay? = null
    private var userLocationOverlay: Marker? = null
    private var userHeadingOverlay: Marker? = null
    private val emojiIconCache = mutableMapOf<String, android.graphics.drawable.Drawable>()
    private val labelIconCache = mutableMapOf<String, android.graphics.drawable.Drawable>()
    private val trailEndpointStartCache = mutableMapOf<String, android.graphics.drawable.Drawable>()
    private val trailEndpointEndCache = mutableMapOf<String, android.graphics.drawable.Drawable>()
    private val trailPointIconCache = mutableMapOf<Int, android.graphics.drawable.Drawable>()
    private val areaPointsCache = LinkedHashMap<String, CachedAreaPoints>(0, 0.75f, true)
    private data class CachedAreaPoints(
        val geometryJson: String,
        val points: List<GeoPoint>,
    )
    private var trailPointMarker: Marker? = null
    private var trailPointHideRunnable: Runnable? = null
    private var lastUserLocation: GeoPoint? = null
    private var lastLocation: Location? = null
    private var sensorManager: SensorManager? = null
    private var accelValues = FloatArray(3)
    private var magnetValues = FloatArray(3)
    private var hasAccel = false
    private var hasMagnet = false
    private var hasRotationVector = false
    private var rotationVector = FloatArray(5)
    private var compassHeading: Float? = null
    private var smoothedCompassHeading: Float? = null
    private var smoothedLocationHeading: Float? = null
    private val headingPoints = ArrayDeque<Location>(4)
    private var lastDisplayLocation: Location? = null
    private var lastDisplayBearing: Float? = null
    private var headingSource: HeadingSource? = null
    private var lastHeadingSourceLog: HeadingSource? = null
    private var lastHeadingLogAtMs: Long = 0L
    private var lastHeadingLogDeg: Float? = null
    private var lastFollowLogAtMs: Long = 0L
    private var lastFollowLogHeading: Float? = null
    private var currentLocationIntervalMs: Long = LOCATION_UPDATE_MS
    private var hasCenteredOnUser = false
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var trailLocationListener: ((Location) -> Unit)? = null
    private var trailRecordingListener: ((Boolean) -> Unit)? = null
    private var usingTrailLocation = false
    private var lastLabelZoomVisible: Boolean? = null
    private var lastRadiusZoomVisible: Boolean? = null
    private var currentMotionMode: MotionMode = MotionMode.WALK
    private var userScrollPauseUntilMs: Long = 0L
    private var lastUserScrollAtMs: Long = 0L
    private var autoFollowUntilMs: Long = 0L
    private var followRunnable: Runnable? = null
    private var scrollEndRunnable: Runnable? = null
    private var currentSpeedStatus: Int = SPEED_STATUS_IDLE
    private var mapCollapseState: Int = MAP_STATE_EXPANDED
    private var pendingTrailFocusId: String? = null
    private var handleTouchStartY: Float = 0f
    private val openMapScopeDialogs = mutableSetOf<android.app.Dialog>()
    private var locationPromptShown: Boolean = false
    private var lastLocationAvailability: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        if (!GroupMapActivityTestSeams.bypassFeatureGate &&
            !PeerMeshProGate.require(this, PeerMeshProGate.FEATURE_MAP)
        ) {
            finish()
            return
        }
        setContentView(R.layout.activity_group_map)
        locationPromptShown = savedInstanceState?.getBoolean(KEY_LOCATION_PROMPT_SHOWN) ?: false

        val gid = intent.getStringExtra(EXTRA_GID) ?: ""
        pendingExternalTrailId = intent.getStringExtra(EXTRA_TRAIL_ID)
        currentGid = gid
        currentGroupName = intent.getStringExtra(EXTRA_GROUP_NAME)
        if (gid.isNotBlank()) {
            ActiveGroupManager.setActiveGroup(this, gid)
        }

        val groupName = currentGroupName
        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)

        statusText = findViewById(R.id.map_status_text)
        mapActivityBanner = findViewById(R.id.map_activity_banner)
        mapActivityBannerText = findViewById(R.id.map_activity_banner_text)
        actionBar = findViewById(R.id.map_action_bar)
        cancelBtn = findViewById(R.id.map_cancel)
        doneBtn = findViewById(R.id.map_done)

        mapFrame = findViewById(R.id.map_frame)
        mapView = findViewById(R.id.group_map_view)
        mapItemsList = findViewById(R.id.map_items_list)
        mapCollapseHandle = findViewById(R.id.map_collapse_handle)
        val centerButton = findViewById<android.widget.ImageButton>(R.id.map_center_user)
        val zoomInButton = findViewById<android.widget.ImageButton>(R.id.map_zoom_in)
        val zoomOutButton = findViewById<android.widget.ImageButton>(R.id.map_zoom_out)
        val listView = mapItemsList

        headerTitle.text = if (!groupName.isNullOrBlank()) {
            getString(R.string.page_title_map_format, groupName)
        } else {
            getString(R.string.page_title_map)
        }

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
            menu.menuInflater.inflate(R.menu.group_map_menu, menu.menu)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_group_chat -> {
                        val i = Intent(this, GroupChatActivity::class.java).apply {
                            putExtra(GroupChatActivity.EXTRA_GID, currentGid)
                            putExtra(GroupChatActivity.EXTRA_GROUP_NAME, currentGroupName)
                        }
                        startActivity(i)
                        finish()
                        true
                    }
                    R.id.menu_group_detail -> {
                        val i = Intent(this, GroupDetailActivity::class.java).apply {
                            putExtra(GroupDetailActivity.EXTRA_GID, currentGid)
                        }
                        startActivity(i)
                        true
                    }
                    R.id.menu_group_settings -> {
                        val i = Intent(this, GroupSettingsActivity::class.java).apply {
                            putExtra(GroupSettingsActivity.EXTRA_GID, currentGid)
                            putExtra(GroupSettingsActivity.EXTRA_GROUP_NAME, currentGroupName)
                        }
                        startActivity(i)
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

        lateinit var adapter: MapListAdapter
        adapter = MapListAdapter(
            onToggleMarkers = { enabled ->
                showMarkers = enabled
                updateMapUi(adapter)
            },
            onToggleAreas = { enabled ->
                showAreas = enabled
                updateMapUi(adapter)
            },
            onToggleMarkerVisibility = markerVisibility@{ marker, visible ->
                if (marker.gid != currentGid) return@markerVisibility
                if (visible) {
                    hiddenMarkers.remove(marker.marker_id)
                } else {
                    hiddenMarkers.add(marker.marker_id)
                }
                updateMapUi(adapter)
            },
            onToggleAreaVisibility = areaVisibility@{ area, visible ->
                if (area.gid != currentGid) return@areaVisibility
                if (visible) {
                    hiddenAreas.remove(area.area_id)
                } else {
                    hiddenAreas.add(area.area_id)
                }
                updateMapUi(adapter)
            },
            onMarkerEdit = { marker -> if (marker.gid == currentGid) openMarkerEditDialog(currentGid, marker) },
            onMarkerDelete = { marker -> if (marker.gid == currentGid) confirmDeleteMarker(currentGid, marker) },
            onMarkerFocus = { marker -> if (marker.gid == currentGid) focusMarker(marker) },
            onMarkerDetail = { marker ->
                requestPersistedMapItemDetail(
                    MapItemIdentity(marker.gid, MapItemType.MARKER, marker.marker_id),
                )
            },
            onAreaEdit = { area -> if (area.gid == currentGid) openAreaEditDialog(currentGid, area) },
            onAreaDelete = { area -> if (area.gid == currentGid) confirmDeleteArea(currentGid, area) },
            onAreaFocus = { area -> if (area.gid == currentGid) focusArea(area) },
            onAreaDetail = { area ->
                requestPersistedMapItemDetail(
                    MapItemIdentity(area.gid, MapItemType.AREA, area.area_id),
                )
            },
            onViewAllTrails = {
                startActivity(
                    Intent(this, SharedTrailsActivity::class.java)
                )
            },
            onToggleLiveTrail = trailToggle@{ trail, enabled ->
                if (trail.gid != currentGid) return@trailToggle
                val scopeGroupId = trail.gid
                val scopeGeneration = mapGroupScopeGeneration
                cancelAllLiveTrailRetries()
                selectedTrailId = if (enabled) trail.trail_id else null
                if (enabled) {
                    if (trailRenderCacheId != trail.trail_id) {
                        clearTrailRenderCache()
                        trailRenderCacheId = trail.trail_id
                    }
                } else {
                    clearTrailRenderCache()
                    trailRenderCacheId = null
                    trailPointsById = emptyMap()
                    resetLiveTrailRetry(scopeGroupId, trail.trail_id)
                }
                updateMapUi(adapter)
                if (enabled) {
                    loadSelectedTrailPointsWithRetry(
                        scopeGroupId,
                        scopeGeneration,
                        trail.trail_id,
                        adapter,
                        showMissingToast = true,
                        allowRetry = trail.active == 1,
                    )
                }
            },
            onDeleteLiveTrail = trailDelete@{ trail ->
                if (trail.gid != currentGid) return@trailDelete
                val scopeGroupId = trail.gid
                val scopeGeneration = mapGroupScopeGeneration
                val trailId = trail.trail_id
                resetLiveTrailRetry(scopeGroupId, trailId)
                Thread {
                    val db = AppDatabaseProvider.get(this)
                    db.liveTrailDao().deletePointsByTrailIds(listOf(trail.trail_id))
                    db.liveTrailDao().deleteTrailsByIds(listOf(trail.trail_id))
                    runOnUiThread {
                        if (!isCurrentMapScope(scopeGroupId, scopeGeneration)) return@runOnUiThread
                        if (selectedTrailId == trail.trail_id) {
                            selectedTrailId = null
                            clearTrailRenderCache()
                            trailRenderCacheId = null
                            trailPointsById = emptyMap()
                            resetLiveTrailRetry(scopeGroupId, trailId)
                        }
                        refreshMapUi(scopeGroupId)
                    }
                }.start()
            },
            onOpenSharedTrails = {
                startActivity(
                    android.content.Intent(
                        this,
                        SharedTrailsActivity::class.java
                    ).putExtra(EXTRA_GID, currentGid)
                )
            },
        )
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter
        listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    resumeMapRefreshTimer(resetTimer = true)
                    contribRefreshRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable {
                        maybeRefreshContribPreviews(currentGid, adapter, recyclerView, force = true)
                    }
                    contribRefreshRunnable = r
                    handler.postDelayed(r, 500L)
                } else {
                    pauseMapRefreshTimer()
                    contribRefreshRunnable?.let { handler.removeCallbacks(it) }
                    contribRefreshRunnable = null
                }
            }
        })

        if (gid.isBlank()) {
            toast(getString(R.string.map_group_missing))
            return
        }
        loadGroupProfile(gid)

        initOsmdroidMap(mapView)
        initMapCreation(gid)
        installMapInteractions()
        initSemanticMapRendering()

        // initial hint: show for 5s then hide
        showStatusTemporarily(getString(R.string.map_disclaimer), 5000L)
        handler.postDelayed(
            { showStatus(getString(R.string.map_hint_free)) },
            5000L
        )

        // action buttons
        cancelBtn.setOnClickListener { cancelDraft() }
        doneBtn.setOnClickListener { commitDraft(currentGid) }
        centerButton.setOnClickListener { centerOnUser() }
        zoomInButton.setOnClickListener { mapView.controller.zoomIn() }
        zoomOutButton.setOnClickListener { mapView.controller.zoomOut() }
        mapCollapseHandle.setOnClickListener {
            mapCollapseState = when (mapCollapseState) {
                MAP_STATE_COLLAPSED -> MAP_STATE_EXPANDED
                MAP_STATE_EXPANDED -> MAP_STATE_COLLAPSED
                else -> MAP_STATE_EXPANDED
            }
            applyMapCollapse()
        }
        mapCollapseHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    handleTouchStartY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val deltaY = event.rawY - handleTouchStartY
                    val threshold = android.view.ViewConfiguration.get(this).scaledTouchSlop * 4
                    if (deltaY <= -threshold) {
                        mapCollapseState = when (mapCollapseState) {
                            MAP_STATE_MAX -> MAP_STATE_EXPANDED
                            MAP_STATE_EXPANDED -> MAP_STATE_COLLAPSED
                            else -> MAP_STATE_COLLAPSED
                        }
                        applyMapCollapse()
                        return@setOnTouchListener true
                    }
                    if (deltaY >= threshold) {
                        mapCollapseState = when (mapCollapseState) {
                            MAP_STATE_COLLAPSED -> MAP_STATE_EXPANDED
                            MAP_STATE_EXPANDED -> MAP_STATE_MAX
                            else -> MAP_STATE_MAX
                        }
                        applyMapCollapse()
                        return@setOnTouchListener true
                    }
                    false
                }
                android.view.MotionEvent.ACTION_CANCEL -> false
                else -> false
            }
        }

        if (!GroupMapActivityTestSeams.suppressBackgroundMapLoads) {
            loadMapItems(gid, adapter)
            startMapRefreshTimer(gid, adapter)
            handler.postDelayed(
                { maybeRefreshContribPreviews(currentGid, adapter, listView, force = true) },
                500L,
            )
        }

        ingestListener = object : IngestNotifier.Listener {
            override fun onEventInserted(gid: String, eventId: String) {
                if (gid != currentGid) return
                val db = AppDatabaseProvider.get(applicationContext)
                val event = db.eventsDao().findById(gid, eventId) ?: return
                if (!event.type.startsWith("MAP.") && !EventTypes.isLiveTrail(event.type)) return
                runOnUiThread {
                    if (gid != currentGid) return@runOnUiThread
                    if (EventTypes.isLiveTrail(event.type)) {
                        if (isDrafting()) return@runOnUiThread
                        publishMapActivity(
                            dedupeKey = "trail_updated",
                            text = getString(R.string.map_status_live_trail_updated),
                        )
                        scheduleLiveTrailRefresh(currentGid)
                        return@runOnUiThread
                    }
                    when {
                        event.type.contains("MARKER", ignoreCase = true) -> {
                            publishMapActivity(
                                dedupeKey = "marker_received",
                                text = getString(R.string.map_status_marker_updated),
                            )
                        }

                        event.type.contains("AREA", ignoreCase = true) -> {
                            publishMapActivity(
                                dedupeKey = "area_updated",
                                text = getString(R.string.map_status_area_updated),
                            )
                        }

                        else -> {
                            publishMapActivity(
                                dedupeKey = "map_sync_status",
                                text = getString(R.string.map_status_sync_updated),
                            )
                        }
                    }
                    requestRemoteMapRefresh(gid)
                }
            }
        }

        showLocationPromptIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LOCATION_PROMPT_SHOWN, locationPromptShown)
    }

    private fun showLocationPromptIfNeeded() {
        if (locationPromptShown) return
        if (isLocationEnabled()) return
        locationPromptShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.map_location_prompt_title)
            .setMessage(R.string.map_location_prompt_body)
            .setPositiveButton(R.string.open_location_settings) { _, _ ->
                openLocationSettings()
            }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        isForeground = true
        if (::mapItemNavigationCoordinator.isInitialized) {
            mapItemNavigationCoordinator.consumeReturnRefreshRequest()
        }
        val gid = intent.getStringExtra(EXTRA_GID)
        if (!gid.isNullOrBlank()) {
            ActiveGroupManager.setActiveGroup(this, gid)
        }
        resetMapUiStateIfActorChanged()
        StatusIndicatorUpdater.start(this)
        ingestListener?.let { IngestNotifier.add(it) }
        registerTrailRecordingObserver()
        val adapter = (findViewById<RecyclerView>(R.id.map_items_list).adapter as? MapListAdapter)
        if (!GroupMapActivityTestSeams.suppressBackgroundMapLoads &&
            !gid.isNullOrBlank() &&
            adapter != null
        ) {
            // The normal resume load reads the same persisted authority required after detail.
            // Consume the one-shot above and coalesce it into this single load rather than issuing
            // a second semantic refresh.
            loadMapItems(gid, adapter)
            startMapRefreshTimer(gid, adapter)
            TrailRecorder.setOnTrailSaved {
                currentGid.takeIf { it.isNotBlank() }?.let { loadMapItems(it, adapter) }
            }
        }
        if (TrailRecorder.isRecording()) {
            startTrailLocationUpdates()
        } else {
            startLocationUpdates()
        }
        startCompassUpdates()
        startFollowTicker()
        startMapActivityRotation()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        isForeground = false
        StatusIndicatorUpdater.stop(this)
        ingestListener?.let { IngestNotifier.remove(it) }
        stopMapRefreshTimer()
        remoteMapRefreshRunnable?.let(handler::removeCallbacks)
        remoteMapRefreshRunnable = null
        pendingRemoteMapRefreshGid = null
        cancelAllLiveTrailRetries()
        trailRenderRunnable?.let(handler::removeCallbacks)
        trailRenderRunnable = null
        liveTrailRefreshRunnable?.let(handler::removeCallbacks)
        liveTrailRefreshRunnable = null
        contribRefreshRunnable?.let(handler::removeCallbacks)
        contribRefreshRunnable = null
        trailPointHideRunnable?.let(handler::removeCallbacks)
        trailPointHideRunnable = null
        unregisterTrailRecordingObserver()
        TrailRecorder.setOnTrailSaved(null)
        stopTrailLocationUpdates()
        stopLocationUpdates()
        stopCompassUpdates()
        stopFollowTicker()
        if (::mapRuntimeRenderer.isInitialized) mapRuntimeRenderer.clear()
        labelIconCache.clear()
        emojiIconCache.clear()
        trailEndpointStartCache.clear()
        trailEndpointEndCache.clear()
        areaPointsCache.clear()
        clearTrailRenderCache()
        stopMapActivityRotation()
    }

    override fun onDestroy() {
        mapRenderingClosed = true
        if (::mapItemNavigationCoordinator.isInitialized) mapItemNavigationCoordinator.close()
        cancelAllLiveTrailRetries()
        trailRenderRunnable?.let(handler::removeCallbacks)
        liveTrailRefreshRunnable?.let(handler::removeCallbacks)
        contribRefreshRunnable?.let(handler::removeCallbacks)
        trailPointHideRunnable?.let(handler::removeCallbacks)
        mapRefreshRunnable?.let(handler::removeCallbacks)
        if (::groupMapController.isInitialized) {
            groupMapController.removeListener(groupMapStateListener)
            groupMapController.close()
        }
        if (::mapRuntimeRenderer.isInitialized) mapRuntimeRenderer.close()
        if (::mapCreationController.isInitialized) {
            mapCreationController.removeListener(mapCreationStateListener)
            mapCreationController.close()
        }
        if (::mapCreationDraftRenderer.isInitialized) mapCreationDraftRenderer.close()
        mapSceneExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val nextGid = intent.getStringExtra(EXTRA_GID).orEmpty()
        val groupChanged = nextGid.isNotBlank() && nextGid != currentGid
        if (groupChanged) {
            stopMapRefreshTimer()
            ++mapGroupScopeGeneration
            mapCandidateGeneration++
            openMapScopeDialogs.toList().forEach { it.dismiss() }
            openMapScopeDialogs.clear()
            clearDraftState()
            if (::mapCreationController.isInitialized) mapCreationController.cancel()
            currentGid = nextGid
            currentGroupName = intent.getStringExtra(EXTRA_GROUP_NAME)
            ActiveGroupManager.setActiveGroup(this, nextGid)
            listMarkers = emptyList()
            listAreas = emptyList()
            markerDeleteAllowedIds = emptySet()
            areaDeleteAllowedIds = emptySet()
            mapOwnerNames = emptyMap()
            mapMarkerCandidates = emptyList()
            mapAreaCandidates = emptyList()
            currentLiveTrails = emptyList()
            trailPointsById = emptyMap()
            hiddenMarkers.clear()
            hiddenAreas.clear()
            selectedTrailId = null
            focusingExternalTrailId = null
            pendingTrailFocusId = null
            cancelAllLiveTrailRetries()
            liveTrailStaleIds = emptySet()
            liveTrailReconnectedUntil.clear()
            clearTrailRenderCache()
            trailRenderRunnable?.let { handler.removeCallbacks(it) }
            trailRenderRunnable = null
            liveTrailRefreshRunnable?.let { handler.removeCallbacks(it) }
            liveTrailRefreshRunnable = null
            liveTrailRefreshScheduledAt = 0L
            contribRefreshRunnable?.let { handler.removeCallbacks(it) }
            contribRefreshRunnable = null
            if (::groupMapController.isInitialized) {
                groupMapController.reset()
            }
            if (::mapRuntimeRenderer.isInitialized) mapRuntimeRenderer.clear()
            (findViewById<RecyclerView>(R.id.map_items_list).adapter as? MapListAdapter)?.let {
                clearGroupScopedAdapterState(it)
            }
            findViewById<TextView>(R.id.header_title).text = if (currentGroupName.isNullOrBlank()) {
                getString(R.string.page_title_map)
            } else {
                getString(R.string.page_title_map_format, currentGroupName)
            }
            loadGroupProfile(nextGid)
        }

        pendingExternalTrailId = intent.getStringExtra(EXTRA_TRAIL_ID)

        val adapter = (findViewById<RecyclerView>(R.id.map_items_list).adapter as? MapListAdapter)
        if (!GroupMapActivityTestSeams.suppressBackgroundMapLoads &&
            nextGid.isNotBlank() &&
            adapter != null &&
            groupChanged
        ) {
            loadMapItems(nextGid, adapter)
            startMapRefreshTimer(nextGid, adapter)
        }
    }

    private fun initOsmdroidMap(mapView: MapView) {
        Configuration.getInstance().userAgentValue = packageName
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.setBuiltInZoomControls(false)
        mapView.addMapListener(object : MapListener {
            override fun onZoom(event: ZoomEvent?): Boolean {
                if (isDrafting()) {
                    markUserScrolled()
                    return true
                }
                val zoom = mapView.zoomLevelDouble
                val labelVisible = zoom >= LABEL_MIN_ZOOM
                val radiusVisible = zoom >= RADIUS_MIN_ZOOM
                if (labelVisible != lastLabelZoomVisible || radiusVisible != lastRadiusZoomVisible) {
                    lastLabelZoomVisible = labelVisible
                    lastRadiusZoomVisible = radiusVisible
                    refreshSemanticMapRendering()
                }
                markUserScrolled()
                trailRenderSuspended = true
                scheduleTrailRender()
                return true
            }

            override fun onScroll(event: ScrollEvent?): Boolean {
                markUserScrolled()
                if (isDrafting()) {
                    return false
                }
                trailRenderSuspended = true
                scheduleTrailRender()
                return false
            }
        })

        // defaults (you can replace by last-known center later)
        mapView.controller.setZoom(14.0)
        mapView.controller.setCenter(GeoPoint(10.762622, 106.660172))
    }

    private fun installMapInteractions() {
        // Keep one overlay receiver to handle tap/long-press in different modes
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (inputMode == InputMode.FREE) {
                    if (handleTrailPointTap(p)) {
                        return true
                    }
                    hideTrailPointInfo()
                    startMarkerDraft(p)
                    return true
                }
                hideTrailPointInfo()
                when (inputMode) {
                    InputMode.MARKER_DRAFT -> moveMarkerDraft(p)
                    InputMode.AREA_DRAFT -> addAreaPoint(p)
                    else -> {}
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                if (inputMode == InputMode.FREE) {
                    hideTrailPointInfo()
                    startAreaDraft(p)
                    return true
                }
                return false
            }
        }
        mapEventsOverlay = MapEventsOverlay(receiver)
        mapView.overlays.add(mapEventsOverlay)
    }

    private fun loadMapItems(gid: String, adapter: MapListAdapter) {
        val db = AppDatabaseProvider.get(this)
        val requestGeneration = ++mapCandidateGeneration
        val requestGroupId = gid
        val nowSeconds = System.currentTimeMillis() / 1000L
        val existingSelectedTrailId = selectedTrailId
        val existingTrailPoints = trailPointsById
        Thread {
            val markers = db.mapDao().listActiveMarkers(gid, nowSeconds, MAX_ITEMS)
            val areas = db.mapDao().listActiveAreas(gid, nowSeconds, MAX_ITEMS)
            val markerCandidates = db.mapDao().listMarkerCandidates(gid, MAX_ITEMS)
            val areaCandidates = db.mapDao().listAreaCandidates(gid, MAX_ITEMS)
            val markerCounts = buildVoteCounts(
                db.mapItemVoteDao().countByItemIds(
                    gid,
                    "marker",
                    markers.map { it.marker_id }
                ),
                markers.associate { it.marker_id to it.confidence }
            )
            val areaCounts = buildVoteCounts(
                db.mapItemVoteDao().countByItemIds(
                    gid,
                    "area",
                    areas.map { it.area_id }
                ),
                areas.associate { it.area_id to it.confidence }
            )
            val profiles = db.userProfileDao().listByGid(gid)
            val ownerNames = profiles.associate { it.actor_did to it.display_name }
            val mapOwnerNameProjections = profiles.associate { profile ->
                profile.actor_did to MapOwnerNameProjection(groupNickname = profile.display_name)
            }
            val ownerAvatars = profiles.associate { it.actor_did to it.avatar_id }
            val actorDid = DeviceIdProvider.get(this)
            val markerDomainStates = if (markers.isEmpty()) emptyList() else {
                db.mapItemDomainStateDao().findByItemIds(gid, "marker", markers.map { it.marker_id })
            }
            val areaDomainStates = if (areas.isEmpty()) emptyList() else {
                db.mapItemDomainStateDao().findByItemIds(gid, "area", areas.map { it.area_id })
            }
            val actorRemoved = GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)
            val allowedMarkerDeletes = markerDomainStates.filterTo(mutableSetOf()) { state ->
                MapOwnerDeleteAvailabilityPolicy.canDelete(
                    state = MapOwnerDeleteAvailabilityPolicy.State(
                        ownerDid = state.owner_did,
                        lifecycleWireValue = state.situation_lifecycle,
                        expiresAtSeconds = state.expires_at,
                        ownerEditUntilSeconds = state.owner_edit_until,
                    ),
                    currentActorDid = actorDid,
                    nowSeconds = nowSeconds,
                    currentActorRemoved = actorRemoved,
                )
            }.mapTo(mutableSetOf()) { it.item_id }
            val allowedAreaDeletes = areaDomainStates.filterTo(mutableSetOf()) { state ->
                MapOwnerDeleteAvailabilityPolicy.canDelete(
                    state = MapOwnerDeleteAvailabilityPolicy.State(
                        ownerDid = state.owner_did,
                        lifecycleWireValue = state.situation_lifecycle,
                        expiresAtSeconds = state.expires_at,
                        ownerEditUntilSeconds = state.owner_edit_until,
                    ),
                    currentActorDid = actorDid,
                    nowSeconds = nowSeconds,
                    currentActorRemoved = actorRemoved,
                )
            }.mapTo(mutableSetOf()) { it.item_id }
            val trails = db.liveTrailDao().listTrails(gid, MAX_ITEMS)
            val visibleTrails = trails.filter { isLiveTrailVisible(it, actorDid) }
            runOnUiThread {
                if (requestGeneration != mapCandidateGeneration || requestGroupId != currentGid) {
                    return@runOnUiThread
                }
                if (isDrafting()) {
                    return@runOnUiThread
                }
                val previousStale = liveTrailStaleIds
                val nowMs = System.currentTimeMillis()
                val nextStale = visibleTrails
                    .filter { it.active == 1 && nowSeconds - it.updated_t >= LIVE_TRAIL_STALE_SEC }
                    .map { it.trail_id }
                    .toSet()
                visibleTrails.forEach { trail ->
                    if (trail.active != 1) return@forEach
                    val wasStale = previousStale.contains(trail.trail_id)
                    val isStale = nextStale.contains(trail.trail_id)
                    if (wasStale && !isStale) {
                        liveTrailReconnectedUntil[trail.trail_id] = nowMs + LIVE_TRAIL_RECONNECTED_MS
                    }
                }
                liveTrailReconnectedUntil.keys.toList().forEach { key ->
                    if (visibleTrails.none { it.trail_id == key } || liveTrailReconnectedUntil[key]!! <= nowMs) {
                        liveTrailReconnectedUntil.remove(key)
                    }
                }
                liveTrailStaleIds = nextStale
                maybeNotifyLiveTrailShares(gid, actorDid, visibleTrails)
                markerVoteCounts = markerCounts
                areaVoteCounts = areaCounts
                markerDeleteAllowedIds = allowedMarkerDeletes
                areaDeleteAllowedIds = allowedAreaDeletes
                mapOwnerNames = mapOwnerNameProjections
                listMarkers = sortByConsensusWeight(markers, markerCounts) { it.marker_id }
                listAreas = sortByConsensusWeight(areas, areaCounts) { it.area_id }
                mapMarkerCandidates = markerCandidates
                mapAreaCandidates = areaCandidates
                currentLiveTrails = visibleTrails
                liveTrailOwners = ownerNames
                liveTrailOwnerAvatars = ownerAvatars
                trailPointsById = if (existingSelectedTrailId != null && existingTrailPoints.isNotEmpty()) {
                    existingTrailPoints
                } else {
                    emptyMap()
                }
                updateMapUi(adapter)
                maybeFocusPendingExternalTrail(adapter)
                val requestedTrailId = pendingExternalTrailId
                if (!requestedTrailId.isNullOrBlank() && visibleTrails.any { it.trail_id == requestedTrailId }) {
                    pendingExternalTrailId = null
                    selectAndFocusTrail(requestedTrailId, adapter)
                }
            }
        }.start()
    }

    private fun updateMapUi(adapter: MapListAdapter) {
        adapter.submit(
            listMarkers,
            listAreas,
            currentLiveTrails,
            resources,
            showMarkers,
            showAreas,
            hiddenMarkers,
            hiddenAreas,
            selectedTrailId,
            markerVoteCounts,
            areaVoteCounts,
            mapOwnerNames,
            liveTrailOwners,
            liveTrailStaleIds,
            liveTrailReconnectedUntil,
            DeviceIdProvider.get(this),
            markerDeleteAllowedIds,
            areaDeleteAllowedIds,
        )
        if (!trailRenderSuspended) {
            renderTrailOverlays()
        }
        refreshSemanticMapRendering()
        val gid = mapRefreshGid
        if (gid != null && ::mapItemsList.isInitialized) {
            contribRefreshRunnable?.let { handler.removeCallbacks(it) }
            val r = Runnable {
                maybeRefreshContribPreviews(gid, adapter, mapItemsList, force = true)
            }
            contribRefreshRunnable = r
            handler.postDelayed(r, 500L)
        }
    }

    /** Remove stale A rows synchronously before B can receive any row callback. */
    private fun clearGroupScopedAdapterState(adapter: MapListAdapter) {
        adapter.submit(
            emptyList(), emptyList(), emptyList(), resources, showMarkers, showAreas,
            emptySet(), emptySet(), null,
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet(),
            emptyMap(), DeviceIdProvider.get(this), emptySet(), emptySet(),
        )
    }

    private fun buildVoteCounts(
        counts: List<app.peermesh.data.db.dao.ItemVoteCount>,
        ownerConfidence: Map<String, Int>
    ): Map<String, Pair<Int, Int>> {
        val map = mutableMapOf<String, Pair<Int, Int>>()
        counts.forEach {
            val (rep, obs) = map[it.item_id] ?: (0 to 0)
            if (it.confidence == 2) {
                map[it.item_id] = rep to (obs + it.cnt)
            } else {
                map[it.item_id] = (rep + it.cnt) to obs
            }
        }
        ownerConfidence.forEach { (id, conf) ->
            val (rep, obs) = map[id] ?: (0 to 0)
            if (conf == 2) {
                map[id] = rep to (obs + 1)
            } else {
                map[id] = (rep + 1) to obs
            }
        }
        return map
    }

    private fun isLiveTrailVisible(
        trail: app.peermesh.data.db.entity.LiveTrailEntity,
        actorDid: String,
    ): Boolean {
        val members = parseShareMembers(trail.share_to_json)
        return when (trail.share_scope) {
            LiveTrailShareManager.SCOPE_GROUP -> true
            LiveTrailShareManager.SCOPE_MEMBERS -> members.contains(actorDid)
            else -> true
        }
    }

    private fun parseShareMembers(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val element = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            val arr = element as? kotlinx.serialization.json.JsonArray ?: return emptySet()
            arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                .filter { it.isNotBlank() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun <T> sortByConsensusWeight(
        items: List<T>,
        counts: Map<String, Pair<Int, Int>>,
        idProvider: (T) -> String
    ): List<T> {
        return items.sortedWith(
            compareByDescending<T> {
                val (rep, obs) = counts[idProvider(it)] ?: (0 to 0)
                (obs * 2) + rep
            }.thenByDescending {
                when (it) {
                    is MapMarkerEntity -> it.updated_t
                    is MapAreaEntity -> it.updated_t
                    else -> 0L
                }
            }
        )
    }

    private fun initSemanticMapRendering() {
        val database = AppDatabaseProvider.get(applicationContext)
        mapItemNavigationCoordinator = DefaultMapItemNavigationCoordinatorFactory.create()
        mapVisualResourceResolver = AndroidMapVisualResourceResolver(this)
        mapRuntimeRenderer = OsmdroidMapRuntimeRenderer(
            mapView = mapView,
            registry = MapRuntimeRenderRegistry(),
            clickListener = MapRuntimeItemClickListener { identity ->
                requestPersistedMapItemDetail(identity)
            },
        )
        groupMapController = GroupMapRenderingFactory.createController(
            loadMapSceneItems = MapSceneReadFactory.create(database),
            backgroundExecutor = mapSceneExecutor,
            presentationExecutor = java.util.concurrent.Executor { command ->
                if (mapRenderingClosed) return@Executor
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    command.run()
                } else {
                    handler.post(command)
                }
            },
        )
        groupMapStateListener = GroupMapStateListener { state ->
            if (mapRenderingClosed) return@GroupMapStateListener
            state.renderSnapshot?.let { snapshot ->
                if (snapshot.groupId != currentGid) {
                    mapRuntimeRenderer.clear()
                    return@GroupMapStateListener
                }
                val resolved = try {
                    snapshot.items.map(mapVisualResourceResolver::resolve)
                } catch (_: Exception) {
                    showStatus(getString(R.string.map_render_refresh_failed))
                    return@GroupMapStateListener
                }
                mapRuntimeRenderer.render(resolved)
            }
            if (state.operation is GroupMapOperationState.Failed) {
                showStatus(getString(R.string.map_render_refresh_failed))
            }
        }
        groupMapController.addListener(groupMapStateListener)
    }

    private fun refreshSemanticMapRendering() {
        if (mapRenderingClosed || !::groupMapController.isInitialized || isDrafting()) return
        val viewport = mapView.boundingBox
        val identities = linkedSetOf<MapItemIdentity>()
        if (showMarkers) {
            mapMarkerCandidates.asSequence()
                .filter { it.marker_id !in hiddenMarkers }
                .filter { marker ->
                    viewport == null || isPointInViewport(GeoPoint(marker.lat, marker.lon), viewport)
                }
                .filter { it.gid == currentGid }
                .mapTo(identities) { marker ->
                    MapItemIdentity(marker.gid, MapItemType.MARKER, marker.marker_id)
                }
        }
        if (showAreas) {
            mapAreaCandidates.asSequence()
                .filter { it.area_id !in hiddenAreas }
                .filter { area ->
                    if (viewport == null) return@filter true
                    val points = candidateAreaPoints(area.geometry_json)
                    points.isNotEmpty() && intersectsViewport(BoundingBox.fromGeoPoints(points), viewport)
                }
                .filter { it.gid == currentGid }
                .mapTo(identities) { area ->
                    MapItemIdentity(area.gid, MapItemType.AREA, area.area_id)
                }
        }
        groupMapController.refresh(
            GroupMapViewportRequest(
                groupId = currentGid,
                identities = identities,
                environment = MapRenderEnvironment(
                    zoomBand = when {
                        mapView.zoomLevelDouble < MAP_SEMANTIC_MEDIUM_ZOOM -> MapZoomBand.FAR
                        mapView.zoomLevelDouble < MAP_SEMANTIC_NEAR_ZOOM -> MapZoomBand.MEDIUM
                        else -> MapZoomBand.NEAR
                    },
                    densityBand = when {
                        identities.size >= MAP_SEMANTIC_DENSE_COUNT -> MapDensityBand.DENSE
                        identities.size >= MAP_SEMANTIC_MODERATE_COUNT -> MapDensityBand.MODERATE
                        else -> MapDensityBand.SPARSE
                    },
                ),
            ),
        )
    }

    internal fun requestPersistedMapItemDetail(identity: MapItemIdentity) {
        if (!::mapItemNavigationCoordinator.isInitialized) return
        val activityActive = isForeground && !isFinishing && !isDestroyed
        val draftActive = isDrafting()
        when (
            val decision = mapItemNavigationCoordinator.beginItemClick(
                identity = identity,
                currentGroupId = currentGid,
                creationDraftActive = draftActive,
                activityActive = activityActive && !mapRenderingClosed,
            )
        ) {
            is MapItemNavigationDecision.Reserve -> {
                groupMapController.select(decision.identity)
                val exactSelectionAccepted =
                    groupMapController.currentState().selection.selectedIdentity == decision.identity
                if (!exactSelectionAccepted) {
                    mapItemNavigationCoordinator.markSelectionFailed(decision.identity)
                    return
                }
                when (val launch = mapItemNavigationCoordinator.markSelectionAccepted(decision.identity)) {
                    is MapItemNavigationDecision.Launch -> launchMapItemDetail(launch.identity)
                    is MapItemNavigationDecision.Reserve,
                    is MapItemNavigationDecision.Blocked,
                    MapItemNavigationDecision.IgnoreDuplicate,
                    -> Unit
                }
            }
            is MapItemNavigationDecision.Launch,
            is MapItemNavigationDecision.Blocked,
            MapItemNavigationDecision.IgnoreDuplicate,
            -> Unit
        }
    }

    private fun launchMapItemDetail(identity: MapItemIdentity) {
        try {
            startActivity(MapItemDetailActivity.intent(this, identity))
        } catch (_: Exception) {
            mapItemNavigationCoordinator.markLaunchFailed(identity)
            if (!mapRenderingClosed && !isFinishing && !isDestroyed) {
                showStatus(getString(R.string.map_detail_navigation_failed))
            }
            return
        }
        mapItemNavigationCoordinator.markLaunchSucceeded(identity)
    }

    private fun emojiForKind(kind: String?): String? {
        val label = MapKinds.labelForKey(kind) ?: return null
        val idx = label.indexOf(' ')
        return if (idx > 0) label.substring(0, idx) else label
    }

    private fun isRemovalRequested(requestedT: Long?, cancelT: Long?): Boolean {
        if (requestedT == null) return false
        return cancelT == null || cancelT < requestedT
    }

    private fun emojiDrawable(emoji: String): android.graphics.drawable.Drawable {
        return emojiIconCache.getOrPut(emoji) {
            val density = resources.displayMetrics.density
            val sizePx = (26f * density).toInt().coerceAtLeast(1)
            val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = sizePx.toFloat()
                textAlign = android.graphics.Paint.Align.LEFT
            }
            val bounds = android.graphics.Rect()
            paint.getTextBounds(emoji, 0, emoji.length, bounds)
            val width = (bounds.width() + sizePx / 4).coerceAtLeast(1)
            val height = (bounds.height() + sizePx / 4).coerceAtLeast(1)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val x = (width - bounds.width()) / 2f - bounds.left
            val y = (height + bounds.height()) / 2f - bounds.bottom
            canvas.drawText(emoji, x, y, paint)
            android.graphics.drawable.BitmapDrawable(resources, bitmap)
        }
    }

    private fun labelDrawable(text: String): android.graphics.drawable.Drawable {
        return labelIconCache.getOrPut(text) {
            val density = resources.displayMetrics.density
            val textSizePx = 12f * density
            val padding = (4f * density).toInt()
            val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = textSizePx
                textAlign = android.graphics.Paint.Align.LEFT
                color = android.graphics.Color.WHITE
            }
            val bounds = android.graphics.Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            val width = (bounds.width() + padding * 2).coerceAtLeast(1)
            val height = (bounds.height() + padding * 2).coerceAtLeast(1)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(180, 18, 22, 30)
            }
            val rect = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, 6f * density, 6f * density, bg)
            val x = padding.toFloat()
            val y = padding.toFloat() - bounds.top
            canvas.drawText(text, x, y, paint)
            android.graphics.drawable.BitmapDrawable(resources, bitmap)
        }
    }

    private fun labelText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val short = if (trimmed.length > 5) trimmed.take(5) else trimmed
        return short.ifBlank { null }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typed = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, typed, true)) {
            if (typed.resourceId != 0) ContextCompat.getColor(this, typed.resourceId) else typed.data
        } else {
            android.graphics.Color.WHITE
        }
    }

    private fun centroidFor(points: List<GeoPoint>): GeoPoint {
        var lat = 0.0
        var lon = 0.0
        points.forEach {
            lat += it.latitude
            lon += it.longitude
        }
        val count = points.size.coerceAtLeast(1)
        return GeoPoint(lat / count, lon / count)
    }

    private data class GeoBounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    private fun boundsFor(points: List<GeoPoint>): GeoBounds {
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        points.forEach {
            minLat = minOf(minLat, it.latitude)
            maxLat = maxOf(maxLat, it.latitude)
            minLon = minOf(minLon, it.longitude)
            maxLon = maxOf(maxLon, it.longitude)
        }
        return GeoBounds(minLat, maxLat, minLon, maxLon)
    }

    private fun boundsSizeMeters(bounds: GeoBounds): Float {
        val latMid = (bounds.minLat + bounds.maxLat) / 2.0
        val lonMid = (bounds.minLon + bounds.maxLon) / 2.0
        val result = FloatArray(1)
        android.location.Location.distanceBetween(
            bounds.minLat,
            lonMid,
            bounds.maxLat,
            lonMid,
            result
        )
        val height = result[0]
        android.location.Location.distanceBetween(
            latMid,
            bounds.minLon,
            latMid,
            bounds.maxLon,
            result
        )
        val width = result[0]
        return maxOf(width, height)
    }

    private fun boundsContains(outer: GeoBounds, inner: GeoBounds): Boolean {
        return outer.minLat <= inner.minLat &&
            outer.maxLat >= inner.maxLat &&
            outer.minLon <= inner.minLon &&
            outer.maxLon >= inner.maxLon
    }

    private fun boundsOverlap(a: GeoBounds, b: GeoBounds): Boolean {
        val latOverlap = a.minLat <= b.maxLat && a.maxLat >= b.minLat
        val lonOverlap = a.minLon <= b.maxLon && a.maxLon >= b.minLon
        return latOverlap && lonOverlap
    }

    private fun isSameTypeNearbyMarker(
        gid: String,
        kind: String,
        lat: Double,
        lon: Double,
        markerRadiusMeters: Float?
    ): Boolean {
        val actorDid = DeviceIdProvider.get(this)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val candidates = listMarkers.filter {
            val expiresAt = it.expires_at
            it.gid == gid &&
                it.actor_did == actorDid &&
                it.kind == kind &&
                it.status == ReducerConstants.STATUS_ACTIVE &&
                (expiresAt == null || expiresAt > nowSeconds)
        }
        val baseThreshold = markerRadiusMeters?.let {
            maxOf(MARKER_PROXIMITY_GUARD_M, it * MARKER_PROXIMITY_GUARD_RADIUS_RATIO)
                .coerceAtMost(MARKER_PROXIMITY_GUARD_MAX_M)
        } ?: MARKER_PROXIMITY_GUARD_M
        val result = FloatArray(1)
        for (marker in candidates) {
            android.location.Location.distanceBetween(lat, lon, marker.lat, marker.lon, result)
            val newRadius = markerRadiusMeters ?: DEFAULT_MARKER_RADIUS_M
            val existingRadius = marker.radius_m?.toFloat() ?: DEFAULT_MARKER_RADIUS_M
            val dist = result[0]
            val newContainsExisting = newRadius >= existingRadius + dist
            val existingContainsNew = existingRadius >= newRadius + dist
            if (newContainsExisting || existingContainsNew) continue
            if (result[0] <= baseThreshold) return true
        }
        return false
    }

    private fun isTypeCooldownNearbyMarker(
        gid: String,
        kind: String,
        lat: Double,
        lon: Double,
        markerRadiusMeters: Float?,
        nowSeconds: Long
    ): Boolean {
        val candidates = listMarkers.filter {
            val expiresAt = it.expires_at
            it.gid == gid &&
                it.kind == kind &&
                it.status == ReducerConstants.STATUS_ACTIVE &&
                (expiresAt == null || expiresAt > nowSeconds)
        }
        val threshold = markerRadiusMeters?.let {
            maxOf(MARKER_PROXIMITY_GUARD_M, it * MARKER_PROXIMITY_GUARD_RADIUS_RATIO)
                .coerceAtMost(MARKER_PROXIMITY_GUARD_MAX_M)
        } ?: MARKER_PROXIMITY_GUARD_M
        val result = FloatArray(1)
        for (marker in candidates) {
            val lastT = maxOf(marker.created_t, marker.updated_t)
            if (nowSeconds - lastT > MARKER_AREA_COOLDOWN_SEC) continue
            android.location.Location.distanceBetween(lat, lon, marker.lat, marker.lon, result)
            val newRadius = markerRadiusMeters ?: DEFAULT_MARKER_RADIUS_M
            val existingRadius = marker.radius_m?.toFloat() ?: DEFAULT_MARKER_RADIUS_M
            val dist = result[0]
            val newContainsExisting = newRadius >= existingRadius + dist
            val existingContainsNew = existingRadius >= newRadius + dist
            if (newContainsExisting || existingContainsNew) continue
            if (result[0] <= threshold) return true
        }
        return false
    }

    private fun isSameTypeNearbyArea(
        gid: String,
        kind: String,
        newPoints: List<GeoPoint>,
        newCentroid: GeoPoint
    ): Boolean {
        val actorDid = DeviceIdProvider.get(this)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val candidates = listAreas.filter {
            val expiresAt = it.expires_at
            it.gid == gid &&
                it.actor_did == actorDid &&
                it.kind == kind &&
                it.status == ReducerConstants.STATUS_ACTIVE &&
                (expiresAt == null || expiresAt > nowSeconds)
        }
        val newBounds = boundsFor(newPoints)
        val newSize = boundsSizeMeters(newBounds)
        val ratio = if (newSize >= AREA_PROXIMITY_GUARD_RATIO_PIVOT_M) {
            AREA_PROXIMITY_GUARD_RATIO_LARGE
        } else {
            AREA_PROXIMITY_GUARD_RATIO
        }
        val nearThreshold = maxOf(AREA_PROXIMITY_GUARD_M, newSize * ratio)
            .coerceAtMost(AREA_PROXIMITY_GUARD_MAX_M)
        val result = FloatArray(1)
        for (area in candidates) {
            val points = parsePolygonPoints(area.geometry_json)
            if (points.isEmpty()) continue
            val existingBounds = boundsFor(points)
            if (boundsContains(existingBounds, newBounds) || boundsContains(newBounds, existingBounds)) {
                continue
            }
            if (boundsOverlap(existingBounds, newBounds)) return true
            val center = centroidFor(points)
            android.location.Location.distanceBetween(
                newCentroid.latitude, newCentroid.longitude, center.latitude, center.longitude, result
            )
            if (result[0] <= nearThreshold) return true
        }
        return false
    }

    private fun isTypeCooldownNearbyArea(
        gid: String,
        kind: String,
        newPoints: List<GeoPoint>,
        newCentroid: GeoPoint,
        nowSeconds: Long
    ): Boolean {
        val candidates = listAreas.filter {
            val expiresAt = it.expires_at
            it.gid == gid &&
                it.kind == kind &&
                it.status == ReducerConstants.STATUS_ACTIVE &&
                (expiresAt == null || expiresAt > nowSeconds)
        }
        val newBounds = boundsFor(newPoints)
        val newSize = boundsSizeMeters(newBounds)
        val ratio = if (newSize >= AREA_PROXIMITY_GUARD_RATIO_PIVOT_M) {
            AREA_PROXIMITY_GUARD_RATIO_LARGE
        } else {
            AREA_PROXIMITY_GUARD_RATIO
        }
        val nearThreshold = maxOf(AREA_PROXIMITY_GUARD_M, newSize * ratio)
            .coerceAtMost(AREA_PROXIMITY_GUARD_MAX_M)
        val result = FloatArray(1)
        for (area in candidates) {
            val lastT = maxOf(area.created_t, area.updated_t)
            if (nowSeconds - lastT > MARKER_AREA_COOLDOWN_SEC) continue
            val points = parsePolygonPoints(area.geometry_json)
            if (points.isEmpty()) continue
            val existingBounds = boundsFor(points)
            if (boundsContains(existingBounds, newBounds) || boundsContains(newBounds, existingBounds)) {
                continue
            }
            if (boundsOverlap(existingBounds, newBounds)) return true
            val center = centroidFor(points)
            android.location.Location.distanceBetween(
                newCentroid.latitude, newCentroid.longitude, center.latitude, center.longitude, result
            )
            if (result[0] <= nearThreshold) return true
        }
        return false
    }


    private fun computeTrailDistance(points: List<app.peermesh.data.db.entity.TrailPointEntity>): Float {
        var total = 0f
        var lastLat = points.first().lat
        var lastLon = points.first().lon
        for (i in 1 until points.size) {
            val p = points[i]
            val result = FloatArray(1)
            android.location.Location.distanceBetween(lastLat, lastLon, p.lat, p.lon, result)
            total += result[0]
            lastLat = p.lat
            lastLon = p.lon
        }
        return total
    }

    private fun loadLiveTrailPointsAsync(
        trailId: String,
        onLoaded: (List<app.peermesh.data.db.entity.TrailPointEntity>) -> Unit
    ) {
        Thread {
            val testLoader = GroupMapActivityTestSeams.trailPointLoader
            val mapped = if (testLoader != null) {
                testLoader(trailId)
            } else {
                AppDatabaseProvider.get(this).liveTrailDao().listPoints(trailId).map {
                    app.peermesh.data.db.entity.TrailPointEntity(
                        tid = it.trail_id,
                        idx = it.idx,
                        lat = it.lat,
                        lon = it.lon,
                        t = it.t,
                        acc = it.acc,
                        spd = it.spd,
                        mm = it.mm,
                    )
                }
            }
            runOnUiThread {
                onLoaded(mapped)
                GroupMapActivityTestSeams.onTrailPointResultDelivered?.invoke()
            }
        }.start()
    }

    private fun focusTrail(trailId: String) {
        val points = trailPointsById[trailId].orEmpty()
        if (points.isEmpty()) {
            toast(getString(R.string.map_trail_missing))
            return
        }
        if (mapCollapseState == MAP_STATE_COLLAPSED) {
            pendingTrailFocusId = trailId
            centerTrailLastPoint(trailId)
            return
        }
        val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
        val box = BoundingBox.fromGeoPoints(geoPoints)
        mapView.zoomToBoundingBox(box, true)
    }

    private fun selectAndFocusTrail(trailId: String, adapter: MapListAdapter) {
        val scopeGroupId = currentGid
        val scopeGeneration = mapGroupScopeGeneration
        if (currentLiveTrails.none { it.gid == scopeGroupId && it.trail_id == trailId }) return
        cancelAllLiveTrailRetries()
        selectedTrailId = trailId

        clearTrailRenderCache()
        trailRenderCacheId = trailId

        updateMapUi(adapter)

        loadLiveTrailPointsAsync(trailId) { points ->
            if (!isCurrentTrailScope(scopeGroupId, scopeGeneration, trailId)) return@loadLiveTrailPointsAsync

            if (points.isEmpty()) {
                toast(getString(R.string.map_trail_missing))
                return@loadLiveTrailPointsAsync
            }

            trailPointsById = mapOf(trailId to points)
            clearTrailRenderCache()
            trailRenderCacheId = trailId
            rebuildTrailRenderCache()
            updateMapUi(adapter)
            focusTrail(trailId)
        }
    }

    private fun maybeFocusPendingExternalTrail(adapter: MapListAdapter) {
        val trailId = pendingExternalTrailId ?: return
        if (focusingExternalTrailId == trailId) return

        val trail = currentLiveTrails.firstOrNull { it.trail_id == trailId }
        if (trail == null) return
        val scopeGroupId = trail.gid
        val scopeGeneration = mapGroupScopeGeneration
        if (!isCurrentMapScope(scopeGroupId, scopeGeneration)) return

        focusingExternalTrailId = trailId
        pendingExternalTrailId = null

        selectedTrailId = trailId
        clearTrailRenderCache()
        trailRenderCacheId = trailId
        cancelAllLiveTrailRetries()

        updateMapUi(adapter)

        loadLiveTrailPointsAsync(trailId) { points ->
            if (!isCurrentTrailScope(scopeGroupId, scopeGeneration, trailId)) {
                if (isCurrentMapScope(scopeGroupId, scopeGeneration) &&
                    focusingExternalTrailId == trailId
                ) {
                    focusingExternalTrailId = null
                }
                return@loadLiveTrailPointsAsync
            }
            if (points.isEmpty()) {
                if (focusingExternalTrailId == trailId) focusingExternalTrailId = null
                toast(getString(R.string.map_trail_missing))
                return@loadLiveTrailPointsAsync
            }

            trailPointsById = mapOf(trailId to points)
            clearTrailRenderCache()
            trailRenderCacheId = trailId
            rebuildTrailRenderCache()
            updateMapUi(adapter)
            focusTrail(trailId)

            if (focusingExternalTrailId == trailId) focusingExternalTrailId = null
        }
    }

    private fun centerTrailLastPoint(trailId: String) {
        val points = trailPointsById[trailId].orEmpty()
        if (points.isEmpty()) return
        val last = points.last()
        mapView.controller.setCenter(GeoPoint(last.lat, last.lon))
    }

    private fun scheduleTrailRender() {
        val groupId = currentGid
        val scopeGeneration = mapGroupScopeGeneration
        trailRenderRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (!isCurrentMapScope(groupId, scopeGeneration)) return@Runnable
            trailRenderSuspended = false
            renderTrailOverlays()
            refreshSemanticMapRendering()
        }
        trailRenderRunnable = r
        handler.postDelayed(r, TRAIL_RENDER_DELAY_MS)
    }

    private fun renderTrailOverlays() {
        val hadTrail = trailOverlays.isNotEmpty()
        trailOverlays.forEach { mapView.overlays.remove(it) }
        trailOverlays.clear()
        val id = selectedTrailId ?: run {
            if (hadTrail) {
                trailOverlaysDirty = true
            }
            return
        }
        val zoomBucket = bucketZoom(mapView.zoomLevelDouble)
        val key = TrailRenderKey(trailId = id, zoomBucket = zoomBucket)
        val cached = trailRenderCache[key] ?: run {
            rebuildTrailRenderCache()
            trailRenderCache[key] ?: return
        }
        val viewport = mapView.boundingBox ?: return
        val filtered = filterSegmentsByViewport(cached, viewport)
        filtered.forEach { segment ->
            val line = Polyline().apply {
                setPoints(segment.points)
                outlinePaint.strokeWidth = TRAIL_LINE_WIDTH
                outlinePaint.color = segment.color
                if (segment.dashed) {
                    outlinePaint.pathEffect = android.graphics.DashPathEffect(
                        floatArrayOf(TRAIL_JUMP_DASH_ON_PX, TRAIL_JUMP_DASH_OFF_PX),
                        0f
                    )
                }
            }
            trailOverlays.add(line)
        }
        val selectedTrail = currentLiveTrails.firstOrNull { it.trail_id == id }
        val points = trailPointsById[id].orEmpty()
        if (points.isNotEmpty()) {
            trailOverlays.add(LiveTrailPointDotsOverlay(points))
        }
        val start = cached.firstOrNull { it.isEndpointStart }?.endpoints
        val end = cached.firstOrNull { it.isEndpointEnd }?.endpoints
        val isActive = selectedTrail?.active == 1
        val showStart = points.size >= 2 && (selectedTrail == null || isActive || selectedTrail.active == 0)
        if (showStart) {
            start?.firstOrNull()?.let { pt ->
                trailOverlays.add(
                    Marker(mapView).apply {
                        position = pt
                        icon = trailEndpointDrawable(isStart = true)
                        setAnchor(Marker.ANCHOR_LEFT, Marker.ANCHOR_BOTTOM)
                        isFlat = true
                    }
                )
            }
        }
        if (!isActive) {
            end?.firstOrNull()?.let { pt ->
                trailOverlays.add(
                    Marker(mapView).apply {
                        position = pt
                        icon = trailEndpointDrawable(isStart = false)
                        setAnchor(Marker.ANCHOR_LEFT, Marker.ANCHOR_BOTTOM)
                        isFlat = true
                    }
                )
            }
        }
        if (isActive && points.isNotEmpty()) {
            val lastPoint = points.last()
            val avatar = liveTrailOwnerAvatars[selectedTrail?.owner_did]
            val name = selectedTrail?.owner_did?.let { liveTrailOwners[it] } ?: selectedTrail?.name ?: "Live"
            val avatarText = avatar?.let { AvatarUtils.avatarEmojiForId(this, it) } ?: AvatarUtils.avatarInitials(name)
            val avatarColor = AvatarUtils.avatarColorForKey(selectedTrail?.owner_did ?: name)
            val pin = liveTrailAvatarDrawable(avatarText, avatarColor)
            val pinMarker = Marker(mapView).apply {
                position = GeoPoint(lastPoint.lat, lastPoint.lon)
                icon = pin
                setAnchor(Marker.ANCHOR_CENTER, 1f)
                isFlat = true
            }
            trailOverlays.add(pinMarker)
            val labelMarker = Marker(mapView).apply {
                position = GeoPoint(lastPoint.lat, lastPoint.lon)
                icon = labelDrawable(name).apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) }
                setAnchor(Marker.ANCHOR_CENTER, 3.2f)
                isFlat = true
            }
            trailOverlays.add(labelMarker)
        }
        trailOverlays.forEach { overlay ->
            if (!mapView.overlays.contains(overlay)) {
                mapView.overlays.add(overlay)
            }
        }
        if (hadTrail || trailOverlays.isNotEmpty()) {
            trailOverlaysDirty = true
        }
    }

    private fun clearTrailRenderCache() {
        trailRenderCache.clear()
        trailOverlays.forEach { mapView.overlays.remove(it) }
        trailOverlays.clear()
    }

    private data class TrailRenderKey(
        val trailId: String,
        val zoomBucket: Int,
    )

    private fun bucketZoom(zoom: Double): Int {
        return kotlin.math.floor(zoom * 2.0).toInt()
    }

    private fun rebuildTrailRenderCache() {
        val id = selectedTrailId ?: return
        val points = trailPointsById[id].orEmpty()
        if (points.isEmpty()) return
        val zoom = mapView.zoomLevelDouble
        val key = TrailRenderKey(
            trailId = id,
            zoomBucket = bucketZoom(zoom),
        )
        trailRenderCache.remove(key)
        val segments = buildTrailSegments(points, zoom, null)
        val start = points.first()
        val end = points.last()
        val startPoint = GeoPoint(start.lat, start.lon)
        val endPoint = GeoPoint(end.lat, end.lon)
        val built = segments.map { segment ->
            val box = BoundingBox.fromGeoPoints(segment.points)
            TrailRenderSegment(segment.points, segment.color, box, dashed = segment.dashed)
        }.toMutableList()
        built.add(
            TrailRenderSegment(
                points = listOf(startPoint),
                color = TRAIL_START_COLOR,
                bbox = BoundingBox.fromGeoPoints(listOf(startPoint)),
                isEndpointStart = true,
                endpoints = listOf(startPoint),
            )
        )
        built.add(
            TrailRenderSegment(
                points = listOf(endPoint),
                color = TRAIL_END_COLOR,
                bbox = BoundingBox.fromGeoPoints(listOf(endPoint)),
                isEndpointEnd = true,
                endpoints = listOf(endPoint),
            )
        )
        trailRenderCache[key] = built
    }

    private fun scheduleLiveTrailRefresh(gid: String) {
        val scopeGeneration = mapGroupScopeGeneration
        val now = android.os.SystemClock.elapsedRealtime()
        val earliest = liveTrailLastRefreshAt + LIVE_TRAIL_REFRESH_DEBOUNCE_MS
        val scheduled = liveTrailRefreshScheduledAt
        val target = maxOf(now, earliest)
        if (scheduled != 0L && scheduled <= target) return
        liveTrailRefreshRunnable?.let { handler.removeCallbacks(it) }
        val delay = (target - now).coerceAtLeast(0L)
        val rv = findViewById<RecyclerView>(R.id.map_items_list)
        val adapter = rv.adapter as? MapListAdapter ?: return
        val r = Runnable {
            if (!isCurrentMapScope(gid, scopeGeneration)) return@Runnable
            liveTrailLastRefreshAt = android.os.SystemClock.elapsedRealtime()
            liveTrailRefreshScheduledAt = 0L
            loadMapItems(gid, adapter)
            val trailId = selectedTrailId
            if (trailId != null &&
                currentLiveTrails.any { it.gid == gid && it.trail_id == trailId }
            ) {
                loadLiveTrailPointsAsync(trailId) { points ->
                    if (!isCurrentTrailScope(gid, scopeGeneration, trailId)) return@loadLiveTrailPointsAsync
                    if (points.isEmpty()) return@loadLiveTrailPointsAsync
                    trailPointsById = trailPointsById + (trailId to points)
                    clearTrailRenderCache()
                    rebuildTrailRenderCache()
                    renderTrailOverlays()
                    centerTrailLastPoint(trailId)
                }
            }
        }
        liveTrailRefreshRunnable = r
        liveTrailRefreshScheduledAt = now + delay
        handler.postDelayed(r, delay)
    }

    private data class TrailRenderSegment(
        val points: List<GeoPoint>,
        val color: Int,
        val bbox: BoundingBox,
        val isEndpointStart: Boolean = false,
        val isEndpointEnd: Boolean = false,
        val endpoints: List<GeoPoint> = emptyList(),
        val dashed: Boolean = false,
    )

    private fun filterSegmentsByViewport(
        segments: List<TrailRenderSegment>,
        viewport: BoundingBox,
    ): List<TrailRenderSegment> {
        val padLat = (viewport.latNorth - viewport.latSouth) * TRAIL_VIEWPORT_PAD_RATIO
        val padLon = (viewport.lonEast - viewport.lonWest) * TRAIL_VIEWPORT_PAD_RATIO
        val minLat = viewport.latSouth - padLat
        val maxLat = viewport.latNorth + padLat
        val minLon = viewport.lonWest - padLon
        val maxLon = viewport.lonEast + padLon
        return segments.filter { seg ->
            val box = seg.bbox
            box.latNorth >= minLat && box.latSouth <= maxLat &&
                box.lonEast >= minLon && box.lonWest <= maxLon
        }
    }

    private fun paddedViewport(viewport: BoundingBox): BoundingBox {
        val padLat = (viewport.latNorth - viewport.latSouth) * MAP_VIEWPORT_PAD_RATIO
        val padLon = (viewport.lonEast - viewport.lonWest) * MAP_VIEWPORT_PAD_RATIO
        return BoundingBox(
            viewport.latNorth + padLat,
            viewport.lonEast + padLon,
            viewport.latSouth - padLat,
            viewport.lonWest - padLon,
        )
    }

    private fun intersectsViewport(box: BoundingBox, viewport: BoundingBox): Boolean {
        val padded = paddedViewport(viewport)
        return box.latNorth >= padded.latSouth &&
            box.latSouth <= padded.latNorth &&
            box.lonEast >= padded.lonWest &&
            box.lonWest <= padded.lonEast
    }

    private fun isPointInViewport(point: GeoPoint, viewport: BoundingBox): Boolean {
        val padded = paddedViewport(viewport)
        return point.latitude <= padded.latNorth &&
            point.latitude >= padded.latSouth &&
            point.longitude <= padded.lonEast &&
            point.longitude >= padded.lonWest
    }

    private fun cachedAreaPoints(areaId: String, geometryJson: String): List<GeoPoint> {
        val cached = areaPointsCache[areaId]
        if (cached != null && cached.geometryJson == geometryJson) {
            return cached.points
        }
        val points = parsePolygonPoints(geometryJson)
        areaPointsCache[areaId] = CachedAreaPoints(geometryJson, points)
        trimAreaPointsCache()
        return points
    }

    private fun trimAreaPointsCache() {
        while (areaPointsCache.size > MAX_AREA_CACHE_ITEMS) {
            val eldestKey = areaPointsCache.entries.iterator().next().key
            areaPointsCache.remove(eldestKey)
        }
    }

    private data class TrailSegment(
        val points: List<GeoPoint>,
        val color: Int,
        val dashed: Boolean = false,
    )

    private fun buildTrailSegments(
        points: List<app.peermesh.data.db.entity.TrailPointEntity>,
        zoom: Double,
        viewport: BoundingBox?,
    ): List<TrailSegment> {
        if (points.size < 2) return emptyList()
        val stride = when {
            zoom < TRAIL_DOWNSAMPLE_ZOOM_FAR -> TRAIL_DOWNSAMPLE_STEP_FAR
            zoom < TRAIL_DOWNSAMPLE_ZOOM_MED -> TRAIL_DOWNSAMPLE_STEP_MED
            else -> 1
        }
        val gapIndices = HashSet<Int>()
        for (i in 1 until points.size) {
            val dt = points[i].t - points[i - 1].t
            val gapThreshold = TRAIL_GAP_SAVER_SECONDS
            if (dt >= gapThreshold) {
                gapIndices.add(i)
                continue
            }
            val spd = points[i - 1].spd
            val actual = distanceBetween(points[i - 1], points[i])
            val segSpeed = actual / dt.toFloat()
            val isStartSegment = i == 1
            if (isStartSegment && (spd == null || spd <= 0f) && segSpeed > TRAIL_WALK_MAX_MPS) {
                gapIndices.add(i)
                continue
            }
            val expectedIntervalMs = expectedGapIntervalMs()
            val skipCount = (dt * 1000f / expectedIntervalMs).toInt()
            if (dt >= TRAIL_GAP_SKIP_MIN_SECONDS && skipCount >= TRAIL_GAP_SKIP_MIN_COUNT) {
                gapIndices.add(i)
                continue
            }
            if (spd != null && spd > TRAIL_GAP_MIN_SPEED_MPS && dt >= TRAIL_GAP_MIN_DT_S) {
                val expected = spd * dt
                if (segSpeed > TRAIL_WALK_MAX_MPS && actual > expected * TRAIL_GAP_EXPECTED_MULT) {
                    gapIndices.add(i)
                }
            }
        }
        val sampled = if (stride <= 1) {
            points.mapIndexed { index, point -> index to point }
        } else {
            val list = ArrayList<Pair<Int, app.peermesh.data.db.entity.TrailPointEntity>>()
            var i = 0
            while (i < points.size) {
                list.add(i to points[i])
                i += stride
            }
            if (list.last().first != points.lastIndex) {
                list.add(points.lastIndex to points.last())
            }
            list
        }
        val segments = ArrayList<TrailSegment>()
        var currentColor: Int? = null
        var currentPoints = ArrayList<GeoPoint>()
        for (i in 1 until sampled.size) {
            val (prevIndex, prev) = sampled[i - 1]
            val (nextIndex, next) = sampled[i]
            if (gapIndices.contains(nextIndex)) {
                if (currentPoints.size >= 2 && currentColor != null) {
                    segments.add(TrailSegment(currentPoints, currentColor!!))
                }
                segments.add(
                    TrailSegment(
                        points = listOf(GeoPoint(prev.lat, prev.lon), GeoPoint(next.lat, next.lon)),
                        color = TRAIL_COLOR_JUMP,
                        dashed = true,
                    )
                )
                currentColor = null
                currentPoints = ArrayList()
                continue
            }
            val color = colorForSpeed(prev, prevIndex, points)
            if (currentColor == null || color != currentColor) {
                if (currentPoints.size >= 2) {
                    segments.add(TrailSegment(currentPoints, currentColor!!))
                }
                currentColor = color
                currentPoints = ArrayList()
                currentPoints.add(GeoPoint(prev.lat, prev.lon))
            }
            currentPoints.add(GeoPoint(next.lat, next.lon))
        }
        if (currentPoints.size >= 2 && currentColor != null) {
            segments.add(TrailSegment(currentPoints, currentColor!!))
        }
        return segments
    }

    private fun distanceBetween(
        prev: app.peermesh.data.db.entity.TrailPointEntity,
        next: app.peermesh.data.db.entity.TrailPointEntity,
    ): Float {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(prev.lat, prev.lon, next.lat, next.lon, result)
        return result[0]
    }

    private fun expectedGapIntervalMs(): Long {
        return LOCATION_UPDATE_MS
    }

    private fun trailEndpointDrawable(isStart: Boolean): android.graphics.drawable.Drawable {
        val emoji = if (isStart) "🚩" else "🏁"
        val cache = if (isStart) trailEndpointStartCache else trailEndpointEndCache
        return cache.getOrPut(emoji) {
            val density = resources.displayMetrics.density
            val sizePx = (26f * density).toInt().coerceAtLeast(1)
            val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = sizePx * 0.9f
            }
            val x = sizePx / 2f
            val y = sizePx / 2f - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(emoji, x, y, paint)
            android.graphics.drawable.BitmapDrawable(resources, bitmap)
        }
    }

    private fun liveTrailAvatarDrawable(
        text: String,
        color: Int,
    ): android.graphics.drawable.Drawable {
        val key = "$text|$color"
        return emojiIconCache.getOrPut(key) {
            val size = (resources.displayMetrics.density * 36).toInt().coerceAtLeast(36)
            val pointerHeight = (size * 0.25f).toInt()
            val width = size
            val height = size + pointerHeight
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.FILL
                this.color = color
            }
            val cx = width / 2f
            val cy = size / 2f
            canvas.drawCircle(cx, cy, size / 2f, paint)
            val path = android.graphics.Path().apply {
                moveTo(cx, height.toFloat())
                lineTo(cx - size * 0.18f, size * 0.78f)
                lineTo(cx + size * 0.18f, size * 0.78f)
                close()
            }
            canvas.drawPath(path, paint)
            val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = AvatarUtils.avatarTextColor(color)
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = size * 0.5f
            }
            val bounds = android.graphics.Rect()
            textPaint.getTextBounds(text, 0, text.length, bounds)
            canvas.drawText(text, cx, cy + bounds.height() / 2f, textPaint)
            android.graphics.drawable.BitmapDrawable(resources, bitmap)
        }
    }


    private fun handleTrailPointTap(p: GeoPoint): Boolean {
        val trailId = selectedTrailId ?: return false
        val points = trailPointsById[trailId] ?: return false
        if (points.isEmpty()) return false
        val projection = mapView.projection ?: return false
        val tapPx = projection.toPixels(p, null)
        val thresholdPx = (24f * resources.displayMetrics.density).toInt()
        var bestIndex = -1
        var bestDistSq = Int.MAX_VALUE
        for (i in points.indices) {
            val pt = points[i]
            val ptPx = projection.toPixels(GeoPoint(pt.lat, pt.lon), null)
            val dx = ptPx.x - tapPx.x
            val dy = ptPx.y - tapPx.y
            val distSq = dx * dx + dy * dy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestIndex = i
            }
        }
        if (bestIndex == -1) return false
        if (bestDistSq > thresholdPx * thresholdPx) return false
        showTrailPointInfo(points, bestIndex)
        return true
    }

    private fun showTrailPointInfo(
        points: List<app.peermesh.data.db.entity.TrailPointEntity>,
        index: Int,
    ) {
        val point = points[index]
        val geo = GeoPoint(point.lat, point.lon)
        val icon = trailPointDrawable(colorForSpeed(point, index, points))
        val marker = trailPointMarker ?: Marker(mapView).also { trailPointMarker = it }
        marker.position = geo
        marker.icon = icon
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.isFlat = true
        marker.title = getString(R.string.trail_point_title)
        marker.snippet = buildTrailPointSnippet(point, index, points)
        if (!mapView.overlays.contains(marker)) {
            mapView.overlays.add(marker)
        }
        marker.showInfoWindow()
        trailPointHideRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { hideTrailPointInfo() }
        trailPointHideRunnable = r
        handler.postDelayed(r, TRAIL_POINT_HIDE_MS)
        mapView.invalidate()
    }

    private fun hideTrailPointInfo() {
        trailPointHideRunnable?.let { handler.removeCallbacks(it) }
        trailPointHideRunnable = null
        trailPointMarker?.let { marker ->
            marker.closeInfoWindow()
            mapView.overlays.remove(marker)
        }
        mapView.invalidate()
    }

    private fun trailPointDrawable(color: Int): android.graphics.drawable.Drawable {
        return trailPointIconCache.getOrPut(color) {
            val density = resources.displayMetrics.density
            val sizePx = (10f * density).toInt().coerceAtLeast(1)
            val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
            }
            val radius = sizePx / 2f
            canvas.drawCircle(radius, radius, radius, paint)
            android.graphics.drawable.BitmapDrawable(resources, bitmap)
        }
    }

    private inner class LiveTrailPointDotsOverlay(
        private val trailPoints: List<app.peermesh.data.db.entity.TrailPointEntity>,
    ) : Overlay() {
        private val density = resources.displayMetrics.density
        private val radiusPx = 3.5f * density
        private val outlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.argb(230, 8, 16, 41)
        }
        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val screenPoint = android.graphics.Point()

        override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val projection = mapView.projection ?: return
            val viewport = mapView.boundingBox
            trailPoints.forEachIndexed { index, point ->
                if (viewport != null && !viewport.contains(point.lat, point.lon)) {
                    return@forEachIndexed
                }
                projection.toPixels(GeoPoint(point.lat, point.lon), screenPoint)
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radiusPx + density, outlinePaint)
                fillPaint.color = colorForSpeed(point, index, trailPoints)
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radiusPx, fillPaint)
            }
        }
    }

    private fun colorForSpeed(
        point: app.peermesh.data.db.entity.TrailPointEntity,
        index: Int,
        points: List<app.peermesh.data.db.entity.TrailPointEntity>,
    ): Int {
        val speed = point.spd ?: run {
            if (index <= 0) return@run 0f
            val prev = points[index - 1]
            val dt = (point.t - prev.t).coerceAtLeast(1L).toFloat()
            val result = FloatArray(1)
            android.location.Location.distanceBetween(prev.lat, prev.lon, point.lat, point.lon, result)
            result[0] / dt
        }
        return when {
            speed <= TRAIL_STOP_MAX_MPS -> TRAIL_COLOR_STOP
            speed <= TRAIL_WALK_MAX_MPS -> TRAIL_COLOR_WALK
            speed <= TRAIL_MOTO_MAX_MPS -> TRAIL_COLOR_MOTO
            speed <= HIGH_SPEED_MIN_MPS -> TRAIL_COLOR_CAR
            else -> TRAIL_COLOR_HIGH
        }
    }

    private fun buildTrailPointSnippet(
        point: app.peermesh.data.db.entity.TrailPointEntity,
        index: Int,
        points: List<app.peermesh.data.db.entity.TrailPointEntity>,
    ): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(point.t * 1000L))
        val spd = point.spd ?: run {
            if (index <= 0) return@run 0f
            val prev = points[index - 1]
            val dt = (point.t - prev.t).coerceAtLeast(1L).toFloat()
            val result = FloatArray(1)
            android.location.Location.distanceBetween(prev.lat, prev.lon, point.lat, point.lon, result)
            result[0] / dt
        }
        val kmh = spd * 3.6f
        val acc = point.acc?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "--"
        return buildString {
            append("t=").append(time)
            append("\nlat=").append(String.format(java.util.Locale.US, "%.6f", point.lat))
            append("\nlon=").append(String.format(java.util.Locale.US, "%.6f", point.lon))
            append("\nacc=").append(acc).append(" m")
            append("\nspd=").append(String.format(java.util.Locale.US, "%.2f", spd))
                .append(" m/s (")
                .append(String.format(java.util.Locale.US, "%.1f", kmh))
                .append(" km/h)")
            append("\nspdm=").append(speedModeLabel(point.mm))
        }
    }

    private fun speedModeLabel(mode: Int?): String {
        return when (mode) {
            0 -> "idle"
            1 -> "walking"
            2 -> "moto"
            3 -> "car"
            4 -> "high"
            else -> "unknown"
        }
    }

    // ----------------------------
    // Draft mode UI + actions
    // ----------------------------

    private fun setFreeUi() {
        inputMode = InputMode.FREE
        actionBar.visibility = android.view.View.GONE
        doneBtn.isEnabled = false
        // status is not always shown in FREE (only temporary hint)
    }

    private fun applyMapCollapse() {
        val mapParams = mapFrame.layoutParams as android.widget.LinearLayout.LayoutParams
        val listParams = mapItemsList.layoutParams as android.widget.LinearLayout.LayoutParams
        val startMap = mapParams.weight
        val startList = listParams.weight
        val endMap = when (mapCollapseState) {
            MAP_STATE_EXPANDED -> MAP_WEIGHT_EXPANDED
            MAP_STATE_COLLAPSED -> MAP_WEIGHT_COLLAPSED
            else -> MAP_WEIGHT_MAX
        }
        val endList = when (mapCollapseState) {
            MAP_STATE_EXPANDED -> LIST_WEIGHT_EXPANDED
            MAP_STATE_COLLAPSED -> LIST_WEIGHT_COLLAPSED
            else -> LIST_WEIGHT_MAX
        }
        val showList = mapCollapseState != MAP_STATE_MAX
        mapCollapseHandle.contentDescription = if (mapCollapseState == MAP_STATE_EXPANDED) {
            getString(R.string.map_collapse)
        } else {
            getString(R.string.map_expand)
        }
        if (mapFrame.isLaidOut && mapItemsList.isLaidOut) {
            val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220L
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    val t = animator.animatedValue as Float
                    mapParams.weight = startMap + (endMap - startMap) * t
                    listParams.weight = startList + (endList - startList) * t
                    mapFrame.layoutParams = mapParams
                    mapItemsList.layoutParams = listParams
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: android.animation.Animator) {
                        if (showList) {
                            mapItemsList.visibility = android.view.View.VISIBLE
                        }
                    }

                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        mapItemsList.visibility = if (showList) {
                            android.view.View.VISIBLE
                        } else {
                            android.view.View.GONE
                        }
                        handleViewportChanged()
                        handlePendingTrailFocus()
                    }
                })
            }
            anim.start()
        } else {
            mapParams.weight = endMap
            listParams.weight = endList
            mapFrame.layoutParams = mapParams
            mapItemsList.layoutParams = listParams
            mapItemsList.visibility = if (showList) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            handleViewportChanged()
            handlePendingTrailFocus()
        }
    }

    private fun handlePendingTrailFocus() {
        if (mapCollapseState == MAP_STATE_COLLAPSED) return
        val id = pendingTrailFocusId ?: return
        pendingTrailFocusId = null
        focusTrail(id)
    }

    private fun handleViewportChanged() {
        mapView.post {
            if (isDrafting()) return@post
            trailRenderSuspended = true
            scheduleTrailRender()
        }
    }

    private fun initMapCreation(groupId: String) {
        mapCreationController = MapCreationFactory.createController(applicationContext, groupId)
        mapCreationDraftRenderer = OsmdroidMapCreationDraftRenderer(mapView) {
                index,
                latitude,
                longitude,
                finished,
            ->
            val points = creationAreaPoints().toMutableList()
            if (index !in points.indices) return@OsmdroidMapCreationDraftRenderer
            points[index] = GeoPoint(latitude, longitude)
            updateCreationArea(points)
            rebuildAreaDraftOverlays(showToast = finished)
            mapView.invalidate()
        }
        mapCreationStateListener = MapCreationStateListener { state ->
            mapCreationDraftRenderer.render(state.draft)
            val feedback = state.feedback as? MapCreationFeedback.Submission ?: return@MapCreationStateListener
            val pending = pendingCreationUi
            if (pending?.submissionId != feedback.submissionId) {
                mapCreationPendingFeedbackCoordinator.clearIfMatches(feedback.submissionId)
                mapCreationController.consumeSubmissionFeedback(feedback.submissionId)
                return@MapCreationStateListener
            }
            if (mapCreationPendingFeedbackCoordinator.resolve(feedback.submissionId) !=
                MapCreationPendingFeedbackDecision.Apply
            ) {
                mapCreationController.consumeSubmissionFeedback(feedback.submissionId)
                return@MapCreationStateListener
            }
            pendingCreationUi = null
            if (isCurrentMapScope(pending.groupId, pending.scopeGeneration) && pending.dialog.isShowing) {
                pending.button.isEnabled = true
                when (val result = feedback.result) {
                    is MapCreationSubmissionResult.Accepted -> {
                        MapCreationDeliveryFeedbackMapper.messageFor(result.delivery)?.let { message ->
                            toast(getString(message))
                        }
                        finishFlow(pending.dialog, pending.onFinished)
                        refreshMapUiNow(pending.groupId, resetTimer = true)
                    }
                    is MapCreationSubmissionResult.AlreadyRecorded -> {
                        finishFlow(pending.dialog, pending.onFinished)
                        refreshMapUiNow(pending.groupId, resetTimer = true)
                    }
                    is MapCreationSubmissionResult.InvalidInput,
                    is MapCreationSubmissionResult.Rejected,
                    is MapCreationSubmissionResult.Failed,
                    -> toast(getString(R.string.map_event_failed))
                }
            }
            mapCreationController.consumeSubmissionFeedback(feedback.submissionId)
        }
        mapCreationController.addListener(mapCreationStateListener)
    }

    private fun startMarkerDraft(p: GeoPoint) {
        pauseMapRefreshTimer()
        inputMode = InputMode.MARKER_DRAFT
        actionBar.visibility = android.view.View.VISIBLE
        doneBtn.isEnabled = true

        showStatus(getString(R.string.map_hint_marker_draft))
        mapCreationController.begin(currentGid, MapItemCategory.SITUATION, MapCreationShape.MARKER)
        mapCreationController.updateMarker(p.latitude, p.longitude, null)
    }

    private fun moveMarkerDraft(p: GeoPoint) {
        mapCreationController.updateMarker(p.latitude, p.longitude, null)
    }

    private fun startAreaDraft(p: GeoPoint) {
        pauseMapRefreshTimer()
        inputMode = InputMode.AREA_DRAFT
        actionBar.visibility = android.view.View.VISIBLE
        doneBtn.isEnabled = false

        mapCreationController.begin(currentGid, MapItemCategory.SITUATION, MapCreationShape.AREA)
        updateCreationArea(listOf(p))
        draftAreaInvalidMessage = null

        showStatus(getString(R.string.map_hint_area_draft_1))
        rebuildAreaDraftOverlays()
        ensureDraftOverlaysAttached()
        mapView.invalidate()
    }

    private fun addAreaPoint(p: GeoPoint) {
        if (inputMode != InputMode.AREA_DRAFT) return
        val points = creationAreaPoints() + p
        updateCreationArea(points)
        rebuildAreaDraftOverlays()

        doneBtn.isEnabled = points.size >= 3
        showStatus(
            if (points.size >= 3) getString(R.string.map_hint_area_draft_ready)
            else getString(R.string.map_hint_area_draft_1)
        )
        ensureDraftOverlaysAttached()
        mapView.invalidate()
    }

    private fun updateCreationArea(points: List<GeoPoint>) {
        mapCreationController.updateArea(
            MapSceneAreaGeometry.Polygon(
                listOf(points.map { point -> MapSceneCoordinate(point.latitude, point.longitude) }),
            ),
        )
    }

    private fun creationAreaPoints(): List<GeoPoint> {
        val geometry = mapCreationController.currentState().draft?.geometry
            as? app.peermesh.map.presentation.creation.MapCreationDraftGeometry.Area
            ?: return emptyList()
        val ring = when (val value = geometry.geometry) {
            is MapSceneAreaGeometry.Polygon -> value.rings.firstOrNull().orEmpty()
            is MapSceneAreaGeometry.MultiPolygon -> value.polygons.firstOrNull()?.firstOrNull().orEmpty()
        }
        return ring.map { GeoPoint(it.latitude, it.longitude) }
    }

    private fun rebuildAreaDraftOverlays() {
        val points = creationAreaPoints()
        draftAreaInvalidMessage =
            if (points.size >= 3) validateAreaConstraints(points) else null
        mapCreationDraftRenderer.render(mapCreationController.currentState().draft)
    }

    private fun rebuildAreaDraftOverlays(showToast: Boolean) {
        rebuildAreaDraftOverlays()
        if (showToast) {
            draftAreaInvalidMessage?.let { toastWithCooldown(it) }
        }
    }

    private fun toastWithCooldown(message: String) {
        val now = System.currentTimeMillis()
        val last = draftAreaToastCooldown[message] ?: 0L
        if (now - last < DRAFT_AREA_TOAST_COOLDOWN_MS) return
        draftAreaToastCooldown[message] = now
        toast(message)
    }

    private fun resetMapUiStateIfActorChanged() {
        val prefs = getSharedPreferences("ui_actor_guard", MODE_PRIVATE)
        val key = "map_last_actor"
        val current = DeviceIdProvider.get(this)
        val last = prefs.getString(key, null)
        if (last != null && last != current) {
            hiddenMarkers.clear()
            hiddenAreas.clear()
            selectedTrailId = null
            listMarkers = emptyList()
            listAreas = emptyList()
            markerDeleteAllowedIds = emptySet()
            areaDeleteAllowedIds = emptySet()
            currentLiveTrails = emptyList()
            liveTrailStaleIds = emptySet()
            liveTrailReconnectedUntil.clear()
            trailPointsById = emptyMap()
            cancelDraft()
            if (::groupMapController.isInitialized) {
                groupMapController.select(null)
                groupMapController.focus(null)
            }
            if (::mapRuntimeRenderer.isInitialized) mapRuntimeRenderer.clear()
            labelIconCache.clear()
            emojiIconCache.clear()
            trailEndpointStartCache.clear()
            trailEndpointEndCache.clear()
            areaPointsCache.clear()
            clearTrailRenderCache()
            mapView.invalidate()
        }
        prefs.edit().putString(key, current).apply()
    }

    @Suppress("MissingPermission")
    private fun maybeNotifyLiveTrailShares(
        gid: String,
        actorDid: String,
        trails: List<app.peermesh.data.db.entity.LiveTrailEntity>,
    ) {
        if (isForeground) return
        if (!canPostNotifications()) return
        val incoming = trails.filter { it.owner_did != actorDid }
        if (incoming.isEmpty()) return
        val prefs = getSharedPreferences("live_trail_pending_notify", MODE_PRIVATE)
        val manager = NotificationManagerCompat.from(this)
        ensureLiveTrailNotificationChannel()
        incoming.forEach { trail ->
            val key = "notified_${trail.trail_id}"
            if (prefs.getBoolean(key, false)) return@forEach
            val title = getString(R.string.live_trail_pending_title)
            val ownerName = liveTrailOwners[trail.owner_did]?.takeIf { it.isNotBlank() }
                ?: shortAuthorId(trail.owner_did)
            val body = getString(R.string.live_trail_pending_body, ownerName)
            val intent = Intent(this, GroupMapActivity::class.java).apply {
                putExtra(EXTRA_GID, gid)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                trail.trail_id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, LIVE_TRAIL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mesh)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(trail.trail_id.hashCode(), notification)
            prefs.edit().putBoolean(key, true).apply()
        }
    }

    private fun canPostNotifications(): Boolean {
        return NotificationGate.status(this, LIVE_TRAIL_CHANNEL_ID).enabled
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

    private fun hasSwitchedOnce(type: String, gid: String, id: String): Boolean {
        val prefs = getSharedPreferences("map_switch_guard", MODE_PRIVATE)
        val actorDid = DeviceIdProvider.get(this)
        return prefs.getBoolean("${type}_switch_${gid}_${id}_${actorDid}", false)
    }

    private fun markSwitchedOnce(type: String, gid: String, id: String) {
        val prefs = getSharedPreferences("map_switch_guard", MODE_PRIVATE)
        val actorDid = DeviceIdProvider.get(this)
        prefs.edit().putBoolean("${type}_switch_${gid}_${id}_${actorDid}", true).apply()
    }

    private fun applyNonOwnerVoteUi(
        voteKey: String,
        gid: String,
        itemId: String,
        currentVoteConfidence: Int?,
        confidenceObserved: android.widget.RadioButton,
        confidenceReported: android.widget.RadioButton,
    ) {
        val hasVote = currentVoteConfidence != null
        val switchedOnce = hasVote && hasSwitchedOnce(voteKey, gid, itemId)
        if (!hasVote) {
            confidenceObserved.isChecked = false
            confidenceReported.isChecked = false
            confidenceObserved.isEnabled = true
            confidenceReported.isEnabled = true
            return
        }
        if (currentVoteConfidence == 2) {
            confidenceObserved.isChecked = true
            confidenceReported.isChecked = false
        } else {
            confidenceObserved.isChecked = false
            confidenceReported.isChecked = true
        }
        if (switchedOnce) {
            confidenceObserved.isEnabled = false
            confidenceReported.isEnabled = false
        } else {
            if (confidenceObserved.isChecked) {
                confidenceObserved.isEnabled = false
                confidenceReported.isEnabled = true
            } else {
                confidenceReported.isEnabled = false
                confidenceObserved.isEnabled = true
            }
        }
    }

    private fun computeEditLockSeconds(createdSeconds: Long, expiresAtSeconds: Long?): Long? {
        if (expiresAtSeconds == null) return null
        val ttlSeconds = expiresAtSeconds - createdSeconds
        if (ttlSeconds <= 0L) return null
        val half = (ttlSeconds * EDIT_LOCK_RATIO).toLong()
        return half.coerceIn(EDIT_LOCK_MIN_SEC, EDIT_LOCK_MAX_SEC)
    }

    private fun isEditLocked(createdSeconds: Long, expiresAtSeconds: Long?, nowSeconds: Long): Boolean {
        val lockSeconds = computeEditLockSeconds(createdSeconds, expiresAtSeconds) ?: return false
        return nowSeconds - createdSeconds >= lockSeconds
    }

    private fun cancelDraft() {
        if (::mapCreationController.isInitialized) mapCreationController.cancel()
        pendingCreationUi = null
        mapCreationPendingFeedbackCoordinator.clear()
        clearDraftState()
        resumeMapRefreshTimer(resetTimer = true)
        flushPendingRemoteMapRefresh()
    }

    private fun clearPendingCreationUiFor(dialog: AlertDialog) {
        val pending = pendingCreationUi ?: return
        if (pending.dialog !== dialog) return
        pendingCreationUi = null
        mapCreationPendingFeedbackCoordinator.clearIfMatches(pending.submissionId)
    }

    /** Clears only drafting state; scope switches must not revive an old refresh timer. */
    private fun clearDraftState() {
        if (::mapCreationDraftRenderer.isInitialized) mapCreationDraftRenderer.clear()
        draftAreaInvalidMessage = null
        draftAreaToastCooldown.clear()
        hideStatus()
        setFreeUi()
        mapView.invalidate()
    }

    private fun isCurrentMapScope(groupId: String, generation: Long): Boolean =
        MapGroupScopePolicy.isCurrent(
            MapGroupScopeToken(groupId, generation),
            currentGid,
            mapGroupScopeGeneration,
        )

    private fun isCurrentTrailScope(groupId: String, generation: Long, trailId: String): Boolean =
        MapGroupScopePolicy.isCurrentTrail(
            MapGroupScopeToken(groupId, generation),
            trailId,
            currentGid,
            mapGroupScopeGeneration,
            selectedTrailId,
            currentLiveTrails.map { ScopedTrailIdentity(it.gid, it.trail_id) },
        )

    private fun commitDraft(gid: String) {
        when (inputMode) {
            InputMode.MARKER_DRAFT -> {
                val marker = mapCreationController.currentState().draft?.geometry
                    as? app.peermesh.map.presentation.creation.MapCreationDraftGeometry.Marker
                    ?: return
                showMarkerDialog(
                    gid = gid,
                    existing = null,
                    presetLat = marker.latitude,
                    presetLon = marker.longitude,
                    onFinished = {
                        cancelDraft()
                        refreshMapUiNow(gid, resetTimer = true)
                    }
                )
            }
            InputMode.AREA_DRAFT -> {
                val points = creationAreaPoints()
                if (points.size < 3) return
                val geoJson = buildGeoJsonPolygon(points)
                showAreaDialog(
                    gid = gid,
                    existing = null,
                    presetGeometryRaw = geoJson,
                    onFinished = {
                        cancelDraft()
                        refreshMapUiNow(gid, resetTimer = true)
                    }
                )
            }
            else -> {}
        }
    }

    private fun buildGeoJsonPolygon(points: List<GeoPoint>): String {
        // GeoJSON Polygon expects [ [ [lon,lat], ... ] ] and ring closed (last == first)
        val ring = points.map {
            JsonArray(listOf(JsonPrimitive(it.longitude), JsonPrimitive(it.latitude)))
        }.toMutableList()
        // close ring
        val first = points.first()
        ring.add(JsonArray(listOf(JsonPrimitive(first.longitude), JsonPrimitive(first.latitude))))

        val obj = JsonObject(
            mapOf(
                "type" to JsonPrimitive("Polygon"),
                "coordinates" to JsonArray(listOf(JsonArray(ring)))
            )
        )
        return obj.toString()
    }

    // ----------------------------
    // Floating status helpers
    // ----------------------------

    private var hideStatusRunnable: Runnable? = null

    private fun showStatus(text: String) {
        hideStatusRunnable?.let { handler.removeCallbacks(it) }
        statusText.text = text
        statusText.visibility = android.view.View.VISIBLE
    }

    private fun showStatusTemporarily(text: String, ms: Long) {
        showStatus(text)
        val r = Runnable { hideStatus() }
        hideStatusRunnable = r
        handler.postDelayed(r, ms)
    }

    private fun hideStatus() {
        hideStatusRunnable?.let { handler.removeCallbacks(it) }
        hideStatusRunnable = null
        statusText.visibility = android.view.View.GONE
    }

    // ----------------------------
    // Dialogs (prefill)
    // ----------------------------

    private fun openMarkerEditDialog(gid: String, marker: MapMarkerEntity) {
        val scopeGeneration = mapGroupScopeGeneration
        Thread {
            val category = AppDatabaseProvider.get(applicationContext)
                .mapItemDomainStateDao()
                .find(gid, "marker", marker.marker_id)
                ?.category
                ?.let(MapItemCategory::fromWireValue)
            runOnUiThread {
                if (isCurrentMapScope(gid, scopeGeneration)) {
                    showMarkerDialog(gid, marker, persistedCategory = category)
                }
            }
        }.start()
    }

    private fun openAreaEditDialog(gid: String, area: MapAreaEntity) {
        val scopeGeneration = mapGroupScopeGeneration
        Thread {
            val category = AppDatabaseProvider.get(applicationContext)
                .mapItemDomainStateDao()
                .find(gid, "area", area.area_id)
                ?.category
                ?.let(MapItemCategory::fromWireValue)
            runOnUiThread {
                if (isCurrentMapScope(gid, scopeGeneration)) {
                    showAreaDialog(gid, area, persistedCategory = category)
                }
            }
        }.start()
    }

    private fun showMarkerDialog(
        gid: String,
        existing: MapMarkerEntity?,
        presetLat: Double? = null,
        presetLon: Double? = null,
        onFinished: (() -> Unit)? = null,
        persistedCategory: MapItemCategory? = null,
    ) {
        val dialogGroupId = gid
        val dialogScopeGeneration = mapGroupScopeGeneration
        val view = layoutInflater.inflate(R.layout.dialog_map_marker, null)
        val latInput = view.findViewById<EditText>(R.id.marker_lat)
        val lonInput = view.findViewById<EditText>(R.id.marker_lon)
        val radiusInput = view.findViewById<EditText>(R.id.marker_radius)
        val categorySpinner = view.findViewById<android.widget.Spinner>(R.id.marker_category_spinner)
        val categorySummary = view.findViewById<TextView>(R.id.marker_category_summary)
        val kindSpinner = view.findViewById<android.widget.Spinner>(R.id.marker_kind_spinner)
        val ttlInput = view.findViewById<EditText>(R.id.marker_ttl_minutes)
        val confidenceGroup = view.findViewById<android.widget.RadioGroup>(R.id.marker_confidence_group)
        val confidenceObserved = view.findViewById<android.widget.RadioButton>(R.id.marker_conf_observed)
        val confidenceReported = view.findViewById<android.widget.RadioButton>(R.id.marker_conf_reported)
        val confidenceUncertain = view.findViewById<android.widget.RadioButton>(R.id.marker_conf_uncertain)
        val labelInput = view.findViewById<EditText>(R.id.marker_label)
        val noteInput = view.findViewById<EditText>(R.id.marker_note)
        val readOnlySummary = view.findViewById<TextView>(R.id.marker_readonly_summary)
        val removalStatus = view.findViewById<TextView>(R.id.marker_removal_status)
        val removalActions = view.findViewById<View>(R.id.marker_removal_actions)
        val contribTitle = view.findViewById<TextView>(R.id.marker_contrib_title)
        val contribList = view.findViewById<LinearLayout>(R.id.marker_contrib_list)
        val contribMore = view.findViewById<TextView>(R.id.marker_contrib_more)

        val actorDid = DeviceIdProvider.get(this)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val editLocked = existing != null && isEditLocked(existing.created_t, existing.expires_at, nowSeconds)

        if (existing == null) {
            confidenceObserved.text = getString(R.string.map_creation_source_firsthand)
            confidenceReported.text = getString(R.string.map_creation_source_reported)
            confidenceUncertain.text = getString(R.string.map_creation_source_uncertain)
            confidenceUncertain.visibility = View.VISIBLE
            noteInput.hint = getString(R.string.map_creation_note_hint)
            confidenceGroup.clearCheck()
        } else {
            confidenceUncertain.visibility = View.GONE
        }

        if (existing != null) {
            latInput.setText(existing.lat.toString())
            lonInput.setText(existing.lon.toString())
            radiusInput.setText(existing.radius_m?.toString() ?: "")
            // kind selection is set after adapter is bound
            labelInput.setText(existing.label ?: "")
            noteInput.setText(existing.note ?: "")
            if (existing.actor_did == actorDid) {
                if (existing.confidence == 2) {
                    confidenceObserved.isChecked = true
                } else {
                    confidenceReported.isChecked = true
                }
            } else {
                confidenceObserved.isChecked = false
                confidenceReported.isChecked = false
            }
            val ttlRemaining = existing.expires_at?.let { it - nowSeconds } ?: 0L
            if (ttlRemaining > 0) {
                val ttlMinutes = kotlin.math.ceil(ttlRemaining / 60.0).toLong()
                ttlInput.setText(ttlMinutes.toString())
            }
        } else {
            if (presetLat != null) latInput.setText(presetLat.toString())
            if (presetLon != null) lonInput.setText(presetLon.toString())
        }

        val removalActive = existing?.let {
            isRemovalRequested(it.removal_requested_t, it.removal_cancel_t)
        } ?: false
        if (removalActive) {
            val reason = existing?.removal_reason ?: "--"
            removalStatus.text = getString(R.string.map_removal_status_format, reason)
            removalStatus.visibility = View.VISIBLE
        } else {
            removalStatus.visibility = View.GONE
        }

        val hideObserved = existing != null && existing.actor_did == actorDid
        confidenceObserved.visibility = if (hideObserved) android.view.View.GONE else android.view.View.VISIBLE
        if (hideObserved) {
            confidenceReported.isChecked = true
        }
        if (existing != null && existing.actor_did != actorDid) {
            confidenceGroup.clearCheck()
            confidenceObserved.isChecked = false
            confidenceReported.isChecked = false
        }
        if (existing != null && (existing.actor_did != actorDid || editLocked)) {
            val ttlRemaining = existing.expires_at?.let { it - nowSeconds } ?: 0L
            val ttlDisplay = if (ttlRemaining > 0) {
                val ttlMinutes = kotlin.math.ceil(ttlRemaining / 60.0).toLong()
                "${ttlMinutes}m"
            } else {
                "--"
            }
            val radiusDisplay = existing.radius_m?.let { String.format(java.util.Locale.US, "%.0f m", it) } ?: "--"
            val labelDisplay = existing.label ?: "--"
            val kindDisplay = MapKinds.labelForKey(existing.kind) ?: existing.kind ?: "--"
            val latDisplay = String.format(java.util.Locale.US, "%.5f", existing.lat)
            val lonDisplay = String.format(java.util.Locale.US, "%.5f", existing.lon)
            readOnlySummary.text = getString(
                R.string.map_marker_readonly_summary_format,
                kindDisplay,
                latDisplay,
                lonDisplay,
                radiusDisplay,
                ttlDisplay,
                labelDisplay
            )
            readOnlySummary.visibility = android.view.View.VISIBLE
            latInput.visibility = View.GONE
            lonInput.visibility = View.GONE
            radiusInput.visibility = View.GONE
            kindSpinner.visibility = View.GONE
            ttlInput.visibility = View.GONE
            labelInput.visibility = View.GONE
        }
        // Removal requests and vote-style controls are legacy surfaces. Existing
        // records remain readable, while normal edit uses canonical Edit/Delete
        // and dedicated Situation/Coordination actions live in Details.
        removalActions.visibility = View.GONE
        var currentVoteConfidence: Int? = null
        if (existing != null) {
            confidenceGroup.visibility = View.GONE
            if (existing.actor_did != actorDid) {
                noteInput.visibility = View.GONE
            }
        }

        var creationKindAvailable = true
        var creationPositiveButton: Button? = null
        fun bindMarkerCreationKinds(category: MapItemCategory) {
            val compatible = MapKinds.markerCreationOptions(groupProfile, category)
            creationKindAvailable = compatible.isNotEmpty()
            val displayed = compatible.ifEmpty {
                listOf(
                    MapKinds.KindOption(
                        key = "",
                        label = getString(R.string.map_creation_no_compatible_kinds),
                    ),
                )
            }
            kindSpinner.adapter = android.widget.ArrayAdapter(
                this,
                R.layout.spinner_item_primary,
                displayed,
            ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_primary) }
            kindSpinner.isEnabled = creationKindAvailable
            creationPositiveButton?.isEnabled =
                creationKindAvailable &&
                    mapCreationController.currentState().operation !is MapCreationOperationState.Submitting
        }

        val creationCategories = listOf(
            getString(R.string.map_creation_category_situation),
            getString(R.string.map_creation_category_coordination),
        )
        categorySpinner.adapter = android.widget.ArrayAdapter(
            this,
            R.layout.spinner_item_primary,
            creationCategories,
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_primary) }
        if (existing != null) {
            categorySpinner.visibility = View.GONE
            categorySummary.text = persistedCategory?.let { category ->
                getString(
                    R.string.map_category_summary_format,
                    getString(
                        if (category == MapItemCategory.SITUATION) {
                            R.string.map_creation_category_situation
                        } else {
                            R.string.map_creation_category_coordination
                        },
                    ),
                )
            } ?: getString(R.string.map_category_unavailable)
            categorySummary.visibility = View.VISIBLE
            val compatibleKinds = persistedCategory?.let {
                MapKinds.markerCreationOptions(groupProfile, it)
            }.orEmpty()
            val persistedKey = MapKinds.normalizeKey(existing.kind)
            val persistedOption = MapKinds.KindOption(
                key = persistedKey ?: existing.kind.orEmpty(),
                label = MapKinds.labelForKey(existing.kind) ?: existing.kind ?: "--",
            )
            val markerKinds = if (
                persistedKey != null && compatibleKinds.any { it.key == persistedKey }
            ) {
                compatibleKinds
            } else {
                listOf(persistedOption)
            }
            kindSpinner.adapter = android.widget.ArrayAdapter(
                this,
                R.layout.spinner_item_primary,
                markerKinds,
            ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_primary) }
            val presetIndex = markerKinds.indexOfFirst { it.key == persistedKey }.takeIf { it >= 0 } ?: 0
            kindSpinner.setSelection(presetIndex)
            kindSpinner.isEnabled = persistedCategory != null &&
                compatibleKinds.any { it.key == persistedKey }
        } else {
            bindMarkerCreationKinds(MapItemCategory.SITUATION)
            categorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val category = if (position == 1) {
                        MapItemCategory.COORDINATION
                    } else {
                        MapItemCategory.SITUATION
                    }
                    mapCreationController.updateCategory(category)
                    mapCreationController.updateKind(null)
                    bindMarkerCreationKinds(category)
                    confidenceGroup.visibility =
                        if (category == MapItemCategory.SITUATION) View.VISIBLE else View.GONE
                    if (category == MapItemCategory.COORDINATION) {
                        confidenceGroup.clearCheck()
                        mapCreationController.updateCreatorInformationSource(null)
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }

        val dialogTitle = getString(R.string.map_marker_details)
        val dialog = AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            creationPositiveButton = null
            openMapScopeDialogs.remove(dialog)
            clearPendingCreationUiFor(dialog)
            if (isCurrentMapScope(dialogGroupId, dialogScopeGeneration)) {
                resumeMapRefreshTimer(resetTimer = true)
            }
        }

        dialog.setOnShowListener {
            pauseMapRefreshTimer()
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            creationPositiveButton = button
            if (existing == null) button.isEnabled = creationKindAvailable
            if (editLocked || (existing != null && existing.actor_did != actorDid)) {
                // Core fields locked for non-owner or after edit window.
                latInput.isEnabled = false
                lonInput.isEnabled = false
                radiusInput.isEnabled = false
                kindSpinner.isEnabled = false
                ttlInput.isEnabled = false
                labelInput.isEnabled = false
            }
            if (existing != null && existing.actor_did != actorDid) {
                button.visibility = View.GONE
            }
            button.setOnClickListener {
                if (!isCurrentMapScope(dialogGroupId, dialogScopeGeneration)) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val editLocked = existing != null &&
                    isEditLocked(existing.created_t, existing.expires_at, nowSeconds)
                val lat = if (editLocked && existing != null) {
                    existing.lat
                } else {
                    latInput.text.toString().trim().toDoubleOrNull() ?: run {
                        toast(getString(R.string.map_lat_lon_required))
                        return@setOnClickListener
                    }
                }
                val lon = if (editLocked && existing != null) {
                    existing.lon
                } else {
                    lonInput.text.toString().trim().toDoubleOrNull() ?: run {
                        toast(getString(R.string.map_lat_lon_required))
                        return@setOnClickListener
                    }
                }
                val radius = if (editLocked && existing != null) {
                    existing.radius_m
                } else {
                    radiusInput.text.toString().trim().toDoubleOrNull()
                }
                val kind = if (editLocked && existing != null) {
                    existing.kind
                } else {
                    (kindSpinner.selectedItem as? MapKinds.KindOption)?.key
                }
                if (kind.isNullOrBlank()) {
                    toast(getString(R.string.map_kind_required))
                    return@setOnClickListener
                }
                val creationCategory = if (categorySpinner.selectedItemPosition == 1) {
                    MapItemCategory.COORDINATION
                } else {
                    MapItemCategory.SITUATION
                }
                val radiusMeters = radius?.toFloat()
                if (existing == null && isTypeCooldownNearbyMarker(gid, kind, lat, lon, radiusMeters, nowSeconds)) {
                    toast(getString(R.string.map_spam_cooldown_nearby))
                    return@setOnClickListener
                }
                if (existing == null && isSameTypeNearbyMarker(gid, kind, lat, lon, radiusMeters)) {
                    val threshold = radiusMeters?.let {
                        maxOf(MARKER_PROXIMITY_GUARD_M, it * MARKER_PROXIMITY_GUARD_RADIUS_RATIO)
                            .coerceAtMost(MARKER_PROXIMITY_GUARD_MAX_M)
                    } ?: MARKER_PROXIMITY_GUARD_M
                    val distanceText = if (threshold >= 1000f) {
                        String.format(java.util.Locale.US, "%.1fkm", threshold / 1000f)
                    } else {
                        "${threshold.toInt()}m"
                    }
                    toast("${getString(R.string.map_spam_same_type_nearby)} ($distanceText)")
                    return@setOnClickListener
                }
                val label = if (editLocked && existing != null) {
                    existing.label
                } else {
                    labelInput.text.toString().trim().ifBlank { null }?.take(5)
                }
                val note = noteInput.text.toString().trim()
                val creatorInformationSource =
                    if (existing == null && creationCategory == MapItemCategory.SITUATION) {
                        when (confidenceGroup.checkedRadioButtonId) {
                            R.id.marker_conf_observed -> MapInformationSource.FIRSTHAND
                            R.id.marker_conf_reported -> MapInformationSource.REPORTED_BY_OTHERS
                            R.id.marker_conf_uncertain -> MapInformationSource.UNCERTAIN
                            else -> null
                        }
                    } else {
                        null
                    }
                val confidence = if (existing != null) {
                    existing.confidence
                } else if (creationCategory == MapItemCategory.COORDINATION) {
                    1
                } else {
                    when (creatorInformationSource) {
                        MapInformationSource.FIRSTHAND -> 2
                        MapInformationSource.REPORTED_BY_OTHERS,
                        MapInformationSource.UNCERTAIN,
                        -> 1
                        null -> {
                            toast(getString(R.string.map_creation_source_required))
                            return@setOnClickListener
                        }
                    }
                }
                if (confidence == null) {
                    toast(getString(R.string.map_conf_required))
                    return@setOnClickListener
                }
                if (existing != null && existing.actor_did == actorDid && confidence != existing.confidence) {
                    toast(getString(R.string.map_owner_vote_fixed))
                    return@setOnClickListener
                }
                val ttlSeconds = if (editLocked && existing != null) {
                    existing.expires_at?.minus(existing.created_t)
                } else {
                    ttlInput.text.toString().trim().toLongOrNull()?.takeIf { it > 0 }?.times(60L)
                }
                val existingTtlSeconds = existing?.expires_at?.minus(existing.created_t)
                val coreChanged = existing != null && (
                    lat != existing.lat ||
                        lon != existing.lon ||
                        radius != existing.radius_m ||
                        kind != existing.kind ||
                        label != existing.label ||
                        ttlSeconds != existingTtlSeconds
                )
                if (editLocked && coreChanged) {
                    toast(getString(R.string.map_edit_window_expired))
                    return@setOnClickListener
                }
                if (editLocked && existing != null && existing.actor_did != actorDid) {
                    if (currentVoteConfidence == null) {
                        toast(getString(R.string.map_edit_window_expired))
                        return@setOnClickListener
                    }
                    if (confidence != currentVoteConfidence) {
                        toast(getString(R.string.map_edit_window_expired))
                        return@setOnClickListener
                    }
                }
                val isSwitch = existing != null && existing.actor_did == actorDid && confidence != existing.confidence
                val isUserVoteSwitch = existing != null &&
                    existing.actor_did != actorDid &&
                    currentVoteConfidence != null &&
                    currentVoteConfidence != confidence
                if (isUserVoteSwitch && existing?.marker_id?.let { markerId ->
                        hasSwitchedOnce("marker_vote", gid, markerId)
                    } == true
                ) {
                    toast(getString(R.string.map_marker_switch_once))
                    return@setOnClickListener
                }
                if (isSwitch && existing?.actor_did == actorDid) {
                    toast(getString(R.string.map_owner_vote_fixed))
                    return@setOnClickListener
                }
                if (isSwitch && existing?.marker_id?.let { markerId ->
                        hasSwitchedOnce("marker", gid, markerId)
                    } == true
                ) {
                    toast(getString(R.string.map_marker_switch_once))
                    return@setOnClickListener
                }

                // Room disallows DB writes on the main thread -> do submit on a background thread.
                button.isEnabled = false
                if (existing == null) {
                    mapCreationController.updateCategory(creationCategory)
                    mapCreationController.updateKind(kind)
                    mapCreationController.updateLabel(label)
                    mapCreationController.updateNote(note)
                    mapCreationController.updateTtlSeconds(ttlSeconds)
                    mapCreationController.updateCreatorInformationSource(creatorInformationSource)
                    mapCreationController.updateMarker(lat, lon, radius)
                    val submissionId = mapCreationController.submit { startedSubmissionId ->
                        pendingCreationUi = PendingCreationUi(
                            dialogGroupId,
                            dialogScopeGeneration,
                            startedSubmissionId,
                            dialog,
                            button,
                            onFinished,
                        )
                        mapCreationPendingFeedbackCoordinator.register(startedSubmissionId)
                    }
                    if (submissionId == null) button.isEnabled = true
                    return@setOnClickListener
                }
                submitMarkerAsync(gid, existing, lat, lon, radius, kind, label, note, confidence, ttlSeconds) { ok ->
                    if (!isCurrentMapScope(dialogGroupId, dialogScopeGeneration)) return@submitMarkerAsync
                    button.isEnabled = true
                    if (ok) {
                        if (isSwitch) {
                            existing?.marker_id?.let { markerId ->
                                markSwitchedOnce("marker", gid, markerId)
                            }
                        }
                        if (isUserVoteSwitch) {
                            existing?.marker_id?.let { markerId ->
                                markSwitchedOnce("marker_vote", gid, markerId)
                            }
                        }
                        finishFlow(dialog, onFinished)
                        refreshMapUi(gid)
                    }
                }

            }
        }
        dialog.show()
        openMapScopeDialogs += dialog

        contribTitle.visibility = View.GONE
        contribMore.visibility = View.GONE
        contribList.removeAllViews()
    }

    private fun showAreaDialog(
        gid: String,
        existing: MapAreaEntity?,
        presetGeometryRaw: String? = null,
        onFinished: (() -> Unit)? = null,
        persistedCategory: MapItemCategory? = null,
    ) {
        val dialogGroupId = gid
        val dialogScopeGeneration = mapGroupScopeGeneration
        val view = layoutInflater.inflate(R.layout.dialog_map_area, null)
        val geometryInput = view.findViewById<EditText>(R.id.area_geometry)
        val geometrySummary = view.findViewById<TextView>(R.id.area_geometry_summary)
        val categorySpinner = view.findViewById<android.widget.Spinner>(R.id.area_category_spinner)
        val categorySummary = view.findViewById<TextView>(R.id.area_category_summary)
        val kindSpinner = view.findViewById<android.widget.Spinner>(R.id.area_kind_spinner)
        val ttlInput = view.findViewById<EditText>(R.id.area_ttl_minutes)
        val confidenceGroup = view.findViewById<android.widget.RadioGroup>(R.id.area_confidence_group)
        val confidenceObserved = view.findViewById<android.widget.RadioButton>(R.id.area_conf_observed)
        val confidenceReported = view.findViewById<android.widget.RadioButton>(R.id.area_conf_reported)
        val confidenceUncertain = view.findViewById<android.widget.RadioButton>(R.id.area_conf_uncertain)
        val noteInput = view.findViewById<EditText>(R.id.area_note)
        val labelInput = view.findViewById<EditText>(R.id.area_label)
        val readOnlySummary = view.findViewById<TextView>(R.id.area_readonly_summary)
        val removalStatus = view.findViewById<TextView>(R.id.area_removal_status)
        val removalActions = view.findViewById<View>(R.id.area_removal_actions)
        val contribTitle = view.findViewById<TextView>(R.id.area_contrib_title)
        val contribList = view.findViewById<LinearLayout>(R.id.area_contrib_list)
        val contribMore = view.findViewById<TextView>(R.id.area_contrib_more)

        val actorDid = DeviceIdProvider.get(this)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val editLocked = existing != null && isEditLocked(existing.created_t, existing.expires_at, nowSeconds)

        if (existing == null) {
            confidenceObserved.text = getString(R.string.map_creation_source_firsthand)
            confidenceReported.text = getString(R.string.map_creation_source_reported)
            confidenceUncertain.text = getString(R.string.map_creation_source_uncertain)
            confidenceUncertain.visibility = View.VISIBLE
            noteInput.hint = getString(R.string.map_creation_note_hint)
            confidenceGroup.clearCheck()
        } else {
            confidenceUncertain.visibility = View.GONE
        }

        if (existing != null) {
            geometryInput.setText(existing.geometry_json)
            // kind selection is set after adapter is bound
            labelInput.setText(existing.label ?: "")
            noteInput.setText(existing.note ?: "")
            if (existing.actor_did == actorDid) {
                if (existing.confidence == 2) {
                    confidenceObserved.isChecked = true
                } else {
                    confidenceReported.isChecked = true
                }
            } else {
                confidenceObserved.isChecked = false
                confidenceReported.isChecked = false
            }
            val ttlRemaining = existing.expires_at?.let { it - nowSeconds } ?: 0L
            if (ttlRemaining > 0) {
                val ttlMinutes = kotlin.math.ceil(ttlRemaining / 60.0).toLong()
                ttlInput.setText(ttlMinutes.toString())
            }
        } else if (!presetGeometryRaw.isNullOrBlank()) {
            geometryInput.setText(presetGeometryRaw)
        }
        geometrySummary.text = areaGeometrySummary(geometryInput.text.toString())

        val removalActive = existing?.let {
            isRemovalRequested(it.removal_requested_t, it.removal_cancel_t)
        } ?: false
        if (removalActive) {
            val reason = existing?.removal_reason ?: "--"
            removalStatus.text = getString(R.string.map_removal_status_format, reason)
            removalStatus.visibility = View.VISIBLE
        } else {
            removalStatus.visibility = View.GONE
        }

        val hideObserved = existing != null && existing.actor_did == actorDid
        confidenceObserved.visibility = if (hideObserved) android.view.View.GONE else android.view.View.VISIBLE
        if (hideObserved) {
            confidenceReported.isChecked = true
        }
        if (existing != null && existing.actor_did != actorDid) {
            confidenceGroup.clearCheck()
            confidenceObserved.isChecked = false
            confidenceReported.isChecked = false
        }
        if (existing != null && (existing.actor_did != actorDid || editLocked)) {
            val ttlRemaining = existing.expires_at?.let { it - nowSeconds } ?: 0L
            val ttlDisplay = if (ttlRemaining > 0) {
                val ttlMinutes = kotlin.math.ceil(ttlRemaining / 60.0).toLong()
                "${ttlMinutes}m"
            } else {
                "--"
            }
            val labelDisplay = existing.label ?: "--"
            val kindDisplay = MapKinds.labelForKey(existing.kind) ?: existing.kind ?: "--"
            readOnlySummary.text = getString(
                R.string.map_area_readonly_summary_format,
                kindDisplay,
                ttlDisplay,
                labelDisplay,
                areaGeometrySummary(existing.geometry_json),
            )
            readOnlySummary.visibility = View.VISIBLE
            geometryInput.visibility = View.GONE
            kindSpinner.visibility = View.GONE
            ttlInput.visibility = View.GONE
            labelInput.visibility = View.GONE
        }
        removalActions.visibility = View.GONE
        var currentVoteConfidence: Int? = null
        if (existing != null) {
            confidenceGroup.visibility = View.GONE
            if (existing.actor_did != actorDid) {
                noteInput.visibility = View.GONE
            }
        }

        var creationKindAvailable = true
        var creationPositiveButton: Button? = null
        fun bindAreaCreationKinds(category: MapItemCategory) {
            val compatible = MapKinds.areaCreationOptions(groupProfile, category)
            creationKindAvailable = compatible.isNotEmpty()
            val displayed = compatible.ifEmpty {
                listOf(
                    MapKinds.KindOption(
                        key = "",
                        label = getString(R.string.map_creation_no_compatible_kinds),
                    ),
                )
            }
            kindSpinner.adapter = android.widget.ArrayAdapter(
                this,
                R.layout.spinner_item_primary,
                displayed,
            ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_primary) }
            kindSpinner.isEnabled = creationKindAvailable
            creationPositiveButton?.isEnabled =
                creationKindAvailable &&
                    mapCreationController.currentState().operation !is MapCreationOperationState.Submitting
        }

        categorySpinner.adapter = android.widget.ArrayAdapter(
            this,
            R.layout.spinner_item_primary,
            listOf(
                getString(R.string.map_creation_category_situation),
                getString(R.string.map_creation_category_coordination),
            ),
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_primary) }
        if (existing != null) {
            categorySpinner.visibility = View.GONE
            categorySummary.text = persistedCategory?.let { category ->
                getString(
                    R.string.map_category_summary_format,
                    getString(
                        if (category == MapItemCategory.SITUATION) {
                            R.string.map_creation_category_situation
                        } else {
                            R.string.map_creation_category_coordination
                        },
                    ),
                )
            } ?: getString(R.string.map_category_unavailable)
            categorySummary.visibility = View.VISIBLE
            val compatibleKinds = persistedCategory?.let {
                MapKinds.areaCreationOptions(groupProfile, it)
            }.orEmpty()
            val persistedKey = MapKinds.normalizeKey(existing.kind)
            val persistedOption = MapKinds.KindOption(
                key = persistedKey ?: existing.kind.orEmpty(),
                label = MapKinds.labelForKey(existing.kind) ?: existing.kind ?: "--",
            )
            val areaKinds = if (
                persistedKey != null && compatibleKinds.any { it.key == persistedKey }
            ) {
                compatibleKinds
            } else {
                listOf(persistedOption)
            }
            kindSpinner.adapter = android.widget.ArrayAdapter(
                this,
                R.layout.spinner_item_primary,
                areaKinds,
            ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_primary) }
            val presetIndex = areaKinds.indexOfFirst { it.key == persistedKey }.takeIf { it >= 0 } ?: 0
            kindSpinner.setSelection(presetIndex)
            kindSpinner.isEnabled = persistedCategory != null &&
                compatibleKinds.any { it.key == persistedKey }
        } else {
            bindAreaCreationKinds(MapItemCategory.SITUATION)
            categorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val category = if (position == 1) {
                        MapItemCategory.COORDINATION
                    } else {
                        MapItemCategory.SITUATION
                    }
                    mapCreationController.updateCategory(category)
                    mapCreationController.updateKind(null)
                    bindAreaCreationKinds(category)
                    confidenceGroup.visibility =
                        if (category == MapItemCategory.SITUATION) View.VISIBLE else View.GONE
                    if (category == MapItemCategory.COORDINATION) {
                        confidenceGroup.clearCheck()
                        mapCreationController.updateCreatorInformationSource(null)
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }

        val dialogTitle = getString(R.string.map_area_details)
        val dialog = AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener {
            creationPositiveButton = null
            openMapScopeDialogs.remove(dialog)
            clearPendingCreationUiFor(dialog)
            if (isCurrentMapScope(dialogGroupId, dialogScopeGeneration)) {
                resumeMapRefreshTimer(resetTimer = true)
            }
        }

        dialog.setOnShowListener {
            pauseMapRefreshTimer()
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            creationPositiveButton = button
            if (existing == null) button.isEnabled = creationKindAvailable
            if (editLocked || (existing != null && existing.actor_did != actorDid)) {
                // Core fields locked for non-owner or after edit window.
                geometryInput.isEnabled = false
                kindSpinner.isEnabled = false
                ttlInput.isEnabled = false
                labelInput.isEnabled = false
            }
            if (existing != null && existing.actor_did != actorDid) {
                button.visibility = View.GONE
            }
            button.setOnClickListener {
                if (!isCurrentMapScope(dialogGroupId, dialogScopeGeneration)) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val editLocked = existing != null &&
                    isEditLocked(existing.created_t, existing.expires_at, nowSeconds)
                val geometryRaw = if (editLocked && existing != null) {
                    existing.geometry_json
                } else {
                    geometryInput.text.toString().trim()
                }
                if (geometryRaw.isBlank()) {
                    toast(getString(R.string.map_geometry_required))
                    return@setOnClickListener
                }
                val geometry = parseGeometry(geometryRaw) ?: run {
                    toast(getString(R.string.map_geometry_invalid))
                    return@setOnClickListener
                }
                if (!editLocked || existing == null) {
                    val polygonPoints = parsePolygonPoints(geometryRaw)
                    if (polygonPoints.isEmpty()) {
                        toast(getString(R.string.map_geometry_invalid))
                        return@setOnClickListener
                    }
                    val validation = validateAreaConstraints(polygonPoints)
                    if (validation != null) {
                        toast(validation)
                        return@setOnClickListener
                    }
                }
                val kind = if (editLocked && existing != null) {
                    existing.kind
                } else {
                    (kindSpinner.selectedItem as? MapKinds.KindOption)?.key
                }
                if (kind.isNullOrBlank()) {
                    toast(getString(R.string.map_kind_required))
                    return@setOnClickListener
                }
                val creationCategory = if (categorySpinner.selectedItemPosition == 1) {
                    MapItemCategory.COORDINATION
                } else {
                    MapItemCategory.SITUATION
                }
                if (existing == null) {
                    val points = parsePolygonPoints(geometryRaw)
                    val centroid = if (points.isEmpty()) null else centroidFor(points)
                    if (centroid != null && isTypeCooldownNearbyArea(gid, kind, points, centroid, nowSeconds)) {
                        toast(getString(R.string.map_spam_cooldown_nearby))
                        return@setOnClickListener
                    }
                    if (centroid != null && isSameTypeNearbyArea(gid, kind, points, centroid)) {
                        val bounds = boundsFor(points)
                        val size = boundsSizeMeters(bounds)
                        val ratio = if (size >= AREA_PROXIMITY_GUARD_RATIO_PIVOT_M) {
                            AREA_PROXIMITY_GUARD_RATIO_LARGE
                        } else {
                            AREA_PROXIMITY_GUARD_RATIO
                        }
                        val threshold = maxOf(AREA_PROXIMITY_GUARD_M, size * ratio)
                            .coerceAtMost(AREA_PROXIMITY_GUARD_MAX_M)
                        val distanceText = if (threshold >= 1000f) {
                            String.format(java.util.Locale.US, "%.1fkm", threshold / 1000f)
                        } else {
                            "${threshold.toInt()}m"
                        }
                        toast("${getString(R.string.map_spam_same_type_nearby)} ($distanceText)")
                        return@setOnClickListener
                    }
                }
                val label = if (editLocked && existing != null) {
                    existing.label
                } else {
                    labelInput.text.toString().trim().ifBlank { null }?.take(5)
                }
                val note = noteInput.text.toString().trim()
                val creatorInformationSource =
                    if (existing == null && creationCategory == MapItemCategory.SITUATION) {
                        when (confidenceGroup.checkedRadioButtonId) {
                            R.id.area_conf_observed -> MapInformationSource.FIRSTHAND
                            R.id.area_conf_reported -> MapInformationSource.REPORTED_BY_OTHERS
                            R.id.area_conf_uncertain -> MapInformationSource.UNCERTAIN
                            else -> null
                        }
                    } else {
                        null
                    }
                val confidence = if (existing != null) {
                    existing.confidence
                } else if (creationCategory == MapItemCategory.COORDINATION) {
                    1
                } else {
                    when (creatorInformationSource) {
                        MapInformationSource.FIRSTHAND -> 2
                        MapInformationSource.REPORTED_BY_OTHERS,
                        MapInformationSource.UNCERTAIN,
                        -> 1
                        null -> {
                            toast(getString(R.string.map_creation_source_required))
                            return@setOnClickListener
                        }
                    }
                }
                if (confidence == null) {
                    toast(getString(R.string.map_conf_required))
                    return@setOnClickListener
                }
                if (existing != null && existing.actor_did == actorDid && confidence != existing.confidence) {
                    toast(getString(R.string.map_owner_vote_fixed))
                    return@setOnClickListener
                }
                val ttlSeconds = if (editLocked && existing != null) {
                    existing.expires_at?.minus(existing.created_t)
                } else {
                    ttlInput.text.toString().trim().toLongOrNull()?.takeIf { it > 0 }?.times(60L)
                }
                val existingTtlSeconds = existing?.expires_at?.minus(existing.created_t)
                val coreChanged = existing != null && (
                    geometryRaw != existing.geometry_json ||
                        kind != existing.kind ||
                        label != existing.label ||
                        ttlSeconds != existingTtlSeconds
                )
                if (editLocked && coreChanged) {
                    toast(getString(R.string.map_edit_window_expired))
                    return@setOnClickListener
                }
                if (editLocked && existing != null && existing.actor_did != actorDid) {
                    if (currentVoteConfidence == null) {
                        toast(getString(R.string.map_edit_window_expired))
                        return@setOnClickListener
                    }
                    if (confidence != currentVoteConfidence) {
                        toast(getString(R.string.map_edit_window_expired))
                        return@setOnClickListener
                    }
                }
                val isSwitch = existing != null && existing.actor_did == actorDid && confidence != existing.confidence
                val isUserVoteSwitch = existing != null &&
                    existing.actor_did != actorDid &&
                    currentVoteConfidence != null &&
                    currentVoteConfidence != confidence
                if (isUserVoteSwitch && existing?.area_id?.let { areaId ->
                        hasSwitchedOnce("area_vote", gid, areaId)
                    } == true
                ) {
                    toast(getString(R.string.map_area_switch_once))
                    return@setOnClickListener
                }
                if (isSwitch && existing?.actor_did == actorDid) {
                    toast(getString(R.string.map_owner_vote_fixed))
                    return@setOnClickListener
                }
                if (isSwitch && existing?.area_id?.let { areaId ->
                        hasSwitchedOnce("area", gid, areaId)
                    } == true
                ) {
                    toast(getString(R.string.map_area_switch_once))
                    return@setOnClickListener
                }

                // Room disallows DB writes on the main thread -> do submit on a background thread.
                button.isEnabled = false
                if (existing == null) {
                    val typedPoints = parsePolygonPoints(geometryRaw)
                    mapCreationController.updateCategory(creationCategory)
                    mapCreationController.updateKind(kind)
                    mapCreationController.updateLabel(label)
                    mapCreationController.updateNote(note)
                    mapCreationController.updateTtlSeconds(ttlSeconds)
                    mapCreationController.updateCreatorInformationSource(creatorInformationSource)
                    mapCreationController.updateArea(
                        MapSceneAreaGeometry.Polygon(
                            listOf(
                                typedPoints.map { point ->
                                    MapSceneCoordinate(point.latitude, point.longitude)
                                },
                            ),
                        ),
                    )
                    val submissionId = mapCreationController.submit { startedSubmissionId ->
                        pendingCreationUi = PendingCreationUi(
                            dialogGroupId,
                            dialogScopeGeneration,
                            startedSubmissionId,
                            dialog,
                            button,
                            onFinished,
                        )
                        mapCreationPendingFeedbackCoordinator.register(startedSubmissionId)
                    }
                    if (submissionId == null) button.isEnabled = true
                    return@setOnClickListener
                }
                submitAreaAsync(gid, existing, geometry, kind, label, note, confidence, ttlSeconds) { ok ->
                    if (!isCurrentMapScope(dialogGroupId, dialogScopeGeneration)) return@submitAreaAsync
                    button.isEnabled = true
                    if (ok) {
                        if (isSwitch) {
                            existing?.area_id?.let { areaId ->
                                markSwitchedOnce("area", gid, areaId)
                            }
                        }
                        if (isUserVoteSwitch) {
                            existing?.area_id?.let { areaId ->
                                markSwitchedOnce("area_vote", gid, areaId)
                            }
                        }
                        finishFlow(dialog, onFinished)
                        refreshMapUi(gid)
                    }
                }
            }
        }
        dialog.show()
        openMapScopeDialogs += dialog

        contribTitle.visibility = View.GONE
        contribMore.visibility = View.GONE
        contribList.removeAllViews()
    }

    private fun loadContributionsPage(
        gid: String,
        itemId: String,
        itemType: String,
        offset: Int,
        limit: Int,
        container: LinearLayout,
        append: Boolean,
        moreLink: TextView,
        scopeGeneration: Long,
        isDialogActive: () -> Boolean,
    ) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        Thread {
            val votes = AppDatabaseProvider.get(this)
                .mapItemVoteDao()
                .listVotesForItem(gid, itemType, itemId, limit, offset)
            runOnUiThread {
                if (!isCurrentMapScope(gid, scopeGeneration) || !isDialogActive()) return@runOnUiThread
                if (!append) container.removeAllViews()
                votes.forEach { vote ->
                    val label = if (vote.confidence == 2) "👁 Observed" else "📣 Reported"
                    val age = formatAgeShort(nowSeconds - vote.updated_t)
                    val author = shortAuthorId(vote.actor_did)
                    val note = vote.note?.trim().takeUnless { it.isNullOrBlank() }
                        ?: getString(R.string.map_note_empty)
                    val text = "$label · $age · $author\n$note"
                    val tv = TextView(this).apply {
                        this.text = text
                        setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
                        textSize = 12f
                        setPadding(0, 4, 0, 4)
                    }
                    container.addView(tv)
                }
                moreLink.visibility = if (votes.size == limit) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun formatAgeShort(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h"
            else -> "${s / 86400}d"
        }
    }

    private fun shortAuthorId(actorDid: String): String {
        return if (actorDid.length <= 6) actorDid else actorDid.takeLast(6)
    }

    private fun maybeRefreshContribPreviews(
        gid: String,
        adapter: MapListAdapter,
        recyclerView: RecyclerView,
        force: Boolean,
    ) {
        if (gid.isBlank() || gid != currentGid) return
        val now = System.currentTimeMillis()
        if (!force && (now - lastContribPreviewRefreshMs) < 5000L) return
        lastContribPreviewRefreshMs = now
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
        loadVisibleContribPreviews(gid, adapter, first, last)
    }

    private fun loadVisibleContribPreviews(
        gid: String,
        adapter: MapListAdapter,
        first: Int,
        last: Int,
    ) {
        val scopeGeneration = mapGroupScopeGeneration
        val nowSeconds = System.currentTimeMillis() / 1000L
        val markerIds = mutableMapOf<String, Int>()
        val areaIds = mutableMapOf<String, Int>()
        for (i in first..last) {
            when (val item = adapter.getItemAt(i)) {
                is MapListAdapter.MapListItem.Marker -> markerIds[item.entity.marker_id] = i
                is MapListAdapter.MapListItem.Area -> areaIds[item.entity.area_id] = i
                else -> {}
            }
        }
        if (markerIds.isEmpty() && areaIds.isEmpty()) return
        val actorDid = DeviceIdProvider.get(this)
        val markerOwners = listMarkers.associate { it.marker_id to it.actor_did }
        val areaOwners = listAreas.associate { it.area_id to it.actor_did }
        Thread {
            val markerTexts = mutableMapOf<String, String>()
            markerIds.keys.forEach { id ->
                val votes = AppDatabaseProvider.get(this)
                    .mapItemVoteDao()
                    .listVotesForItem(gid, "marker", id, 1, 0)
                if (votes.isEmpty()) {
                    val ownerDid = markerOwners[id]
                    markerTexts[id] = if (ownerDid == actorDid) {
                        getString(R.string.map_contrib_none_owner)
                    } else {
                        getString(R.string.map_contrib_none_non_owner)
                    }
                } else {
                    val vote = votes.first()
                    val label = if (vote.confidence == 2) "👁 Observed" else "📣 Reported"
                    val age = formatAgeShort(nowSeconds - vote.updated_t)
                    val author = shortAuthorId(vote.actor_did)
                    val note = vote.note?.trim().takeUnless { it.isNullOrBlank() }
                        ?: getString(R.string.map_note_empty)
                    markerTexts[id] = "$label · $age · $author\n$note"
                }
            }
            val areaTexts = mutableMapOf<String, String>()
            areaIds.keys.forEach { id ->
                val votes = AppDatabaseProvider.get(this)
                    .mapItemVoteDao()
                    .listVotesForItem(gid, "area", id, 1, 0)
                if (votes.isEmpty()) {
                    val ownerDid = areaOwners[id]
                    areaTexts[id] = if (ownerDid == actorDid) {
                        getString(R.string.map_contrib_none_owner)
                    } else {
                        getString(R.string.map_contrib_none_non_owner)
                    }
                } else {
                    val vote = votes.first()
                    val label = if (vote.confidence == 2) "👁 Observed" else "📣 Reported"
                    val age = formatAgeShort(nowSeconds - vote.updated_t)
                    val author = shortAuthorId(vote.actor_did)
                    val note = vote.note?.trim().takeUnless { it.isNullOrBlank() }
                        ?: getString(R.string.map_note_empty)
                    areaTexts[id] = "$label · $age · $author\n$note"
                }
            }
            runOnUiThread {
                if (!isCurrentMapScope(gid, scopeGeneration)) return@runOnUiThread
                adapter.updateContributionPreviewText(markerTexts, areaTexts)
                for (i in first..last) {
                    adapter.notifyItemChanged(i)
                }
            }
        }.start()
    }

    private fun finishFlow(dialog: android.app.Dialog, onFinished: (() -> Unit)?) {
        dialog.dismiss()
        onFinished?.invoke()
    }

    // ----------------------------
    // CRUD confirmations
    // ----------------------------

    private fun refreshMapUi(gid: String) {
        val rv = findViewById<RecyclerView>(R.id.map_items_list)
        val adapter = rv.adapter as? MapListAdapter ?: return
        refreshMapUiNow(gid, resetTimer = true)
    }

    private fun refreshMapUiNow(gid: String, resetTimer: Boolean) {
        val rv = findViewById<RecyclerView>(R.id.map_items_list)
        val adapter = rv.adapter as? MapListAdapter ?: return
        if (isDrafting()) {
            return
        }
        if (pendingRemoteMapRefreshGid == gid) {
            pendingRemoteMapRefreshGid = null
            remoteMapRefreshRunnable?.let(handler::removeCallbacks)
            remoteMapRefreshRunnable = null
        }
        loadMapItems(gid, adapter)
        if (resetTimer) {
            startMapRefreshTimer(gid, adapter)
        }
    }

    /**
     * Remote BLE and relay events reach this boundary only after EventIngestor has accepted and
     * committed them. Coalesce bursts, preserve an active creation draft, and then reload the
     * authoritative Room-backed map scene and list for the current group.
     */
    private fun requestRemoteMapRefresh(gid: String) {
        if (gid != currentGid) return
        pendingRemoteMapRefreshGid = gid
        if (isDrafting()) return
        remoteMapRefreshRunnable?.let(handler::removeCallbacks)
        val scopeGeneration = mapGroupScopeGeneration
        val refresh = Runnable {
            remoteMapRefreshRunnable = null
            if (!isCurrentMapScope(gid, scopeGeneration)) return@Runnable
            if (isDrafting()) return@Runnable
            if (pendingRemoteMapRefreshGid != gid) return@Runnable
            pendingRemoteMapRefreshGid = null
            refreshMapUiNow(gid, resetTimer = true)
        }
        remoteMapRefreshRunnable = refresh
        handler.postDelayed(refresh, REMOTE_MAP_REFRESH_DEBOUNCE_MS)
    }

    private fun flushPendingRemoteMapRefresh() {
        val gid = pendingRemoteMapRefreshGid ?: return
        requestRemoteMapRefresh(gid)
    }

    private fun startMapRefreshTimer(gid: String, adapter: MapListAdapter) {
        mapRefreshGid = gid
        mapRefreshAdapter = adapter
        mapRefreshScopeGeneration = mapGroupScopeGeneration
        mapRefreshPaused = false
        scheduleNextMapRefresh()
    }

    private fun pauseMapRefreshTimer() {
        mapRefreshPaused = true
        mapRefreshRunnable?.let { handler.removeCallbacks(it) }
        mapRefreshRunnable = null
    }

    private fun resumeMapRefreshTimer(resetTimer: Boolean) {
        mapRefreshPaused = false
        if (resetTimer) {
            scheduleNextMapRefresh()
        }
    }

    private fun stopMapRefreshTimer() {
        mapRefreshRunnable?.let { handler.removeCallbacks(it) }
        mapRefreshRunnable = null
        mapRefreshGid = null
        mapRefreshAdapter = null
        mapRefreshScopeGeneration = null
    }

    private fun scheduleNextMapRefresh() {
        if (mapRefreshPaused) return
        mapRefreshRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            val gid = mapRefreshGid
            val adapter = mapRefreshAdapter
            val scopeGeneration = mapRefreshScopeGeneration
            if (gid == null || adapter == null || scopeGeneration == null) return@Runnable
            if (!MapRefreshTimerPolicy.mayLoadAndReschedule(
                    MapGroupScopeToken(gid, scopeGeneration),
                    currentGid,
                    mapGroupScopeGeneration,
                    mapRefreshPaused,
                )
            ) return@Runnable
            if (isDrafting()) {
                scheduleNextMapRefresh()
                return@Runnable
            }
            loadMapItems(gid, adapter)
            scheduleNextMapRefresh()
        }
        mapRefreshRunnable = r
        handler.postDelayed(r, MAP_REFRESH_INTERVAL_MS)
    }

    private fun ensureDraftOverlaysAttached() {
        mapCreationDraftRenderer.render(mapCreationController.currentState().draft)
    }

    private fun isDrafting(): Boolean {
        return ::mapCreationController.isInitialized && mapCreationController.currentState().isDrafting
    }

    private fun focusMarker(marker: MapMarkerEntity) {
        if (marker.gid != currentGid) return
        if (::groupMapController.isInitialized) {
            groupMapController.focus(MapItemIdentity(marker.gid, MapItemType.MARKER, marker.marker_id))
        }
        mapView.controller.setCenter(GeoPoint(marker.lat, marker.lon))
        mapView.controller.setZoom(16.0)
    }

    private fun focusArea(area: MapAreaEntity) {
        if (area.gid != currentGid) return
        if (::groupMapController.isInitialized) {
            groupMapController.focus(MapItemIdentity(area.gid, MapItemType.AREA, area.area_id))
        }
        val points = parsePolygonPoints(area.geometry_json)
        if (points.isEmpty()) return
        val lats = points.map { it.latitude }
        val lons = points.map { it.longitude }
        val box = BoundingBox(
            lats.maxOrNull() ?: return,
            lons.maxOrNull() ?: return,
            lats.minOrNull() ?: return,
            lons.minOrNull() ?: return,
        )
        mapView.zoomToBoundingBox(box, true, 80)
    }

    private fun parsePolygonPoints(raw: String): List<GeoPoint> {
        return try {
            val element = Json.parseToJsonElement(raw)
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return emptyList()
            val coords = obj["coordinates"]?.jsonArray ?: return emptyList()
            val ring = when (type) {
                "Polygon" -> coords.firstOrNull()?.jsonArray
                "MultiPolygon" -> coords.firstOrNull()?.jsonArray?.firstOrNull()?.jsonArray
                else -> null
            } ?: return emptyList()
            ring.mapNotNull { point ->
                val arr = point.jsonArray
                val lon = arr.getOrNull(0)?.jsonPrimitive?.content?.toDoubleOrNull()
                val lat = arr.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull()
                if (lat != null && lon != null) GeoPoint(lat, lon) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun areaGeometrySummary(raw: String): String {
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val coordinates = obj["coordinates"]?.jsonArray
                ?: return getString(R.string.map_area_geometry_unavailable)
            fun visibleVertexCount(ring: JsonArray): Int {
                if (ring.size < 2) return ring.size
                return if (ring.first() == ring.last()) ring.size - 1 else ring.size
            }
            when (obj["type"]?.jsonPrimitive?.content) {
                "Polygon" -> {
                    val vertices = coordinates.firstOrNull()?.jsonArray
                        ?.let(::visibleVertexCount)
                        ?: 0
                    getString(R.string.map_area_geometry_polygon_summary, vertices)
                }
                "MultiPolygon" -> {
                    val outerRings = coordinates.mapNotNull { polygon ->
                        polygon.jsonArray.firstOrNull()?.jsonArray
                    }
                    getString(
                        R.string.map_area_geometry_multipolygon_summary,
                        outerRings.size,
                        outerRings.sumOf(::visibleVertexCount),
                    )
                }
                else -> getString(R.string.map_area_geometry_unavailable)
            }
        } catch (_: Exception) {
            getString(R.string.map_area_geometry_unavailable)
        }
    }

    /** Candidate bounds include every MultiPolygon outer ring; rendering remains unflattened in M3D2. */
    private fun candidateAreaPoints(raw: String): List<GeoPoint> = try {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val coordinates = obj["coordinates"]?.jsonArray
        if (coordinates == null) {
            emptyList()
        } else {
            val rings = when (obj["type"]?.jsonPrimitive?.content) {
                "Polygon" -> listOfNotNull(coordinates.firstOrNull()?.jsonArray)
                "MultiPolygon" -> coordinates.mapNotNull { polygon -> polygon.jsonArray.firstOrNull()?.jsonArray }
                else -> emptyList()
            }
            rings.flatMap { ring -> ring.mapNotNull { point ->
                val values = point.jsonArray
                val lon = values.getOrNull(0)?.jsonPrimitive?.content?.toDoubleOrNull()
                val lat = values.getOrNull(1)?.jsonPrimitive?.content?.toDoubleOrNull()
                if (lat != null && lon != null) GeoPoint(lat, lon) else null
            } }
        }
    } catch (_: Exception) { emptyList() }

    private fun validateAreaConstraints(points: List<GeoPoint>): String? {
        val normalized = normalizePolygon(points)
        if (normalized.size < 3) {
            return getString(R.string.map_area_invalid_polygon)
        }
        val areaMeters = polygonAreaMeters(normalized)
        if (areaMeters < AREA_MIN_SQM) {
            return getString(R.string.map_area_too_small)
        }
        if (hasSharpAngle(normalized, AREA_MIN_ANGLE_DEG)) {
            return getString(R.string.map_area_angle_too_sharp)
        }
        return null
    }

    private fun normalizePolygon(points: List<GeoPoint>): List<GeoPoint> {
        if (points.isEmpty()) return points
        val first = points.first()
        val last = points.last()
        return if (first.latitude == last.latitude && first.longitude == last.longitude) {
            points.dropLast(1)
        } else {
            points
        }
    }

    private fun polygonAreaMeters(points: List<GeoPoint>): Double {
        if (points.size < 3) return 0.0
        val lat0 = points.map { it.latitude }.average()
        val mPerDegLat = 110_540.0
        val mPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(lat0))
        var sum = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            val xi = points[i].longitude * mPerDegLon
            val yi = points[i].latitude * mPerDegLat
            val xj = points[j].longitude * mPerDegLon
            val yj = points[j].latitude * mPerDegLat
            sum += xi * yj - xj * yi
        }
        return kotlin.math.abs(sum) * 0.5
    }

    private fun hasSharpAngle(points: List<GeoPoint>, minDeg: Double): Boolean {
        if (points.size < 3) return true
        val minRad = Math.toRadians(minDeg)
        for (i in points.indices) {
            val prev = points[(i - 1 + points.size) % points.size]
            val curr = points[i]
            val next = points[(i + 1) % points.size]
            val v1x = prev.longitude - curr.longitude
            val v1y = prev.latitude - curr.latitude
            val v2x = next.longitude - curr.longitude
            val v2y = next.latitude - curr.latitude
            val dot = v1x * v2x + v1y * v2y
            val len1 = kotlin.math.hypot(v1x, v1y)
            val len2 = kotlin.math.hypot(v2x, v2y)
            if (len1 == 0.0 || len2 == 0.0) return true
            val cos = (dot / (len1 * len2)).coerceIn(-1.0, 1.0)
            val angle = kotlin.math.acos(cos)
            if (angle < minRad) return true
        }
        return false
    }

    private fun startLocationUpdates() {
        if (usingTrailLocation) return
        if (!hasLocationPermission()) {
            publishLocationAvailability(false, R.string.map_activity_location_permission_needed)
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
        publishLocationAvailability(isLocationEnabled(), R.string.map_activity_location_unavailable)
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = manager
        if (locationListener == null) {
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    updateUserLocation(location)
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }
        }
        try {
            registerLocationUpdates(currentLocationIntervalMs)
            val seed = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (seed != null) {
                updateUserLocation(seed)
            }
        } catch (_: SecurityException) {
            // ignore
        }
    }

    private fun stopLocationUpdates() {
        if (usingTrailLocation) return
        val manager = locationManager ?: return
        val listener = locationListener ?: return
        manager.removeUpdates(listener)
        userHeadingOverlay?.setVisible(false)
    }

    private fun startTrailLocationUpdates() {
        if (usingTrailLocation) return
        usingTrailLocation = true
        stopLocationUpdates()
        val listener = trailLocationListener ?: { location: Location ->
            runOnUiThread { updateUserLocation(location) }
        }.also { trailLocationListener = it }
        TrailRecorder.addLocationListener(listener)
        TrailRecorder.latestLocation()?.let { updateUserLocation(it) }
    }

    private fun stopTrailLocationUpdates() {
        if (!usingTrailLocation) return
        val listener = trailLocationListener
        if (listener != null) {
            TrailRecorder.removeLocationListener(listener)
        }
        usingTrailLocation = false
    }

    private fun registerTrailRecordingObserver() {
        if (trailRecordingListener != null) return
        val listener: (Boolean) -> Unit = { recording ->
            if (recording) {
                startTrailLocationUpdates()
            } else {
                stopTrailLocationUpdates()
                startLocationUpdates()
            }
        }
        trailRecordingListener = listener
        TrailRecorder.addRecordingListener(listener)
    }

    private fun unregisterTrailRecordingObserver() {
        val listener = trailRecordingListener ?: return
        TrailRecorder.removeRecordingListener(listener)
        trailRecordingListener = null
    }

    private fun openLocationSettings() {
        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    private fun isLocationEnabled(): Boolean {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
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

    private fun updateUserLocation(location: Location) {
        val displayLoc = sanitizeDisplayLocation(location) ?: return
        lastLocation = displayLoc
        updateLocationInterval(displayLoc.speed)
        val point = GeoPoint(displayLoc.latitude, displayLoc.longitude)
        lastUserLocation = point
        if (userLocationOverlay == null) {
            userLocationOverlay = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(this@GroupMapActivity, R.drawable.ic_user_dot)
                title = getString(R.string.map_you_are_here)
            }
        }
        userLocationOverlay?.let {
            if (!mapView.overlays.contains(it)) {
                mapView.overlays.add(it)
            }
        }
        userLocationOverlay?.position = point
        val heading = resolveHeading()
        val displayHeading = heading
        val rotation = heading?.let { (360f - it) % 360f }
        updateHeadingOverlay(point, heading)
        updateHeadingDebug(heading, compassHeading, rotation)
        updateSpeedStatus(displayLoc.speed)
        if (!hasCenteredOnUser) {
            followUser(point, heading, updateMotionMode(displayLoc.speed))
            hasCenteredOnUser = true
        }
        mapView.invalidate()
    }

    private enum class MotionMode { WALK, MOTO, CAR }

    private fun updateMotionMode(speedMps: Float): MotionMode {
        val previous = currentMotionMode
        val next = when {
            speedMps >= MOTION_CAR_MIN_MPS -> MotionMode.CAR
            speedMps >= MOTION_MOTO_MIN_MPS -> MotionMode.MOTO
            else -> MotionMode.WALK
        }
        if (next.ordinal > currentMotionMode.ordinal) {
            currentMotionMode = next
            if (next != MotionMode.WALK && previous == MotionMode.WALK) {
                userScrollPauseUntilMs = 0L
                lastUserLocation?.let { point ->
                    followUser(point, resolveHeading(), currentMotionMode)
                }
            }
        }
        return currentMotionMode
    }

    private fun updateSpeedStatus(speedMps: Float) {
        currentSpeedStatus = when {
            speedMps < SPEED_IDLE_MAX_MPS -> SPEED_STATUS_IDLE
            speedMps < MOTION_MOTO_MIN_MPS -> SPEED_STATUS_WALK
            speedMps < MOTION_CAR_MIN_MPS -> SPEED_STATUS_MOTO
            speedMps < HIGH_SPEED_MIN_MPS -> SPEED_STATUS_CAR
            else -> SPEED_STATUS_HIGH
        }
    }

    private fun followUser(point: GeoPoint, heading: Float?, mode: MotionMode) {
        if (mode == MotionMode.WALK || heading == null) {
            centerOnUser()
            return
        }
        val projection = mapView.projection ?: run {
            centerOnUser()
            return
        }
        val center = android.graphics.Point(mapView.width / 2, mapView.height / 2)
        val size = minOf(mapView.width, mapView.height)
        if (size <= 0) {
            centerOnUser()
            return
        }
        val offset = size * FORWARD_OFFSET_RATIO
        val rad = Math.toRadians(heading.toDouble())
        val targetX = center.x - (offset * Math.sin(rad)).toInt()
        val targetY = center.y + (offset * Math.cos(rad)).toInt()
        val userPx = projection.toPixels(point, null)
        val deltaX = targetX - userPx.x
        val deltaY = targetY - userPx.y
        val newCenter = projection.fromPixels(center.x - deltaX, center.y - deltaY) as GeoPoint
        markAutoFollow()
        mapView.controller.animateTo(newCenter, mapView.zoomLevelDouble, FOLLOW_ANIM_MS)
    }

    private fun markUserScrolled() {
        val now = System.currentTimeMillis()
        if (now < autoFollowUntilMs) return
        lastUserScrollAtMs = now
        scrollEndRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - lastUserScrollAtMs
                if (elapsed < SCROLL_END_DEBOUNCE_MS) {
                    handler.postDelayed(this, SCROLL_END_DEBOUNCE_MS - elapsed)
                    return
                }
                userScrollPauseUntilMs = System.currentTimeMillis() + USER_SCROLL_PAUSE_MS
                app.peermesh.data.debug.ActivityLog.add(
                    label = "Driving follow debug",
                    source = "map",
                    message = "pause_for=${USER_SCROLL_PAUSE_MS}ms"
                )
            }
        }
        scrollEndRunnable = r
        handler.postDelayed(r, SCROLL_END_DEBOUNCE_MS)
    }

    private fun startFollowTicker() {
        followRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val point = lastUserLocation
                if (point != null && currentMotionMode != MotionMode.WALK && now >= userScrollPauseUntilMs) {
                    val heading = resolveHeading() ?: lastDisplayBearing
                    followUser(point, heading, currentMotionMode)
                    logFollowIfChanged(heading, currentMotionMode)
                }
                handler.postDelayed(this, FOLLOW_TICK_MS)
            }
        }
        followRunnable = r
        handler.postDelayed(r, FOLLOW_TICK_MS)
    }

    private fun stopFollowTicker() {
        followRunnable?.let { handler.removeCallbacks(it) }
        followRunnable = null
    }

    private fun markAutoFollow() {
        autoFollowUntilMs = System.currentTimeMillis() + FOLLOW_ANIM_MS + AUTO_FOLLOW_GRACE_MS
    }

    private fun updateHeadingDebug(
        heading: Float?,
        compass: Float?,
        rotation: Float?,
    ) {
        // Heading diagnostics are intentionally hidden from consumer UI.
    }

    private fun publishMapActivity(
        dedupeKey: String,
        text: String,
        priority: MapActivityPriority = MapActivityPriority.INFO,
    ) {
        val now = System.currentTimeMillis()
        val currentKey = currentMapActivityMessage()?.dedupeKey
        pruneExpiredMapActivityMessages(now)
        val existingIndex = mapActivityMessages.indexOfFirst { it.dedupeKey == dedupeKey }
        if (existingIndex >= 0) {
            val existing = mapActivityMessages.removeAt(existingIndex)
            val count = existing.count + 1
            mapActivityMessages.add(
                existing.copy(
                    text = if (dedupeKey == "marker_received") {
                        resources.getQuantityString(R.plurals.map_status_marker_updated_count, count, count)
                    } else text,
                    createdAt = now,
                    expiresAt = now + MESSAGE_TTL_MS,
                    count = count,
                )
            )
        } else {
            mapActivityMessages.add(
                MapActivityMessage(dedupeKey, text, priority, now, now + MESSAGE_TTL_MS)
            )
        }
        mapActivityMessages.sortWith(
            compareByDescending<MapActivityMessage> { it.priority.ordinal }.thenBy { it.createdAt }
        )
        mapActivityMessageIndex = mapActivityMessages.indexOfFirst { it.dedupeKey == currentKey }
            .takeIf { it >= 0 } ?: 0
        if (currentKey == null) renderMapActivityBanner()
        startMapActivityRotation()
    }

    private fun startMapActivityRotation() {
        pruneExpiredMapActivityMessages()
        renderMapActivityBanner()
        if (mapActivityMessages.isEmpty() || !isForeground || mapActivityRotationRunnable != null) return

        val runnable = object : Runnable {
            override fun run() {
                pruneExpiredMapActivityMessages()
                if (mapActivityMessages.isNotEmpty()) {
                    mapActivityMessageIndex = (mapActivityMessageIndex + 1) % mapActivityMessages.size
                }
                renderMapActivityBanner()
                if (mapActivityMessages.isNotEmpty() && isForeground) {
                    handler.postDelayed(this, DISPLAY_DURATION_MS)
                } else {
                    mapActivityRotationRunnable = null
                }
            }
        }
        mapActivityRotationRunnable = runnable
        handler.postDelayed(runnable, DISPLAY_DURATION_MS)
    }

    private fun stopMapActivityRotation() {
        mapActivityRotationRunnable?.let { handler.removeCallbacks(it) }
        mapActivityRotationRunnable = null
    }

    private fun pruneExpiredMapActivityMessages(now: Long = System.currentTimeMillis()) {
        mapActivityMessages.removeAll { it.expiresAt <= now }
        if (mapActivityMessageIndex >= mapActivityMessages.size) mapActivityMessageIndex = 0
    }

    private fun currentMapActivityMessage(): MapActivityMessage? {
        pruneExpiredMapActivityMessages()
        return mapActivityMessages.getOrNull(mapActivityMessageIndex)
    }

    private fun renderMapActivityBanner() {
        val message = currentMapActivityMessage()
        if (message == null) {
            mapActivityBanner.visibility = View.GONE
            mapActivityBannerText.text = ""
            return
        }
        mapActivityBannerText.text = message.text
        mapActivityBannerText.contentDescription = message.text
        mapActivityBanner.visibility = View.VISIBLE
    }

    private fun publishLocationAvailability(available: Boolean, unavailableMessage: Int) {
        val previous = lastLocationAvailability
        lastLocationAvailability = available
        if (previous == available) return
        when {
            !available -> publishMapActivity(
                dedupeKey = "location_changed",
                text = getString(unavailableMessage),
                priority = MapActivityPriority.WARNING,
            )
            previous == false -> publishMapActivity(
                dedupeKey = "location_changed",
                text = getString(R.string.map_activity_location_available),
            )
        }
    }

    private fun sanitizeDisplayLocation(location: Location): Location? {
        val last = lastDisplayLocation
        if (last == null) {
            lastDisplayLocation = Location(location)
            return location
        }
        val now = if (location.time > 0L) location.time else System.currentTimeMillis()
        val dt = ((now - last.time).coerceAtLeast(1L)) / 1000f
        val dist = last.distanceTo(location)
        if (dt <= HEADING_JUMP_WINDOW_S && dist >= HEADING_JUMP_DIST_M) {
            return null
        }
        val speed = dist / dt
        if (speed > HEADING_SPEED_MAX_MPS || speed > HEADING_SPEED_JUMP_MPS) {
            return null
        }
        if (lastDisplayLocation != null) {
            lastDisplayBearing = last.bearingTo(location)
        }
        lastDisplayLocation = Location(location).apply { time = now }
        return location
    }

    private fun computeAxisHeading(points: ArrayDeque<Location>): Float? {
        if (points.size < 3) return null
        val list = points.toList()
        val recent = if (list.size > 4) list.takeLast(4) else list
        val filtered = dropJumpPoint(recent)
        if (filtered.size < 2) return null
        if (filtered.size == 2) return filtered[0].bearingTo(filtered[1])
        val p0 = filtered[0]
        val p1 = filtered[1]
        val p2 = filtered[2]
        val b01 = p0.bearingTo(p1)
        val b12 = p1.bearingTo(p2)
        return if (angleDelta(b01, b12) <= HEADING_STRAIGHT_ANGLE_DEG) {
            p0.bearingTo(p2)
        } else {
            b12
        }
    }

    private fun resolveHeading(): Float? {
        val loc = lastLocation
        if (loc != null && loc.speed >= HEADING_SPEED_MIN_MPS) {
            val last = headingPoints.lastOrNull()
            if (last == null || last.distanceTo(loc) >= HEADING_SAMPLE_MIN_DISTANCE_M) {
                headingPoints.addLast(Location(loc))
                while (headingPoints.size > 4) headingPoints.removeFirst()
            }
            val derived = deriveHeadingFromPoints(headingPoints)
            if (derived != null) {
                headingSource = HeadingSource.LOCATION
                smoothedLocationHeading = smoothAngle(
                    smoothedLocationHeading,
                    derived,
                    LOCATION_HEADING_ALPHA
                )
                val smooth = smoothedLocationHeading ?: derived
                logHeadingIfChanged(
                    HeadingSource.LOCATION,
                    smooth,
                    "speed=${"%.2f".format(java.util.Locale.US, loc.speed)} derived=${"%.1f".format(java.util.Locale.US, derived)} smooth=${"%.1f".format(java.util.Locale.US, smooth)} pts=${headingPoints.size}"
                )
                return smoothedLocationHeading
            }
        } else {
            headingPoints.clear()
        }
        val compass = compassHeading
        if (compass != null) {
            headingSource = HeadingSource.COMPASS
            smoothedCompassHeading = smoothAngle(smoothedCompassHeading, compass, COMPASS_HEADING_ALPHA)
            val smooth = smoothedCompassHeading ?: compass
            logHeadingIfChanged(
                HeadingSource.COMPASS,
                smooth,
                "compass=${"%.1f".format(java.util.Locale.US, compass)} smooth=${"%.1f".format(java.util.Locale.US, smooth)}"
            )
            return smoothedCompassHeading
        }
        headingSource = null
        return null
    }

    private fun deriveHeadingFromPoints(points: ArrayDeque<Location>): Float? {
        if (points.size < 3) return null
        val list = points.toList()
        val recent = if (list.size > 4) list.takeLast(4) else list
        if (recent.size >= 3) {
            val p0 = recent[recent.size - 3]
            val p1 = recent[recent.size - 2]
            val p2 = recent[recent.size - 1]
            if (isInertiaJump(p0, p1, p2)) {
                points.removeLast()
                return p0.bearingTo(p1)
            }
        }
        val filtered = dropJumpPoint(recent)
        if (filtered.size < 2) return null
        if (filtered.size == 2) {
            return filtered[0].bearingTo(filtered[1])
        }
        val p0 = filtered[0]
        val p1 = filtered[1]
        val p2 = filtered[2]
        val b01 = p0.bearingTo(p1)
        val b12 = p1.bearingTo(p2)
        return if (angleDelta(b01, b12) <= HEADING_STRAIGHT_ANGLE_DEG) {
            p0.bearingTo(p2)
        } else {
            b12
        }
    }

    private fun angleDelta(a: Float, b: Float): Float {
        val diff = kotlin.math.abs((a - b + 540f) % 360f - 180f)
        return diff
    }

    private fun isInertiaJump(p0: Location, p1: Location, p2: Location): Boolean {
        if (p2.time <= p1.time) return false
        val dt = ((p2.time - p1.time).coerceAtLeast(1L)) / 1000f
        if (dt < INERTIA_MIN_DT_S) return false
        val speed = when {
            p1.hasSpeed() -> p1.speed
            p0.time > 0L && p1.time > p0.time -> {
                val dist = p0.distanceTo(p1)
                val dt01 = ((p1.time - p0.time).coerceAtLeast(1L)) / 1000f
                if (dt01 < INERTIA_MIN_DT_S) return false
                dist / dt01
            }
            else -> 0f
        }
        if (speed < INERTIA_REVERSE_MIN_MPS) return false
        val expected = speed * dt
        if (expected <= 0f) return false
        val bearing01 = p0.bearingTo(p1)
        val bearing12 = p1.bearingTo(p2)
        val reverse = angleDelta(bearing01, bearing12) >= INERTIA_REVERSE_ANGLE_DEG
        val actual = p1.distanceTo(p2)
        if (reverse && speed > INERTIA_ALLOW_REVERSE_MPS && actual > expected * INERTIA_BACKTRACK_RATIO) {
            return true
        }
        if (!reverse && speed >= INERTIA_PROGRESS_MIN_MPS && actual < expected * INERTIA_MIN_PROGRESS_RATIO) {
            return true
        }
        return false
    }

    private fun dropJumpPoint(points: List<Location>): List<Location> {
        if (points.size < 4) return points
        val times = points.map { p -> if (p.time > 0L) p.time else System.currentTimeMillis() }
        val speeds = FloatArray(points.size - 1)
        for (i in 1 until points.size) {
            val dt = ((times[i] - times[i - 1]).coerceAtLeast(1L)) / 1000f
            val dist = points[i - 1].distanceTo(points[i])
            speeds[i - 1] = dist / dt
        }
        var dropIndex: Int? = null
        var dropReason: String? = null
        for (i in 1 until points.size) {
            val dt = ((times[i] - times[i - 1]).coerceAtLeast(1L)) / 1000f
            val dist = points[i - 1].distanceTo(points[i])
            val speed = speeds[i - 1]
            val speedJump = speed > HEADING_SPEED_JUMP_MPS || speed > HEADING_SPEED_MAX_MPS
            val distJump = dist >= HEADING_JUMP_DIST_M && dt <= HEADING_JUMP_WINDOW_S
            if (speedJump || distJump) {
                dropIndex = i
                dropReason = if (speedJump) "speed" else "dist"
                break
            }
        }
        if (dropIndex == null) {
            for (i in 1 until speeds.size) {
                val dt = ((times[i + 1] - times[i - 1]).coerceAtLeast(1L)) / 1000f
                if (dt > HEADING_JUMP_WINDOW_S) continue
                val accel = kotlin.math.abs(speeds[i] - speeds[i - 1])
                if (accel > HEADING_ACCEL_JUMP_MPS) {
                    dropIndex = i
                    dropReason = "accel"
                    break
                }
            }
        }
        if (dropIndex == null) return points.takeLast(3)
        val filtered = points.toMutableList()
        filtered.removeAt(dropIndex)
        return filtered.takeLast(3)
    }

    private fun logHeadingIfChanged(source: HeadingSource, heading: Float, message: String) {
        val now = System.currentTimeMillis()
        val last = lastHeadingLogDeg
        val delta = if (last == null) 180f else angleDelta(last, heading)
        val sourceChanged = source != lastHeadingSourceLog
        if (!sourceChanged && delta < HEADING_LOG_MIN_CHANGE_DEG) return
        if (now - lastHeadingLogAtMs < HEADING_LOG_MIN_INTERVAL_MS) return
        lastHeadingSourceLog = source
        lastHeadingLogAtMs = now
        lastHeadingLogDeg = heading
        logHeadingEvent("heading_source=$source", message)
    }

    private fun logHeadingEvent(event: String, message: String) {
        app.peermesh.data.debug.ActivityLog.add(
            label = "Heading debug",
            source = "map",
            message = "$event $message"
        )
    }

    private fun logFollowIfChanged(heading: Float?, mode: MotionMode) {
        val h = heading ?: return
        val now = System.currentTimeMillis()
        val last = lastFollowLogHeading
        val delta = if (last == null) 180f else angleDelta(last, h)
        if (delta < FOLLOW_LOG_MIN_CHANGE_DEG) return
        if (now - lastFollowLogAtMs < FOLLOW_LOG_MIN_INTERVAL_MS) return
        lastFollowLogAtMs = now
        lastFollowLogHeading = h
        app.peermesh.data.debug.ActivityLog.add(
            label = "Driving follow debug",
            source = "map",
            message = "heading=${"%.1f".format(java.util.Locale.US, h)} mode=$mode"
        )
    }

    private fun updateHeadingOverlay(point: GeoPoint, heading: Float?) {
        if (heading == null) {
            userHeadingOverlay?.setVisible(false)
            return
        }
        if (userHeadingOverlay == null) {
            userHeadingOverlay = Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(this@GroupMapActivity, R.drawable.up_arrow)
                isFlat = true
            }
        }
        userHeadingOverlay?.let {
            if (!mapView.overlays.contains(it)) {
                mapView.overlays.add(it)
            }
        }
        userHeadingOverlay?.position = point
        userHeadingOverlay?.rotation = (360f - heading) % 360f
        userHeadingOverlay?.setVisible(true)
    }

    private fun startCompassUpdates() {
        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager = manager
        val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (rotation != null) {
            hasRotationVector = true
            manager.registerListener(compassListener, rotation, SensorManager.SENSOR_DELAY_UI)
            return
        }
        if (accel == null || magnet == null) return
        hasRotationVector = false
        manager.registerListener(compassListener, accel, SensorManager.SENSOR_DELAY_UI)
        manager.registerListener(compassListener, magnet, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopCompassUpdates() {
        sensorManager?.unregisterListener(compassListener)
        sensorManager = null
        compassHeading = null
        smoothedCompassHeading = null
        smoothedLocationHeading = null
        hasAccel = false
        hasMagnet = false
        hasRotationVector = false
    }

    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    rotationVector = event.values.clone()
                    hasRotationVector = true
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    accelValues = event.values.clone()
                    hasAccel = true
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magnetValues = event.values.clone()
                    hasMagnet = true
                }
            }
            val r = FloatArray(9)
            val i = FloatArray(9)
            if (hasRotationVector) {
                SensorManager.getRotationMatrixFromVector(r, rotationVector)
            } else {
                if (!hasAccel || !hasMagnet) return
                val ok = SensorManager.getRotationMatrix(r, i, accelValues, magnetValues)
                if (!ok) return
            }
            val orientation = FloatArray(3)
            val remapped = FloatArray(9)
            val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.rotation ?: android.view.Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
            when (rotation) {
                android.view.Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                    r,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X,
                    remapped
                )
                android.view.Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                    r,
                    SensorManager.AXIS_MINUS_X,
                    SensorManager.AXIS_MINUS_Y,
                    remapped
                )
                android.view.Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                    r,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_X,
                    remapped
                )
                else -> {
                    System.arraycopy(r, 0, remapped, 0, r.size)
                }
            }
            SensorManager.getOrientation(remapped, orientation)
            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            val normalized = ((azimuth % 360) + 360) % 360
            compassHeading = normalized
            val point = lastUserLocation
            if (point != null) {
                val heading = resolveHeading()
                updateHeadingOverlay(point, heading)
                mapView.invalidate()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun registerLocationUpdates(intervalMs: Long) {
        val manager = locationManager ?: return
        val listener = locationListener ?: return
        if (!hasLocationPermission()) return
        val gpsEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        try {
            if (gpsEnabled) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener)
            }
            if (networkEnabled) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, intervalMs, 0f, listener)
            }
        } catch (_: SecurityException) {
            // ignore
        }
    }

    private fun updateLocationInterval(speed: Float) {
        val desired = when {
            speed > 1.0f -> LOCATION_UPDATE_FAST_MS
            speed > 0.3f -> LOCATION_UPDATE_MED_MS
            else -> LOCATION_UPDATE_SLOW_MS
        }
        if (desired == currentLocationIntervalMs) return
        currentLocationIntervalMs = desired
        val manager = locationManager ?: return
        val listener = locationListener ?: return
        manager.removeUpdates(listener)
        try {
            registerLocationUpdates(currentLocationIntervalMs)
        } catch (_: SecurityException) {
            // ignore
        }
    }

    private fun smoothAngle(previous: Float?, current: Float, alpha: Float): Float {
        val currentRad = Math.toRadians(current.toDouble())
        if (previous == null) return normalizeAngle(current)
        val prevRad = Math.toRadians(previous.toDouble())
        val x = (1 - alpha) * kotlin.math.cos(prevRad) + alpha * kotlin.math.cos(currentRad)
        val y = (1 - alpha) * kotlin.math.sin(prevRad) + alpha * kotlin.math.sin(currentRad)
        val angle = Math.toDegrees(kotlin.math.atan2(y, x)).toFloat()
        return normalizeAngle(angle)
    }

    private fun normalizeAngle(angle: Float): Float {
        val mod = angle % 360f
        return if (mod < 0) mod + 360f else mod
    }

    private fun centerOnUser() {
        val point = lastUserLocation ?: return
        markAutoFollow()
        mapView.controller.setCenter(point)
        if (mapView.zoomLevelDouble < 15.0) {
            mapView.controller.setZoom(15.5)
        }
    }

    private fun bearingLabel(bearing: Float): String {
        val degrees = ((bearing % 360) + 360) % 360
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((degrees + 22.5f) / 45.0f).toInt() % 8
        val rounded = kotlin.math.round(degrees).toInt()
        return "${rounded}° (${dirs[index]})"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION_PERM) {
            if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationUpdates()
            }
        }
    }


    private fun confirmDeleteMarker(gid: String, marker: MapMarkerEntity) {
        val deleteScopeGeneration = mapGroupScopeGeneration
        AlertDialog.Builder(this)
            .setTitle(R.string.map_delete_marker)
            .setMessage(R.string.map_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                submitMarkerDelete(gid, marker) { deleted ->
                    if (deleted && isCurrentMapScope(gid, deleteScopeGeneration)) {
                        refreshMapUi(gid)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteArea(gid: String, area: MapAreaEntity) {
        val deleteScopeGeneration = mapGroupScopeGeneration
        AlertDialog.Builder(this)
            .setTitle(R.string.map_delete_area)
            .setMessage(R.string.map_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                submitAreaDelete(gid, area) { deleted ->
                    if (deleted && isCurrentMapScope(gid, deleteScopeGeneration)) {
                        refreshMapUi(gid)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptRemovalReason(onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.map_removal_reason_hint)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.map_removal_request)
            .setView(input)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val reason = input.text.toString().trim()
                if (reason.isBlank()) {
                    toast(getString(R.string.map_removal_reason_required))
                    return@setOnClickListener
                }
                dialog.dismiss()
                onConfirm(reason)
            }
        }
        dialog.show()
    }

    private fun submitMarkerRemovalRequest(
        gid: String,
        marker: MapMarkerEntity,
        reason: String,
        onResult: (Boolean) -> Unit,
    ) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val ttlSeconds = marker.expires_at?.let { (it - nowSeconds).coerceAtLeast(0L) }?.takeIf { it > 0 }
        submitMarkerAsync(
            gid = gid,
            existing = marker,
            lat = marker.lat,
            lon = marker.lon,
            radius = marker.radius_m,
            kind = marker.kind,
            label = marker.label,
            note = marker.note,
            confidence = marker.confidence,
            ttlSeconds = ttlSeconds,
            removalRequestedT = nowSeconds,
            removalRequestedActor = DeviceIdProvider.get(this),
            removalReason = reason,
            onResult = onResult,
        )
    }

    private fun submitMarkerRemovalCancel(
        gid: String,
        marker: MapMarkerEntity,
        onResult: (Boolean) -> Unit,
    ) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val ttlSeconds = marker.expires_at?.let { (it - nowSeconds).coerceAtLeast(0L) }?.takeIf { it > 0 }
        submitMarkerAsync(
            gid = gid,
            existing = marker,
            lat = marker.lat,
            lon = marker.lon,
            radius = marker.radius_m,
            kind = marker.kind,
            label = marker.label,
            note = marker.note,
            confidence = marker.confidence,
            ttlSeconds = ttlSeconds,
            removalCancelT = nowSeconds,
            onResult = onResult,
        )
    }

    private fun submitAreaRemovalRequest(
        gid: String,
        area: MapAreaEntity,
        reason: String,
        onResult: (Boolean) -> Unit,
    ) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val ttlSeconds = area.expires_at?.let { (it - nowSeconds).coerceAtLeast(0L) }?.takeIf { it > 0 }
        submitAreaAsync(
            gid = gid,
            existing = area,
            geometry = kotlinx.serialization.json.Json.parseToJsonElement(area.geometry_json),
            kind = area.kind,
            label = area.label,
            note = area.note,
            confidence = area.confidence,
            ttlSeconds = ttlSeconds,
            removalRequestedT = nowSeconds,
            removalRequestedActor = DeviceIdProvider.get(this),
            removalReason = reason,
            onResult = onResult,
        )
    }

    private fun submitAreaRemovalCancel(
        gid: String,
        area: MapAreaEntity,
        onResult: (Boolean) -> Unit,
    ) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val ttlSeconds = area.expires_at?.let { (it - nowSeconds).coerceAtLeast(0L) }?.takeIf { it > 0 }
        submitAreaAsync(
            gid = gid,
            existing = area,
            geometry = kotlinx.serialization.json.Json.parseToJsonElement(area.geometry_json),
            kind = area.kind,
            label = area.label,
            note = area.note,
            confidence = area.confidence,
            ttlSeconds = ttlSeconds,
            removalCancelT = nowSeconds,
            onResult = onResult,
        )
    }

    private fun requestMarkerRemovalFromList(gid: String, marker: MapMarkerEntity) {
        promptRemovalReason { reason ->
            submitMarkerRemovalRequest(gid, marker, reason) { ok ->
                if (ok) refreshMapUi(gid)
            }
        }
    }

    private fun requestAreaRemovalFromList(gid: String, area: MapAreaEntity) {
        promptRemovalReason { reason ->
            submitAreaRemovalRequest(gid, area, reason) { ok ->
                if (ok) refreshMapUi(gid)
            }
        }
    }

    private fun submitMarkerAsync(
        gid: String,
        existing: MapMarkerEntity?,
        lat: Double,
        lon: Double,
        radius: Double?,
        kind: String?,
        label: String?,
        note: String?,
        confidence: Int,
        ttlSeconds: Long?,
        removalRequestedT: Long? = null,
        removalRequestedActor: String? = null,
        removalReason: String? = null,
        removalCancelT: Long? = null,
        onResult: (Boolean) -> Unit,
    ) {
        if (existing == null) {
            onResult(false)
            return
        }
        val nowSeconds = System.currentTimeMillis() / 1000L
        val lamport = System.currentTimeMillis()
        val actorDid = DeviceIdProvider.get(this)
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val markerId = existing.marker_id
        val core = EventFactory.mapMarkerUpdate(
                eventId = eventId,
                actorDid = actorDid,
                createdSeconds = nowSeconds,
                lamport = lamport,
                markerId = markerId,
                layerId = existing.layer_id,
                lat = lat,
                lon = lon,
                radiusM = radius,
                label = label,
                note = note,
                kind = kind,
                confidence = confidence,
                ttlSeconds = ttlSeconds,
                removalRequestedT = removalRequestedT,
                removalRequestedActor = removalRequestedActor,
                removalReason = removalReason,
                removalCancelT = removalCancelT,
            )
        runInBackground {
            val ok = storeAndBroadcast(gid, withCanonicalRoot(gid, MapItemType.MARKER, markerId, core))
            runOnUiThread { onResult(ok) }
        }
    }

    private fun submitAreaAsync(
        gid: String,
        existing: MapAreaEntity?,
        geometry: JsonElement,
        kind: String?,
        label: String?,
        note: String?,
        confidence: Int,
        ttlSeconds: Long?,
        removalRequestedT: Long? = null,
        removalRequestedActor: String? = null,
        removalReason: String? = null,
        removalCancelT: Long? = null,
        onResult: (Boolean) -> Unit,
    ) {
        if (existing == null) {
            onResult(false)
            return
        }
        val nowSeconds = System.currentTimeMillis() / 1000L
        val lamport = System.currentTimeMillis()
        val actorDid = DeviceIdProvider.get(this)
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val areaId = existing.area_id
        val core = EventFactory.mapAreaUpdate(
                eventId = eventId,
                actorDid = actorDid,
                createdSeconds = nowSeconds,
                lamport = lamport,
                areaId = areaId,
                layerId = existing.layer_id,
                geometry = geometry,
                label = label,
                note = note,
                kind = kind,
                confidence = confidence,
                ttlSeconds = ttlSeconds,
                removalRequestedT = removalRequestedT,
                removalRequestedActor = removalRequestedActor,
                removalReason = removalReason,
                removalCancelT = removalCancelT,
            )
        runInBackground {
            val ok = storeAndBroadcast(gid, withCanonicalRoot(gid, MapItemType.AREA, areaId, core))
            runOnUiThread { onResult(ok) }
        }
    }

    private fun submitMarkerDelete(
        gid: String,
        marker: MapMarkerEntity,
        onResult: (Boolean) -> Unit,
    ) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val lamport = System.currentTimeMillis()
        val actorDid = DeviceIdProvider.get(this)
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val core = EventFactory.mapMarkerDelete(
            eventId = eventId,
            actorDid = actorDid,
            createdSeconds = nowSeconds,
            lamport = lamport,
            markerId = marker.marker_id,
        )
        runInBackground {
            val deleted = storeAndBroadcast(
                gid,
                withCanonicalRoot(gid, MapItemType.MARKER, marker.marker_id, core),
            )
            runOnUiThread { onResult(deleted) }
        }
    }

    private fun submitAreaDelete(
        gid: String,
        area: MapAreaEntity,
        onResult: (Boolean) -> Unit,
    ) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val lamport = System.currentTimeMillis()
        val actorDid = DeviceIdProvider.get(this)
        val eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce())
        val core = EventFactory.mapAreaDelete(
            eventId = eventId,
            actorDid = actorDid,
            createdSeconds = nowSeconds,
            lamport = lamport,
            areaId = area.area_id,
        )
        runInBackground {
            val deleted = storeAndBroadcast(
                gid,
                withCanonicalRoot(gid, MapItemType.AREA, area.area_id, core),
            )
            runOnUiThread { onResult(deleted) }
        }
    }

    private fun runInBackground(block: () -> Unit) {
        Thread {
            runCatching { block() }
        }.start()
    }

    private fun withCanonicalRoot(
        gid: String,
        itemType: MapItemType,
        itemId: String,
        core: EventCore,
    ): EventCore {
        val db = AppDatabaseProvider.get(applicationContext)
        val persistedType = if (itemType == MapItemType.MARKER) "marker" else "area"
        val root = db.mapItemDomainStateDao()
            .find(gid, persistedType, itemId)
            ?.canonical_add_event_id
            ?: db.eventsDao()
                .listByTypeAndTarget(
                    gid,
                    if (itemType == MapItemType.MARKER) EventTypes.MAP_MARKER_ADD else EventTypes.MAP_AREA_ADD,
                    itemId,
                )
                .firstOrNull()
                ?.event_id
        return if (root.isNullOrBlank()) core else core.copy(ref = core.ref.copy(root = root))
    }

    private fun storeAndBroadcast(gid: String, core: EventCore): Boolean {
        if (!PeerMeshEventPolicy.canGenerateLocal(core.type)) {
            ActivityLog.add(
                label = "Map event blocked",
                source = "map",
                message = "type=${core.type} reason=${PeerMeshEventPolicy.deniedReason(core.type)}",
                eventId = core.id,
                gid = gid,
            )
            runOnUiThread {
                toast(getString(R.string.pro_unlock_map_title))
            }
            return false
        }
        val db = AppDatabaseProvider.get(this)
        if (GroupMembershipPolicy.isPeerRemoved(db, gid, DeviceIdProvider.get(this))) {
            runOnUiThread {
                toast(getString(R.string.group_removed_cannot_participate))
            }
            return false
        }
        val nowSeconds = System.currentTimeMillis() / 1000L
        val coreWithKid = KeyIdHelper.attachKeyId(this, gid, core)
        val localActorDid = DeviceIdProvider.get(this)
        val outcome = localMapEventWriter.write(
            LocalMapWriteRequest(gid, coreWithKid, localActorDid, nowSeconds),
        )
        if (outcome !is LocalMapWriteOutcome.AcceptedNew) {
            ActivityLog.add(
                label = "Local map write outcome",
                source = "map",
                message = when (outcome) {
                    is LocalMapWriteOutcome.AcceptedNoChange -> "accepted_no_change"
                    is LocalMapWriteOutcome.ExactDuplicate -> "exact_duplicate"
                    is LocalMapWriteOutcome.ConflictingDuplicate -> "conflicting_duplicate"
                    is LocalMapWriteOutcome.SemanticRejected -> "semantic_rejected:${outcome.reason.persistedValue}"
                    is LocalMapWriteOutcome.InvalidPersistedState -> "invalid_persisted_state:${outcome.detail}"
                    is LocalMapWriteOutcome.PersistenceFailure -> "persistence_failure:${outcome.cause.javaClass.simpleName}"
                    is LocalMapWriteOutcome.AcceptedNew -> error("handled above")
                },
                eventId = coreWithKid.id,
                gid = gid,
            )
            if (outcome is LocalMapWriteOutcome.AcceptedNoChange) return true
            if (outcome is LocalMapWriteOutcome.SemanticRejected && outcome.reason in setOf(
                    MapEventRejectionReason.ACTOR_NOT_MEMBER,
                    MapEventRejectionReason.LOCAL_MEMBER_REMOVED,
                )
            ) {
                toast(getString(R.string.group_removed_cannot_participate))
            } else {
                toast(getString(R.string.map_event_failed))
            }
            return false
        }

        when (val transport = LocalMapPostCommitTransport.dispatch(
            outcome = outcome,
            core = coreWithKid,
            hasGroupKey = { SecureKeyStore(this).loadGroupKey(gid) != null },
            hasBlePermission = ::hasBlePermissions,
            isBleReady = ::isBleReady,
            enqueue = { exactCore ->
                UserOutbox.enqueue(
                context = this,
                gid = gid,
                core = exactCore,
                source = "mesh",
                successLabel = "Sent map event",
                successMessage = coreWithKid.type,
                )
            },
        )) {
            LocalMapPostCommitTransport.Result.MissingGroupKey -> toast(getString(R.string.map_group_key_missing))
            is LocalMapPostCommitTransport.Result.Enqueued -> if (!transport.bleReady && !transport.hasBlePermission) {
                toast(getString(R.string.map_ble_permission_required))
            }
            is LocalMapPostCommitTransport.Result.EnqueueFailed -> {
                ActivityLog.add("Map transport enqueue failed", "mesh", transport.cause.javaClass.simpleName, coreWithKid.id, gid)
                if (!transport.bleReady && !transport.hasBlePermission) toast(getString(R.string.map_ble_permission_required))
            }
            LocalMapPostCommitTransport.Result.NotEligible -> Unit
        }

        return true
    }

    private fun parseGeometry(raw: String): JsonElement? {
        return runCatching { CanonicalJson.json.parseToJsonElement(raw) }.getOrNull()
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

    private fun toast(message: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            handler.post {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadGroupProfile(gid: String) {
        val scopeGeneration = mapGroupScopeGeneration
        val db = AppDatabaseProvider.get(this)
        Thread {
            val group = db.groupDao().findGroup(gid)
            val profile = group?.context_profile
            runOnUiThread {
                if (!isCurrentMapScope(gid, scopeGeneration)) return@runOnUiThread
                groupProfile = if (profile.isNullOrBlank()) MapKinds.PROFILE_URBAN else profile
            }
        }.start()
    }

    companion object {
        const val EXTRA_GID = "extra_gid"
        const val EXTRA_GROUP_NAME = "extra_group_name"
        const val EXTRA_TRAIL_ID = "extra_trail_id"
        @Volatile
        var isForeground: Boolean = false
        private const val MAX_ITEMS = 200
        private const val LIVE_TRAIL_STALE_SEC = 120L
        private const val LIVE_TRAIL_RECONNECTED_MS = 120_000L
        private const val LIVE_TRAIL_CHANNEL_ID = "live_trail_share_pending"
        private const val LIVE_TRAIL_RETRY_DELAY_MS = 3_000L
        private const val LIVE_TRAIL_RETRY_MAX = 3
        private const val LIVE_TRAIL_REFRESH_DEBOUNCE_MS = 10_000L
        private const val REMOTE_MAP_REFRESH_DEBOUNCE_MS = 500L
        private const val MAP_REFRESH_INTERVAL_MS = 60_000L
        private const val LOCATION_UPDATE_MS = 15_000L
        private const val LABEL_MIN_ZOOM = 15.0
        private const val RADIUS_MIN_ZOOM = 14.5
        private const val MAP_SEMANTIC_MEDIUM_ZOOM = 13.5
        private const val MAP_SEMANTIC_NEAR_ZOOM = 15.0
        private const val MAP_SEMANTIC_MODERATE_COUNT = 25
        private const val MAP_SEMANTIC_DENSE_COUNT = 75
        private const val REQ_LOCATION_PERM = 3001
        private const val KEY_LOCATION_PROMPT_SHOWN = "key_location_prompt_shown"
        private const val LOCATION_UPDATE_FAST_MS = 5_000L
        private const val LOCATION_UPDATE_MED_MS = 10_000L
        private const val LOCATION_UPDATE_SLOW_MS = 30_000L
        private const val MAP_WEIGHT_EXPANDED = 14f
        private const val LIST_WEIGHT_EXPANDED = 6f
        private const val MAP_WEIGHT_COLLAPSED = 0f
        private const val LIST_WEIGHT_COLLAPSED = 20f
        private const val MAP_WEIGHT_MAX = 20f
        private const val LIST_WEIGHT_MAX = 0f
        private const val MAP_STATE_EXPANDED = 0
        private const val MAP_STATE_COLLAPSED = 1
        private const val MAP_STATE_MAX = 2
        private const val LOCATION_HEADING_ALPHA = 0.35f
        private const val COMPASS_HEADING_ALPHA = 0.15f
        private const val HEADING_SPEED_MIN_MPS = 1.0f
        private const val HEADING_SAMPLE_MIN_DISTANCE_M = 3f
        private const val HEADING_STRAIGHT_ANGLE_DEG = 20f
        private const val HEADING_JUMP_DIST_M = 20f
        private const val HEADING_JUMP_WINDOW_S = 2.0f
        private const val HEADING_SPEED_JUMP_MPS = 20f
        private const val HEADING_SPEED_MAX_MPS = 33.33f
        private const val HEADING_ACCEL_JUMP_MPS = 5f
        private const val HEADING_LOG_MIN_CHANGE_DEG = 8f
        private const val HEADING_LOG_MIN_INTERVAL_MS = 1500L
        private const val FOLLOW_LOG_MIN_CHANGE_DEG = 8f
        private const val FOLLOW_LOG_MIN_INTERVAL_MS = 1500L
        private const val MARKER_PROXIMITY_GUARD_M = 200f
        private const val AREA_PROXIMITY_GUARD_M = 200f
        private const val AREA_PROXIMITY_GUARD_RATIO = 0.15f
        private const val AREA_PROXIMITY_GUARD_RATIO_LARGE = 0.1f
        private const val AREA_PROXIMITY_GUARD_RATIO_PIVOT_M = 3000f
        private const val AREA_PROXIMITY_GUARD_MAX_M = 3000f
        private const val MARKER_PROXIMITY_GUARD_RADIUS_RATIO = 0.5f
        private const val MARKER_PROXIMITY_GUARD_MAX_M = 3000f
        private const val MARKER_AREA_COOLDOWN_SEC = 10 * 60L
        private const val DEFAULT_MARKER_RADIUS_M = 10f
        private const val DEBUG_HEADING_OVERLAY = true
        private const val HEADING_ROTATION_OFFSET_DEG = 0f
        private const val MOTION_MOTO_MIN_MPS = 2.2f
        private const val MOTION_CAR_MIN_MPS = 12.0f
        private const val FORWARD_OFFSET_RATIO = 0.25f
        private const val FOLLOW_ANIM_MS = 450L
        private const val FOLLOW_TICK_MS = 15_000L
        private const val USER_SCROLL_PAUSE_MS = 60_000L
        private const val SCROLL_END_DEBOUNCE_MS = 600L
        private const val AUTO_FOLLOW_GRACE_MS = 200L
        private const val SPEED_IDLE_MAX_MPS = 0.5f
        private const val HIGH_SPEED_MIN_MPS = 33.33f
        private const val SPEED_STATUS_IDLE = 0
        private const val SPEED_STATUS_WALK = 1
        private const val SPEED_STATUS_MOTO = 2
        private const val SPEED_STATUS_CAR = 3
        private const val SPEED_STATUS_HIGH = 4
        private const val INERTIA_REVERSE_MIN_MPS = 1.0f
        private const val INERTIA_MIN_DT_S = 0.5f
        // Test values. After verification, set to min=10m and max=6h.
        private const val EDIT_LOCK_MIN_SEC = 10 * 60L
        private const val EDIT_LOCK_MAX_SEC = 6 * 60 * 60L
        private const val EDIT_LOCK_RATIO = 0.5
        private const val TRAIL_LINE_WIDTH = 10f
        private const val TRAIL_DOWNSAMPLE_ZOOM_MED = 15.0
        private const val TRAIL_DOWNSAMPLE_ZOOM_FAR = 13.5
        private const val TRAIL_DOWNSAMPLE_STEP_MED = 2
        private const val TRAIL_DOWNSAMPLE_STEP_FAR = 4
        private const val TRAIL_VIEWPORT_PAD_RATIO = 0.2
        private const val TRAIL_STOP_MAX_MPS = 0.5f
        private const val TRAIL_WALK_MAX_MPS = 2.2f
        private const val TRAIL_MOTO_MAX_MPS = 12.0f
        private val TRAIL_COLOR_STOP = android.graphics.Color.argb(200, 230, 57, 53)
        private val TRAIL_COLOR_WALK = android.graphics.Color.argb(200, 255, 152, 0)
        private val TRAIL_COLOR_MOTO = android.graphics.Color.argb(200, 255, 214, 0)
        private val TRAIL_COLOR_CAR = android.graphics.Color.argb(200, 56, 142, 60)
        private val TRAIL_COLOR_HIGH = android.graphics.Color.argb(200, 156, 39, 176)
        private val TRAIL_COLOR_JUMP = android.graphics.Color.argb(200, 158, 158, 158)
        private val TRAIL_START_COLOR = android.graphics.Color.argb(220, 76, 175, 80)
        private val TRAIL_END_COLOR = android.graphics.Color.argb(220, 229, 57, 53)
        private const val TRAIL_RENDER_DELAY_MS = 500L
        private const val TRAIL_GAP_SAVER_SECONDS = 60L
        private const val TRAIL_GAP_MIN_SPEED_MPS = 0.5f
        private const val TRAIL_GAP_MIN_DT_S = 2L
        private const val TRAIL_GAP_EXPECTED_MULT = 2f
        private const val TRAIL_GAP_SKIP_MIN_SECONDS = 45L
        private const val TRAIL_GAP_SKIP_MIN_COUNT = 6
        private const val TRAIL_POINT_HIDE_MS = 10_000L
        private const val TRAIL_JUMP_DASH_ON_PX = 16f
        private const val TRAIL_JUMP_DASH_OFF_PX = 12f
        private const val MAP_VIEWPORT_PAD_RATIO = 0.15
        private const val MAX_AREA_CACHE_ITEMS = 200
        private const val INERTIA_ALLOW_REVERSE_MPS = 1.0f
        private const val INERTIA_PROGRESS_MIN_MPS = 5f
        private const val INERTIA_REVERSE_ANGLE_DEG = 120f
        private const val INERTIA_BACKTRACK_RATIO = 0.2f
        private const val INERTIA_MIN_PROGRESS_RATIO = 0.2f
        private const val AREA_MIN_SQM = 10_000.0
        private const val AREA_MIN_ANGLE_DEG = 45.0
        private const val DRAFT_AREA_TOAST_COOLDOWN_MS = 10_000L
        private const val DISPLAY_DURATION_MS = 10_000L
        private const val MESSAGE_TTL_MS = 5 * 60_000L
    }
}
