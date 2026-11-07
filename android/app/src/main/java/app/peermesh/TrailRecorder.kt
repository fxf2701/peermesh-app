package app.peermesh

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Bundle
import java.util.UUID
import android.os.Handler
import android.os.Looper
import app.peermesh.data.debug.ActivityLog
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import java.util.concurrent.CopyOnWriteArrayList

object TrailRecorder {
    private const val TAG = "TrailRecorder"
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_TRAIL_ENABLED = "trail_enabled"
    private const val KEY_TRAIL_MODE = "trail_mode"
    private const val KEY_TRAIL_BG_AUTO = "trail_bg_auto"
    private const val MODE_MANUAL = TrailRecordingMode.MANUAL
    private const val MODE_AUTO = TrailRecordingMode.AUTOMATIC

    private const val AUTO_START_DISTANCE_M = 80f
    private const val AUTO_STOP_IDLE_MS = 8 * 60_000L
    private const val LOCATION_UPDATE_MS = 60_000L
    private const val ACCURACY_MAX_M = 50f
    private const val ACCURACY_RETRY_DELAY_MS = 3_000L
    private const val ACCURACY_RETRY_DELAY_STEP_2_MS = 10_000L
    private const val ACCURACY_RETRY_DELAY_MAX_MS = 30_000L
    private const val DEADZONE_ACCURACY_MULT = 1.5f
    private const val JUMP_DISTANCE_M = 80f
    private const val JUMP_WINDOW_S = 3.0f
    private const val WALK_JUMP_WINDOW_S = 10.0f
    private const val WALK_JUMP_ACC_MULT = 2.0f
    private const val WALK_JUMP_MAX_SPEED_MPS = 1.0f

    private var locationManager: LocationManager? = null
    private var listener: LocationListener? = null
    private var fusedClient: com.google.android.gms.location.FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null
    private var currentFusedIntervalMs: Long = 0L
    private var recording = false
    private var currentTrailId: String? = null
    private var currentTrailName: String? = null
    private var currentPoints = mutableListOf<TrailPoint>()
    private var currentStartT: Long = 0L
    private var lastPersistAt: Long = 0L
    private var persistedCount: Int = 0
    private var totalPointCount: Int = 0
    private var lastMoveAt: Long = 0L
    private var anchorLocation: Location? = null
    private var idleAnchorLocation: Location? = null
    private var lastLocation: Location? = null
    private var lastAcceptedLocation: Location? = null
    private var lastAcceptedAtMs: Long = 0L
    private var lastAcceptedSpeedMps: Float = 0f
    private val headingBuffer = ArrayDeque<Location>(4)
    private var lastMotionMode: Int = 0
    private var lastTrailLocation: Location? = null
    private var lastDistanceLocation: Location? = null
    private var lastDistanceSpeedMps: Float? = null
    private var lastDistanceBearing: Float? = null
    private var totalDistanceM: Float = 0f
    private var currentTrailUnstable: Boolean = false
    private var currentTrailUnstableNote: String? = null
    private val locationListeners = CopyOnWriteArrayList<(Location) -> Unit>()
    private val recordingListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private var onTrailSaved: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var idleCheck: Runnable? = null
    private var persistCheck: Runnable? = null
    private var accuracyRetry: Runnable? = null
    private var accuracyRetryDelayMs: Long = ACCURACY_RETRY_DELAY_MS
    private var autoStartCooldownUntilMs: Long = 0L
    private val savingListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    @Volatile private var savingInProgress: Boolean = false
    @Volatile private var persistQueued: Boolean = false
    @Volatile private var persistQueuedForce: Boolean = false
    @Volatile private var persistQueuedFinal: Boolean = false
    @Volatile private var pendingDeleteTrailId: String? = null

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRAIL_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled && !PeerMeshCapabilities.canUseTrail) {
            disableForEntitlement(context)
            return
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TRAIL_ENABLED, enabled).apply()
        if (enabled) {
            startLocationUpdates(context)
            ensureAutoWatch(context)
        } else {
            stopLocationUpdates()
            stopRecording(context)
            if (!recording && !savingInProgress && !shouldAutoWatch(context)) {
                TrailForegroundService.stop(context)
            }
        }
    }

    fun getMode(context: Context): String {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TRAIL_MODE, MODE_MANUAL) ?: MODE_MANUAL
        return if (!PeerMeshCapabilities.canUseAutoTrail && stored == MODE_AUTO) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TRAIL_MODE, MODE_MANUAL)
                .putBoolean(KEY_TRAIL_BG_AUTO, false)
                .apply()
            MODE_MANUAL
        } else {
            stored
        }
    }

    fun setMode(context: Context, mode: String) {
        val resolvedMode = if (!PeerMeshCapabilities.canUseAutoTrail && mode == MODE_AUTO) {
            MODE_MANUAL
        } else {
            mode
        }
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRAIL_MODE, resolvedMode)
        if (!PeerMeshCapabilities.canUseAutoTrail) {
            editor.putBoolean(KEY_TRAIL_BG_AUTO, false)
        }
        editor.apply()
        ensureAutoWatch(context)
    }

    fun getBackgroundAuto(context: Context): Boolean {
        if (!PeerMeshCapabilities.canUseAutoTrail) return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRAIL_BG_AUTO, false)
    }

    fun setBackgroundAuto(context: Context, enabled: Boolean) {
        if (enabled && !PeerMeshCapabilities.canUseTrail) {
            disableForEntitlement(context)
            return
        }
        val resolvedEnabled = enabled && PeerMeshCapabilities.canUseAutoTrail
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TRAIL_BG_AUTO, resolvedEnabled).apply()
        ensureAutoWatch(context)
    }

    fun isAutoWatchEnabled(context: Context): Boolean {
        return PeerMeshCapabilities.canUseAutoTrail && shouldAutoWatch(context)
    }

    fun isRecording(): Boolean = recording

    fun isSaving(): Boolean = savingInProgress

    fun currentStartSeconds(): Long = currentStartT

    fun currentPointCount(): Int = totalPointCount

    fun currentElapsedSeconds(): Long {
        if (!recording || currentStartT <= 0L) return 0L
        return (System.currentTimeMillis() / 1000L) - currentStartT
    }

    fun currentDistanceMeters(): Float = totalDistanceM

    fun latestLocation(): Location? = lastLocation

    fun addLocationListener(listener: (Location) -> Unit) {
        locationListeners.addIfAbsent(listener)
    }

    fun removeLocationListener(listener: (Location) -> Unit) {
        locationListeners.remove(listener)
    }

    fun addRecordingListener(listener: (Boolean) -> Unit) {
        recordingListeners.addIfAbsent(listener)
        listener(recording)
    }

    fun removeRecordingListener(listener: (Boolean) -> Unit) {
        recordingListeners.remove(listener)
    }

    fun currentTrailId(): String? = if (recording) currentTrailId else null

    fun isLocationPermissionReady(context: Context): Boolean {
        return hasLocationPermission(context)
    }

    fun isGpsEnabled(context: Context): Boolean {
        return isGpsProviderEnabled(context)
    }

    fun startManual(context: Context) {
        if (!PeerMeshCapabilities.canUseTrail) {
            disableForEntitlement(context)
            return
        }
        if (!isEnabled(context)) return
        if (!isLocationPermissionReady(context)) {
            handlePermissionMissing(context)
            return
        }
        if (recording) return
        startRecording(context)
    }

    fun stopManual(context: Context) {
        if (!recording) return
        stopRecording(context)
    }

    fun setOnTrailSaved(listener: (() -> Unit)?) {
        onTrailSaved = listener
    }

    fun addSavingListener(listener: (Boolean) -> Unit) {
        savingListeners.add(listener)
    }

    fun removeSavingListener(listener: (Boolean) -> Unit) {
        savingListeners.remove(listener)
    }

    fun onAppForeground(context: Context) {
        if (!PeerMeshCapabilities.canUseTrail) {
            disableForEntitlement(context)
            return
        }
        if (isEnabled(context)) {
            startLocationUpdates(context)
        }
    }

    fun onAppBackground(context: Context) {
        if (!PeerMeshCapabilities.canUseTrail) {
            disableForEntitlement(context)
            return
        }
        if (!recording && !shouldAutoWatch(context)) {
            stopLocationUpdates()
        }
    }

    fun onServiceStart(context: Context) {
        if (!PeerMeshCapabilities.canUseTrail) {
            disableForEntitlement(context)
            return
        }
        startLocationUpdates(context)
    }

    fun onServiceStop(context: Context) {
        if (!recording && !shouldAutoWatch(context)) {
            stopLocationUpdates()
        }
    }

    fun disableForEntitlement(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_TRAIL_ENABLED, false)
            .putBoolean(KEY_TRAIL_BG_AUTO, false)
            .apply()
        stopLocationUpdates()
        stopRecording(context)
        if (!savingInProgress) {
            TrailForegroundService.stop(context)
        }
    }

    fun suppressAutoStart(durationMs: Long) {
        val now = System.currentTimeMillis()
        autoStartCooldownUntilMs = maxOf(autoStartCooldownUntilMs, now + durationMs)
    }

    fun handlePermissionMissing(context: Context) {
        if (recording) {
            pendingDeleteTrailId = currentTrailId
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TRAIL_MODE, MODE_MANUAL)
            .putBoolean(KEY_TRAIL_BG_AUTO, false)
            .apply()
        stopLocationUpdates()
        stopRecording(context, discard = true)
        if (!savingInProgress) {
            pendingDeleteTrailId?.let { id ->
                TrailStore.delete(context, id)
                pendingDeleteTrailId = null
            }
            TrailForegroundService.stop(context)
        }
    }

    fun handleGpsDisabled(context: Context) {
        setMode(context, MODE_MANUAL)
        setBackgroundAuto(context, false)
        if (recording) {
            markTrailUnstable("gps_off")
        }
    }

    private fun startLocationUpdates(context: Context) {
        if (!hasLocationPermission(context)) {
            handlePermissionMissing(context)
            return
        }
        if (isFusedAvailable(context)) {
            stopLocationManagerUpdates()
            startFusedUpdates(context, LOCATION_UPDATE_MS)
        } else {
            stopFusedUpdates()
            startLocationManagerUpdates(context, LOCATION_UPDATE_MS, includeNetwork = false)
        }
    }

    private fun stopLocationUpdates() {
        clearAccuracyRetry()
        stopLocationManagerUpdates()
        stopFusedUpdates()
    }

    private fun scheduleAccuracyRetry(context: Context) {
        if (accuracyRetry != null) return
        val app = context.applicationContext
        val delayMs = accuracyRetryDelayMs
        accuracyRetryDelayMs = when (accuracyRetryDelayMs) {
            ACCURACY_RETRY_DELAY_MS -> ACCURACY_RETRY_DELAY_STEP_2_MS
            else -> ACCURACY_RETRY_DELAY_MAX_MS
        }
        val r = Runnable {
            accuracyRetry = null
            requestAccuracyRetryLocation(app)
        }
        accuracyRetry = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun clearAccuracyRetry() {
        accuracyRetry?.let { mainHandler.removeCallbacks(it) }
        accuracyRetry = null
        accuracyRetryDelayMs = ACCURACY_RETRY_DELAY_MS
    }

    private fun requestAccuracyRetryLocation(context: Context) {
        if (!isEnabled(context) && !recording && !shouldAutoWatch(context)) return
        if (!hasLocationPermission(context) || !isLocationEnabled(context)) return
        if (isFusedAvailable(context)) {
            val client = fusedClient ?: LocationServices.getFusedLocationProviderClient(context).also {
                fusedClient = it
            }
            try {
                val tokenSource = com.google.android.gms.tasks.CancellationTokenSource()
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            onLocation(context, location)
                        }
                    }
            } catch (_: SecurityException) {
                // ignore
            }
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            val provider = when {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> return
            }
            manager.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onLocation(context, location)
                }
            }, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // ignore
        }
    }

    private fun startLocationManagerUpdates(
        context: Context,
        intervalMs: Long = LOCATION_UPDATE_MS,
        includeNetwork: Boolean = true,
    ) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = manager
        if (listener == null) {
            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onLocation(context, location)
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }
        }
        val l = listener ?: return
        try {
            val gpsEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (gpsEnabled) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, l)
            }
            if (includeNetwork && (!gpsEnabled) && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, intervalMs, 0f, l)
            }
        } catch (_: SecurityException) {
            // ignore
        }
    }

    private fun stopLocationManagerUpdates() {
        val manager = locationManager ?: return
        val l = listener ?: return
        manager.removeUpdates(l)
    }

    private fun startFusedUpdates(context: Context, fixedIntervalMs: Long) {
        val client = fusedClient ?: LocationServices.getFusedLocationProviderClient(context).also {
            fusedClient = it
        }
        if (fusedCallback == null) {
            fusedCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach { location -> onLocation(context, location) }
                }
            }
        }
        val callback = fusedCallback ?: return
        if (fixedIntervalMs > 0L) {
            currentFusedIntervalMs = fixedIntervalMs
        } else {
            currentFusedIntervalMs = LOCATION_UPDATE_MS
        }
        try {
            client.requestLocationUpdates(
                buildFusedRequest(currentFusedIntervalMs),
                callback,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            // ignore
        }
    }

    private fun stopFusedUpdates() {
        val client = fusedClient ?: return
        val callback = fusedCallback ?: return
        client.removeLocationUpdates(callback)
    }

    private fun buildFusedRequest(intervalMs: Long): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setMaxUpdateDelayMillis(intervalMs * 2)
            .build()
    }

    private fun isFusedAvailable(context: Context): Boolean {
        return GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private fun onLocation(context: Context, location: Location) {
        val nowMs = if (location.time > 0L) location.time else System.currentTimeMillis()
        ActivityLog.add(
            label = "Trail location update",
            source = "trail",
            message = "recording=$recording lat=${"%.5f".format(java.util.Locale.US, location.latitude)} lon=${"%.5f".format(java.util.Locale.US, location.longitude)} acc=${if (location.hasAccuracy()) location.accuracy else null} spd=${if (location.hasSpeed()) location.speed else null}",
        )
        if (location.hasAccuracy() && location.accuracy > ACCURACY_MAX_M) {
            logRejected("accuracy", location, "acc=${location.accuracy}")
            scheduleAccuracyRetry(context)
            return
        }
        clearAccuracyRetry()
        val prev = lastAcceptedLocation
        if (prev != null) {
            val dtSec = ((nowMs - lastAcceptedAtMs).coerceAtLeast(1L)).toFloat() / 1000f
            val dist = prev.distanceTo(location)
            val reportedSpeed = if (location.hasSpeed()) location.speed else lastAcceptedSpeedMps
            val motionMode = motionModeForSpeed(reportedSpeed)
            if (motionMode == 1 && location.hasAccuracy()) {
                val accGate = location.accuracy * WALK_JUMP_ACC_MULT
                if (dtSec <= WALK_JUMP_WINDOW_S && dist > accGate && reportedSpeed < WALK_JUMP_MAX_SPEED_MPS) {
                    logRejected(
                        "walk_jump",
                        location,
                        "dist=${"%.1f".format(java.util.Locale.US, dist)}m acc=${"%.1f".format(java.util.Locale.US, location.accuracy)}m dt=${"%.2f".format(java.util.Locale.US, dtSec)}s"
                    )
                    return
                }
            }
            if (dtSec <= JUMP_WINDOW_S && dist >= JUMP_DISTANCE_M) {
                val axis = computeHeadingAxis(headingBuffer)
                val bearing = prev.bearingTo(location)
                val aligned = axis != null && angleDelta(axis, bearing) <= HEADING_STRAIGHT_ANGLE_DEG
                if (!aligned) {
                    logRejected(
                        "jump",
                        location,
                        "dist=${"%.1f".format(java.util.Locale.US, dist)}m dt=${"%.2f".format(java.util.Locale.US, dtSec)}s"
                    )
                    return
                }
            }
            val speed = dist / dtSec
            if (speed > MAX_SPEED_MPS) {
                logRejected(
                    "speed",
                    location,
                    "speed=${"%.1f".format(java.util.Locale.US, speed)}m/s dist=${"%.1f".format(java.util.Locale.US, dist)}m dt=${"%.2f".format(java.util.Locale.US, dtSec)}s"
                )
                return
            }
            lastAcceptedSpeedMps = speed
        } else {
            lastAcceptedSpeedMps = 0f
        }
        lastAcceptedLocation = Location(location)
        lastAcceptedAtMs = nowMs
        lastLocation = location
        pushHeadingPoint(location)
        val mode = getMode(context)
        val candidateMode = motionModeForSpeed(if (location.hasSpeed()) location.speed else lastAcceptedSpeedMps)
        if (recording) {
            if (lastMotionMode <= 0) {
                lastMotionMode = candidateMode
            } else if (candidateMode > lastMotionMode) {
                lastMotionMode = candidateMode
            }
        } else if (PeerMeshCapabilities.canUseAutoTrail && mode == MODE_AUTO) {
            val idleMs = nowMs - lastMoveAt
            if (idleMs in SHORT_IDLE_MIN_MS..SHORT_IDLE_MAX_MS && candidateMode == 1 && lastMotionMode > 1) {
                lastMotionMode = lastMotionMode
            } else {
                lastMotionMode = candidateMode
            }
        } else {
            lastMotionMode = candidateMode
        }
        if (PeerMeshCapabilities.canUseAutoTrail && !recording && mode == MODE_AUTO) {
            if (System.currentTimeMillis() < autoStartCooldownUntilMs) return
            if (anchorLocation == null) {
                anchorLocation = location
                return
            }
            val dist = anchorLocation?.distanceTo(location) ?: 0f
            if (dist >= startThresholdForMode(lastMotionMode)) {
                startRecording(context)
            }
        }
        locationListeners.forEach { it(location) }

        if (recording) {
            val now = nowMs
            if (PeerMeshCapabilities.canUseAutoTrail && mode == MODE_AUTO && idleAnchorLocation == null) {
                idleAnchorLocation = location
            }
            val accuracyDeadzone = if (location.hasAccuracy()) {
                location.accuracy * DEADZONE_ACCURACY_MULT
            } else {
                0f
            }
            val baseMoveThreshold = stopThresholdForMode(lastMotionMode)
            val moveThreshold = baseMoveThreshold + accuracyDeadzone
            val moved = !PeerMeshCapabilities.canUseAutoTrail || mode != MODE_AUTO || distanceFromIdleAnchor(location) >= moveThreshold
            if (PeerMeshCapabilities.canUseAutoTrail && moved && mode == MODE_AUTO) {
                idleAnchorLocation = location
                lastMoveAt = now
                scheduleIdleCheck(context)
            }
            if (shouldAppendPoint(now)) {
                ActivityLog.add(
                    label = "Trail point accepted",
                    source = "trail",
                    message = "lat=${"%.5f".format(java.util.Locale.US, location.latitude)} lon=${"%.5f".format(java.util.Locale.US, location.longitude)} count=${totalPointCount + 1}",
                )
                val trailPoint = TrailPoint(
                    lat = location.latitude,
                    lon = location.longitude,
                    t = now / 1000L,
                    acc = if (location.hasAccuracy()) location.accuracy else null,
                    spd = if (location.hasSpeed()) location.speed else lastAcceptedSpeedMps,
                    mm = motionModeForSpeed(if (location.hasSpeed()) location.speed else lastAcceptedSpeedMps),
                )
                currentPoints.add(
                    trailPoint
                )
                ActivityLog.add(
                    label = "Trail point emit",
                    source = "trail",
                    message = "to=LiveTrailShareManager count=${totalPointCount + 1}",
                )
                LiveTrailShareManager.onPoint(
                    context,
                    LiveTrailShareManager.SharedTrailPoint(
                        lat = trailPoint.lat,
                        lon = trailPoint.lon,
                        t = trailPoint.t,
                        acc = trailPoint.acc,
                        spd = trailPoint.spd,
                        mm = trailPoint.mm,
                    )
                )
                totalPointCount += 1
                val lastDist = lastDistanceLocation
                if (lastDist != null) {
                    val dt = ((now - lastDist.time).coerceAtLeast(1L)) / 1000f
                    val dist = lastDist.distanceTo(location)
                    val segSpeed = if (dt > 0f) dist / dt else 0f
                    val gapThreshold = TRAIL_GAP_SAVER_SECONDS
                    val isGap = dt > gapThreshold
                    val bearing = lastDist.bearingTo(location)
                    val isReverse = lastDistanceBearing != null &&
                        angleDelta(lastDistanceBearing!!, bearing) >= TRAIL_REVERSE_ANGLE_DEG &&
                        segSpeed >= TRAIL_REVERSE_MIN_MPS
                    val isAccelJump = lastDistanceSpeedMps != null &&
                        dt <= TRAIL_ACCEL_WINDOW_S &&
                        isAccelBandJump(lastDistanceSpeedMps!!, segSpeed)
                    if (!isGap && !isReverse && !isAccelJump) {
                        val effectiveDist = (dist - accuracyDeadzone).coerceAtLeast(0f)
                        totalDistanceM += effectiveDist
                        app.peermesh.data.debug.ActivityLog.add(
                            label = "Trail distance telemetry",
                            source = "trail",
                            message = "mode=$lastMotionMode acc=${"%.1f".format(java.util.Locale.US, accuracyDeadzone)} dist=${"%.1f".format(java.util.Locale.US, dist)} eff=${"%.1f".format(java.util.Locale.US, effectiveDist)} spd=${"%.2f".format(java.util.Locale.US, lastAcceptedSpeedMps)}",
                        )
                    }
                    lastDistanceSpeedMps = segSpeed
                    lastDistanceBearing = bearing
                }
                lastDistanceLocation = Location(location)
                lastTrailLocation = Location(location)
            }
            // stop handled in idleCheck only
        }
    }

    private fun distanceFromIdleAnchor(location: Location): Float {
        val anchor = idleAnchorLocation ?: return 0f
        return anchor.distanceTo(location)
    }

    private fun startRecording(context: Context) {
        recording = true
        ActivityLog.add(
            label = "Trail recording",
            source = "trail",
            message = "started mode=${getMode(context)} hasSeed=${lastLocation != null}",
        )
        recordingListeners.forEach { it(true) }
        TrailForegroundService.start(context.applicationContext)
        currentTrailId = UUID.randomUUID().toString()
        currentPoints = mutableListOf()
        currentStartT = System.currentTimeMillis() / 1000L
        currentTrailName = "Trail ${formatTime(currentStartT)}"
        lastPersistAt = System.currentTimeMillis()
        persistedCount = 0
        totalPointCount = 0
        lastMoveAt = System.currentTimeMillis()
        idleAnchorLocation = lastLocation
        lastAcceptedLocation = lastLocation?.let { Location(it) }
        lastAcceptedAtMs = if (lastLocation?.time != null && lastLocation!!.time > 0L) lastLocation!!.time else System.currentTimeMillis()
        lastAcceptedSpeedMps = 0f
        lastMotionMode = 1
        lastTrailLocation = lastLocation?.let { Location(it) }
        lastDistanceLocation = lastTrailLocation?.let { Location(it) }
        totalDistanceM = 0f
        currentTrailUnstable = false
        currentTrailUnstableNote = null
        if (!isGpsProviderEnabled(context)) {
            markTrailUnstable("gps_off_start")
        }
        if (PeerMeshCapabilities.canUseAutoTrail && getMode(context) == MODE_AUTO) {
            scheduleIdleCheck(context)
        }
        schedulePersistCheck(context)
        LiveTrailShareManager.onRecordingStarted(
            context,
            currentTrailName,
            currentStartT,
            getMode(context),
        )
        val seed = lastLocation
        ActivityLog.add(
            label = "Trail recording",
            source = "trail",
            message = "seed_location=${seed != null}",
        )
        if (seed != null) {
            val trailPoint = TrailPoint(
                lat = seed.latitude,
                lon = seed.longitude,
                t = seed.time / 1000L,
                acc = if (seed.hasAccuracy()) seed.accuracy else null,
                spd = if (seed.hasSpeed()) seed.speed else null,
                mm = motionModeForSpeed(if (seed.hasSpeed()) seed.speed else null),
            )
            currentPoints.add(trailPoint)
            LiveTrailShareManager.onPoint(
                context,
                LiveTrailShareManager.SharedTrailPoint(
                    lat = trailPoint.lat,
                    lon = trailPoint.lon,
                    t = trailPoint.t,
                    acc = trailPoint.acc,
                    spd = trailPoint.spd,
                    mm = trailPoint.mm,
                )
            )
            totalPointCount += 1
            lastTrailLocation = Location(seed)
            lastDistanceLocation = Location(seed)
        }
    }

    private fun stopRecording(context: Context, discard: Boolean = false) {
        if (!recording) return
        ActivityLog.add(
            label = "Trail recording",
            source = "trail",
            message = "stopping discard=$discard points=$totalPointCount distance=${"%.1f".format(java.util.Locale.US, totalDistanceM)}",
        )
        if (!discard) {
            persistCurrent(context, true, true)
        } else {
            currentPoints.clear()
            persistedCount = 0
            persistQueued = false
            persistQueuedForce = false
            persistQueuedFinal = false
        }
        recording = false
        recordingListeners.forEach { it(false) }
        LiveTrailShareManager.onRecordingStopped(context)
        idleCheck?.let { mainHandler.removeCallbacks(it) }
        idleCheck = null
        persistCheck?.let { mainHandler.removeCallbacks(it) }
        persistCheck = null
        clearAccuracyRetry()
        idleAnchorLocation = null
        currentTrailId = null
        currentTrailName = null
        persistedCount = 0
        totalPointCount = 0
        lastAcceptedLocation = null
        lastAcceptedAtMs = 0L
        lastAcceptedSpeedMps = 0f
        headingBuffer.clear()
        lastMotionMode = 0
        lastTrailLocation = null
        lastDistanceLocation = null
        totalDistanceM = 0f
        currentTrailUnstable = false
        currentTrailUnstableNote = null
        ensureAutoWatch(context)
    }

    private fun markTrailUnstable(reason: String) {
        currentTrailUnstable = true
        if (currentTrailUnstableNote == null) {
            currentTrailUnstableNote = reason
        } else if (!currentTrailUnstableNote!!.contains(reason)) {
            currentTrailUnstableNote = currentTrailUnstableNote + "," + reason
        }
    }

    private fun ensureAutoWatch(context: Context) {
        if (shouldAutoWatch(context)) {
            TrailForegroundService.start(context.applicationContext)
        } else if (!recording && !savingInProgress) {
            TrailForegroundService.stop(context.applicationContext)
        }
    }

    private fun shouldAutoWatch(context: Context): Boolean {
        if (!PeerMeshCapabilities.canUseAutoTrail) return false
        if (!isEnabled(context)) return false
        if (getMode(context) != MODE_AUTO) return false
        if (!getBackgroundAuto(context)) return false

        // Auto-watch runs via Foreground Service + notification.
        // Require foreground location permission + location services enabled.
        if (!hasLocationPermission(context)) return false
        if (!isLocationEnabled(context)) return false

        return true
    }

    private fun scheduleIdleCheck(context: Context) {
        idleCheck?.let { mainHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!PeerMeshCapabilities.canUseAutoTrail || !recording || getMode(context) != MODE_AUTO) return
                val now = System.currentTimeMillis()
            if (now - lastMoveAt >= AUTO_STOP_IDLE_MS) {
                stopRecording(context)
                anchorLocation = lastLocation
                suppressAutoStart(30_000L)
            } else {
                scheduleIdleCheck(context)
            }
            }
        }
        idleCheck = r
        mainHandler.postDelayed(r, IDLE_CHECK_MS)
    }

    private fun schedulePersistCheck(context: Context) {
        persistCheck?.let { mainHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!recording) return
                persistCurrent(context, false, false)
                mainHandler.postDelayed(this, PERSIST_INTERVAL_MS)
            }
        }
        persistCheck = r
        mainHandler.postDelayed(r, PERSIST_INTERVAL_MS)
    }

    private fun persistCurrent(context: Context, force: Boolean, final: Boolean) {
        val trailId = currentTrailId ?: return
        val name = currentTrailName ?: "Trail ${formatTime(currentStartT)}"
        val nowMs = System.currentTimeMillis()
        val total = totalPointCount
        val newPoints = currentPoints.toList()
        if (!force && newPoints.isEmpty()) return
        val endT = nowMs / 1000L
        if (savingInProgress) {
            persistQueued = true
            persistQueuedForce = persistQueuedForce || force
            persistQueuedFinal = persistQueuedFinal || final
            return
        }
        savingInProgress = true
        savingListeners.forEach { it(true) }
        val startIndex = persistedCount
        persistedCount += newPoints.size
        Thread {
            var success = false
            TrailForegroundService.start(context.applicationContext)
            try {
                TrailStore.appendPartial(
                    context = context,
                    trailId = trailId,
                    name = name,
                    mode = getMode(context),
                    startT = currentStartT,
                    endT = endT,
                    points = newPoints,
                    startIndex = startIndex,
                    totalCount = total,
                    distanceMeters = totalDistanceM,
                    unstable = if (currentTrailUnstable) 1 else 0,
                    unstableNote = currentTrailUnstableNote,
                )
                success = true
            } catch (e: Exception) {
                android.util.Log.e(TAG, "persistCurrent failed", e)
            }
            mainHandler.post {
                if (success && newPoints.isNotEmpty()) {
                    if (currentPoints.size >= newPoints.size) {
                        currentPoints.subList(0, newPoints.size).clear()
                    } else {
                        currentPoints.clear()
                    }
                } else if (!success) {
                    persistedCount = (persistedCount - newPoints.size).coerceAtLeast(0)
                }
                savingListeners.forEach { it(false) }
                savingInProgress = false
                if (persistQueued) {
                    val queuedForce = persistQueuedForce
                    val queuedFinal = persistQueuedFinal
                    persistQueued = false
                    persistQueuedForce = false
                    persistQueuedFinal = false
                    persistCurrent(context, queuedForce, queuedFinal)
                }
                if (!savingInProgress) {
                    pendingDeleteTrailId?.let { id ->
                        TrailStore.delete(context, id)
                        pendingDeleteTrailId = null
                    }
                }
                if (!recording && !shouldAutoWatch(context)) {
                    TrailForegroundService.stop(context.applicationContext)
                }
                if (final && onTrailSaved != null) {
                    onTrailSaved?.invoke()
                }
            }
        }.start()
        lastPersistAt = nowMs
    }

    private const val IDLE_CHECK_MS = 60_000L
    private const val PERSIST_INTERVAL_MS = 5 * 60_000L
    private const val MAX_SPEED_MPS = 33.33f
    private const val SPEED_IDLE_MAX_MPS = 0.5f
    private const val WALK_MAX_MPS = 2.2f
    private const val MOTO_MAX_MPS = 12.0f
    private const val WALK_START_M = AUTO_START_DISTANCE_M
    private const val MOTO_START_M = AUTO_START_DISTANCE_M
    private const val CAR_START_M = AUTO_START_DISTANCE_M
    private const val WALK_STOP_M = AUTO_START_DISTANCE_M
    private const val MOTO_STOP_M = AUTO_START_DISTANCE_M
    private const val CAR_STOP_M = AUTO_START_DISTANCE_M
    private const val SHORT_IDLE_MIN_MS = 30_000L
    private const val SHORT_IDLE_MAX_MS = 120_000L
    private const val TRAIL_GAP_SAVER_SECONDS = 60f
    private const val TRAIL_ACCEL_WINDOW_S = 2f
    private const val TRAIL_REVERSE_MIN_MPS = 1.0f
    private const val TRAIL_REVERSE_ANGLE_DEG = 120f
    private const val HEADING_SAMPLE_MIN_DISTANCE_M = 3f
    private const val HEADING_STRAIGHT_ANGLE_DEG = 20f
    private const val HEADING_JUMP_DIST_M = 20f
    private const val HEADING_JUMP_WINDOW_S = 2.0f
    private const val HEADING_SPEED_JUMP_MPS = 20f
    private const val HEADING_SPEED_MAX_MPS = 33.33f
    private const val HEADING_ACCEL_JUMP_MPS = 5f

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun isGpsProviderEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private fun formatTime(epochSeconds: Long): String {
        val date = java.util.Date(epochSeconds * 1000L)
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(date)
    }

    private fun motionModeForSpeed(speedMps: Float?): Int {
        if (speedMps == null) return 0
        return when {
            speedMps < WALK_MAX_MPS -> 1
            speedMps < MOTO_MAX_MPS -> 2
            else -> 3
        }
    }

    private fun startThresholdForMode(mode: Int): Float {
        return when (mode) {
            2 -> MOTO_START_M
            3 -> CAR_START_M
            else -> WALK_START_M
        }
    }

    private fun stopThresholdForMode(mode: Int): Float {
        return when (mode) {
            2 -> MOTO_STOP_M
            3 -> CAR_STOP_M
            else -> WALK_STOP_M
        }
    }

    private fun shouldAppendPoint(nowMs: Long): Boolean {
        val last = lastTrailLocation ?: return true
        val elapsedMs = nowMs - last.time
        val shouldAppend = elapsedMs >= LOCATION_UPDATE_MS
        if (!shouldAppend) {
            ActivityLog.add(
                label = "Trail point skipped",
                source = "trail",
                message = "reason=interval elapsed_ms=$elapsedMs required_ms=$LOCATION_UPDATE_MS",
            )
        }
        return shouldAppend
    }

    private fun angleDelta(a: Float, b: Float): Float {
        return kotlin.math.abs((a - b + 540f) % 360f - 180f)
    }

    private fun speedBand(speedMps: Float): Int {
        return when {
            speedMps < SPEED_IDLE_MAX_MPS -> 0
            speedMps < WALK_MAX_MPS -> 1
            speedMps < MOTO_MAX_MPS -> 2
            else -> 3
        }
    }

    private fun isAccelBandJump(prevSpeed: Float, newSpeed: Float): Boolean {
        val prevBand = speedBand(prevSpeed)
        val newBand = speedBand(newSpeed)
        if (prevBand == 0 && newBand >= 2) return true // idle -> moto/car
        if (prevBand == 1 && newBand >= 3) return true // walk -> car
        return false
    }

    private fun pushHeadingPoint(location: Location) {
        val last = headingBuffer.lastOrNull()
        if (last == null || last.distanceTo(location) >= HEADING_SAMPLE_MIN_DISTANCE_M) {
            headingBuffer.addLast(Location(location))
            while (headingBuffer.size > 4) headingBuffer.removeFirst()
        }
    }

    private fun computeHeadingAxis(points: ArrayDeque<Location>): Float? {
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
        for (i in 1 until points.size) {
            val dt = ((times[i] - times[i - 1]).coerceAtLeast(1L)) / 1000f
            val dist = points[i - 1].distanceTo(points[i])
            val speed = speeds[i - 1]
            val speedJump = speed > HEADING_SPEED_JUMP_MPS || speed > HEADING_SPEED_MAX_MPS
            val distJump = dist >= HEADING_JUMP_DIST_M && dt <= HEADING_JUMP_WINDOW_S
            if (speedJump || distJump) {
                dropIndex = i
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
                    break
                }
            }
        }
        if (dropIndex == null) return points.takeLast(3)
        val filtered = points.toMutableList()
        filtered.removeAt(dropIndex)
        return filtered.takeLast(3)
    }

    private fun logRejected(reason: String, location: Location, detail: String) {
        ActivityLog.add(
            label = "Trail point dropped",
            source = "trail",
            message = "reason=$reason $detail lat=${"%.5f".format(java.util.Locale.US, location.latitude)} lon=${"%.5f".format(java.util.Locale.US, location.longitude)}",
        )
    }
}
