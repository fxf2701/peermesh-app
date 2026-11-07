package app.peermesh

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.View
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.DatePicker
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.TrailEntity
import app.peermesh.data.db.entity.TrailPointEntity
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapAdapter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.floor
import android.graphics.Point

class TrailPlaybackActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var playbackRunnable: Runnable? = null
    private var isPlaying = false
    private var points: List<TrailPointEntity> = emptyList()
    private var currentIndex = 0
    private var startT: Long = 0L
    private var endT: Long = 0L
    private var tapGrid: TrailTapGrid? = null
    private var tapGridPoints: List<Point> = emptyList()
    private var tapGridRunnable: Runnable? = null
    private val trailEndpointStartCache = mutableMapOf<String, android.graphics.drawable.Drawable>()
    private val trailEndpointEndCache = mutableMapOf<String, android.graphics.drawable.Drawable>()

    private lateinit var mapView: MapView
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var clockLabel: TextView
    private lateinit var playButton: Button
    private lateinit var jumpButton: Button
    private lateinit var closeButton: Button
    private var marker: Marker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        if (!PeerMeshProGate.require(this, PeerMeshProGate.FEATURE_TRAIL)) {
            finish()
            return
        }
        setContentView(R.layout.activity_trail_playback)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<View>(R.id.header_menu)
        headerMenu.visibility = View.GONE
        headerTitle.text = getString(R.string.trail_playback_title)

        mapView = findViewById(R.id.trail_playback_map)
        seekBar = findViewById(R.id.trail_playback_seek)
        timeLabel = findViewById(R.id.trail_playback_time)
        clockLabel = findViewById(R.id.trail_playback_clock)
        playButton = findViewById(R.id.trail_playback_toggle)
        jumpButton = findViewById(R.id.trail_playback_jump)
        closeButton = findViewById(R.id.trail_playback_close)

        initMap(mapView)
        installControls()
        installTapToPause()

        val trailId = intent.getStringExtra(EXTRA_TRAIL_ID)
        if (trailId.isNullOrBlank()) {
            toast(getString(R.string.trail_playback_missing))
            finish()
            return
        }
        loadTrail(trailId, headerTitle)
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
        mapView.onPause()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    private fun initMap(map: MapView) {
        Configuration.getInstance().userAgentValue = packageName
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setBuiltInZoomControls(false)
        map.addMapListener(object : MapAdapter() {
            override fun onZoom(event: ZoomEvent): Boolean {
                scheduleTapGridRebuild()
                return false
            }

            override fun onScroll(event: ScrollEvent): Boolean {
                scheduleTapGridRebuild()
                return false
            }
        })
    }

    private fun installControls() {
        playButton.setOnClickListener {
            if (isPlaying) {
                stopPlayback()
            } else {
                startPlayback()
            }
        }
        jumpButton.setOnClickListener { showJumpDialog() }
        closeButton.setOnClickListener { finish() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updatePlaybackIndex(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                stopPlayback()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }
        })
    }

    private fun loadTrail(trailId: String, headerTitle: TextView) {
        Thread {
            val db = AppDatabaseProvider.get(this)
            val trail = db.trailDao().getTrail(trailId)
            val pts = db.trailDao().listPoints(trailId)
            runOnUiThread {
                if (trail == null || pts.isEmpty()) {
                    toast(getString(R.string.trail_playback_empty))
                    finish()
                    return@runOnUiThread
                }
                headerTitle.text = trail.name
                bindTrail(trail, pts)
            }
        }.start()
    }

    private fun bindTrail(trail: TrailEntity, pts: List<TrailPointEntity>) {
        points = pts
        currentIndex = 0
        startT = pts.first().t
        endT = pts.last().t
        seekBar.max = max(0, pts.size - 1)
        drawTrailLine(pts)
        rebuildTapGrid()
        updatePlaybackIndex(0)
    }

    private fun drawTrailLine(pts: List<TrailPointEntity>) {
        mapView.overlays.clear()
        val segments = buildTrailSegments(pts)
        segments.forEach { segment ->
            val polyline = Polyline().apply {
                outlinePaint.color = segment.color
                outlinePaint.strokeWidth = 10f
                setPoints(segment.points)
            }
            mapView.overlays.add(polyline)
        }
        mapView.overlays.add(TrailPointDotsOverlay(pts))
        val startPt = pts.firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
        val endPt = pts.lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
        startPt?.let { pt ->
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = pt
                    icon = trailEndpointDrawable(isStart = true)
                    setAnchor(Marker.ANCHOR_LEFT, Marker.ANCHOR_BOTTOM)
                    isFlat = true
                }
            )
        }
        endPt?.let { pt ->
            mapView.overlays.add(
                Marker(mapView).apply {
                    position = pt
                    icon = trailEndpointDrawable(isStart = false)
                    setAnchor(Marker.ANCHOR_LEFT, Marker.ANCHOR_BOTTOM)
                    isFlat = true
                }
            )
        }
        marker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(this@TrailPlaybackActivity, R.drawable.ic_user_dot)
            infoWindow = null
        }
        mapView.overlays.add(marker)
        focusTrail(pts)
        mapView.invalidate()
    }

    private fun focusTrail(pts: List<TrailPointEntity>) {
        if (pts.size == 1) {
            mapView.controller.setZoom(17.0)
            mapView.controller.setCenter(GeoPoint(pts[0].lat, pts[0].lon))
            return
        }
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        pts.forEach {
            minLat = min(minLat, it.lat)
            maxLat = max(maxLat, it.lat)
            minLon = min(minLon, it.lon)
            maxLon = max(maxLon, it.lon)
        }
        val bbox = BoundingBox(maxLat, maxLon, minLat, minLon)
        mapView.zoomToBoundingBox(bbox, true, 64)
    }

    private fun updatePlaybackIndex(index: Int) {
        if (points.isEmpty()) return
        currentIndex = index.coerceIn(0, points.size - 1)
        val point = points[currentIndex]
        marker?.position = GeoPoint(point.lat, point.lon)
        mapView.invalidate()
        seekBar.progress = currentIndex
        val elapsed = point.t - startT
        val total = endT - startT
        timeLabel.text = getString(
            R.string.trail_playback_time_format,
            formatElapsed(elapsed),
            formatElapsed(total)
        )
        clockLabel.text = formatDateTime(point.t)
    }

    private data class TrailSegment(
        val points: List<GeoPoint>,
        val color: Int,
    )

    private fun buildTrailSegments(points: List<TrailPointEntity>): List<TrailSegment> {
        if (points.size < 2) return emptyList()
        val segments = ArrayList<TrailSegment>()
        var currentColor: Int? = null
        var currentPoints = ArrayList<GeoPoint>()
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val next = points[i]
            val color = colorForSpeed(prev, i - 1, points)
            if (currentColor == null) {
                currentColor = color
                currentPoints.add(GeoPoint(prev.lat, prev.lon))
            }
            if (color != currentColor) {
                if (currentPoints.size >= 2) {
                    segments.add(TrailSegment(currentPoints, currentColor!!))
                }
                currentPoints = ArrayList()
                currentColor = color
                currentPoints.add(GeoPoint(prev.lat, prev.lon))
            }
            currentPoints.add(GeoPoint(next.lat, next.lon))
        }
        if (currentPoints.size >= 2 && currentColor != null) {
            segments.add(TrailSegment(currentPoints, currentColor!!))
        }
        return segments
    }

    private fun colorForSpeed(
        point: TrailPointEntity,
        index: Int,
        pts: List<TrailPointEntity>,
    ): Int {
        val speed = point.spd ?: run {
            if (index <= 0) return@run 0f
            val prev = pts[index - 1]
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

    private fun trailColorForPoint(index: Int, pts: List<TrailPointEntity>): Int {
        return pts.getOrNull(index)?.let { colorForSpeed(it, index, pts) } ?: TRAIL_COLOR_WALK
    }

    private inner class TrailPointDotsOverlay(
        private val trailPoints: List<TrailPointEntity>,
    ) : org.osmdroid.views.overlay.Overlay() {
        private val density = resources.displayMetrics.density
        private val radiusPx = 3.5f * density
        private val outlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.argb(230, 8, 16, 41)
        }
        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val screenPoint = Point()

        override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val projection = mapView.projection ?: return
            trailPoints.forEachIndexed { index, point ->
                projection.toPixels(GeoPoint(point.lat, point.lon), screenPoint)
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radiusPx + density, outlinePaint)
                fillPaint.color = trailColorForPoint(index, trailPoints)
                canvas.drawCircle(screenPoint.x.toFloat(), screenPoint.y.toFloat(), radiusPx, fillPaint)
            }
        }
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

    private fun startPlayback() {
        if (points.isEmpty()) return
        isPlaying = true
        playButton.text = getString(R.string.trail_playback_pause)
        scheduleNextStep()
    }

    private fun stopPlayback() {
        isPlaying = false
        playbackRunnable?.let { handler.removeCallbacks(it) }
        playbackRunnable = null
        playButton.text = getString(R.string.trail_playback_play)
    }

    private fun scheduleNextStep() {
        val runnable = Runnable {
            if (!isPlaying) return@Runnable
            if (currentIndex >= points.size - 1) {
                stopPlayback()
                return@Runnable
            }
            val current = points[currentIndex]
            val next = points[currentIndex + 1]
            val delay = computeDelayMs(current, next)
            updatePlaybackIndex(currentIndex + 1)
            handler.postDelayed(playbackRunnable!!, delay)
        }
        playbackRunnable = runnable
        handler.postDelayed(runnable, DEFAULT_PLAYBACK_DELAY_MS)
    }

    private fun computeDelayMs(current: TrailPointEntity, next: TrailPointEntity): Long {
        val dtSec = max(1L, next.t - current.t)
        val scaled = (dtSec * 1000L / PLAYBACK_SPEED_X).coerceIn(MIN_PLAYBACK_DELAY_MS, MAX_PLAYBACK_DELAY_MS)
        return scaled
    }

    private fun installTapToPause() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (points.isEmpty()) return false
                val idx = findNearestPointIndex(p) ?: return false
                stopPlayback()
                updatePlaybackIndex(idx)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        mapView.overlays.add(MapEventsOverlay(receiver))
    }

    private fun findNearestPointIndex(p: GeoPoint): Int? {
        val projection = mapView.projection ?: return null
        val target = projection.toPixels(p, null)
        val thresholdPx = 24.0 * resources.displayMetrics.density
        val grid = tapGrid ?: return null
        var bestIndex = -1
        var bestDist = Double.MAX_VALUE
        val candidates = grid.query(
            target.x.toDouble(),
            target.y.toDouble(),
            thresholdPx
        )
        candidates.forEach { idx ->
            val px = tapGridPoints.getOrNull(idx) ?: run {
                val pt = points[idx]
                projection.toPixels(GeoPoint(pt.lat, pt.lon), null)
            }
            val dx = (px.x - target.x).toDouble()
            val dy = (px.y - target.y).toDouble()
            val dist = sqrt(dx.pow(2) + dy.pow(2))
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = idx
            }
        }
        return if (bestIndex >= 0 && bestDist <= thresholdPx) bestIndex else null
    }

    private fun rebuildTapGrid() {
        val projection = mapView.projection ?: return
        val cellSize = (64 * resources.displayMetrics.density).toDouble()
        val buckets = HashMap<Long, MutableList<Int>>()
        val pxPoints = ArrayList<Point>(points.size)
        points.forEachIndexed { idx, pt ->
            val px = projection.toPixels(GeoPoint(pt.lat, pt.lon), null)
            pxPoints.add(px)
            val cellX = floor(px.x / cellSize).toInt()
            val cellY = floor(px.y / cellSize).toInt()
            val key = ((cellX.toLong()) shl 32) or (cellY.toLong() and 0xffffffffL)
            val list = buckets.getOrPut(key) { ArrayList() }
            list.add(idx)
        }
        tapGrid = TrailTapGrid(cellSize, buckets)
        tapGridPoints = pxPoints
    }

    private fun scheduleTapGridRebuild() {
        tapGridRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable { rebuildTapGrid() }
        tapGridRunnable = runnable
        handler.postDelayed(runnable, TAP_GRID_REBUILD_DELAY_MS)
    }

    private data class TrailTapGrid(
        val cellSize: Double,
        val buckets: Map<Long, List<Int>>,
    ) {
        fun query(x: Double, y: Double, radius: Double): List<Int> {
            val minX = floor((x - radius) / cellSize).toInt()
            val maxX = floor((x + radius) / cellSize).toInt()
            val minY = floor((y - radius) / cellSize).toInt()
            val maxY = floor((y + radius) / cellSize).toInt()
            val result = ArrayList<Int>()
            for (cx in minX..maxX) {
                for (cy in minY..maxY) {
                    val key = ((cx.toLong()) shl 32) or (cy.toLong() and 0xffffffffL)
                    val list = buckets[key] ?: continue
                    result.addAll(list)
                }
            }
            return result
        }
    }

    private fun formatDuration(seconds: Long): String {
        val s = max(0, seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            String.format("%d:%02d:%02d", h, m, sec)
        } else {
            String.format("%02d:%02d", m, sec)
        }
    }

    private fun formatElapsed(seconds: Long): String {
        val s = max(0, seconds)
        val days = s / 86_400
        val remainder = s % 86_400
        val h = remainder / 3600
        val m = (remainder % 3600) / 60
        val sec = remainder % 60
        return if (days > 0) {
            String.format("%dD %02d:%02d:%02d", days, h, m, sec)
        } else {
            String.format("%02d:%02d:%02d", h, m, sec)
        }
    }

    private fun formatClockTime(epochSeconds: Long): String {
        val date = java.util.Date(epochSeconds * 1000L)
        return android.text.format.DateFormat.getTimeFormat(this).format(date)
    }

    private fun formatDateTime(epochSeconds: Long): String {
        val date = java.util.Date(epochSeconds * 1000L)
        val fmt = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.MEDIUM
        )
        return fmt.format(date)
    }

    private fun showJumpDialog() {
        if (points.isEmpty()) return
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val datePicker = DatePicker(this)
        val hourInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "HH"
            filters = arrayOf(InputFilter.LengthFilter(2))
        }
        val minuteInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "MM"
            filters = arrayOf(InputFilter.LengthFilter(2))
        }
        val startMs = startT * 1000L
        val endMs = endT * 1000L
        datePicker.minDate = startMs
        datePicker.maxDate = endMs

        val current = java.util.Calendar.getInstance().apply { timeInMillis = points[currentIndex].t * 1000L }
        datePicker.updateDate(
            current.get(java.util.Calendar.YEAR),
            current.get(java.util.Calendar.MONTH),
            current.get(java.util.Calendar.DAY_OF_MONTH)
        )
        val initHour = current.get(java.util.Calendar.HOUR_OF_DAY)
        val initMinute = current.get(java.util.Calendar.MINUTE)
        hourInput.setText(String.format("%02d", initHour))
        minuteInput.setText(String.format("%02d", initMinute))

        container.addView(datePicker)
        val timeRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            val padTop = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, padTop, 0, 0)
        }
        timeRow.addView(hourInput)
        val sep = TextView(this).apply {
            text = ":"
            setPadding((6 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt(), 0)
        }
        timeRow.addView(sep)
        timeRow.addView(minuteInput)
        container.addView(timeRow)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.trail_playback_jump_title)
            .setView(container)
            .setNegativeButton(R.string.trail_playback_jump_cancel, null)
            .setPositiveButton(R.string.trail_playback_jump_ok) { _, _ ->
                val selectedHour = hourInput.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: initHour
                val selectedMinute = minuteInput.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: initMinute
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.YEAR, datePicker.year)
                    set(java.util.Calendar.MONTH, datePicker.month)
                    set(java.util.Calendar.DAY_OF_MONTH, datePicker.dayOfMonth)
                    set(java.util.Calendar.HOUR_OF_DAY, selectedHour)
                    set(java.util.Calendar.MINUTE, selectedMinute)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                var targetMs = cal.timeInMillis
                if (targetMs < startMs) targetMs = startMs
                if (targetMs > endMs) targetMs = endMs
                val idx = findNearestIndexByTime(targetMs / 1000L)
                stopPlayback()
                updatePlaybackIndex(idx)
            }
            .show()
    }

    private fun findNearestIndexByTime(targetSeconds: Long): Int {
        if (points.isEmpty()) return 0
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].t < targetSeconds) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        val idx = lo
        if (idx == 0) return 0
        val prev = points[idx - 1]
        val curr = points[idx]
        return if (kotlin.math.abs(curr.t - targetSeconds) < kotlin.math.abs(prev.t - targetSeconds)) {
            idx
        } else {
            idx - 1
        }
    }

    private fun resolveColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_TRAIL_ID = "trail_id"
        private const val DEFAULT_PLAYBACK_DELAY_MS = 300L
        private const val MIN_PLAYBACK_DELAY_MS = 80L
        private const val MAX_PLAYBACK_DELAY_MS = 800L
        private const val PLAYBACK_SPEED_X = 10L
        private const val TAP_GRID_REBUILD_DELAY_MS = 120L
        private const val HIGH_SPEED_MIN_MPS = 33.33f
        private const val TRAIL_STOP_MAX_MPS = 0.5f
        private const val TRAIL_WALK_MAX_MPS = 2.2f
        private const val TRAIL_MOTO_MAX_MPS = 12.0f
        private val TRAIL_COLOR_STOP = android.graphics.Color.argb(200, 230, 57, 53)
        private val TRAIL_COLOR_WALK = android.graphics.Color.argb(200, 255, 152, 0)
        private val TRAIL_COLOR_MOTO = android.graphics.Color.argb(200, 255, 214, 0)
        private val TRAIL_COLOR_CAR = android.graphics.Color.argb(200, 56, 142, 60)
        private val TRAIL_COLOR_HIGH = android.graphics.Color.argb(200, 156, 39, 176)
    }
}
