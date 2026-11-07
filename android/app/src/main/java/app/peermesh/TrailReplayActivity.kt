package app.peermesh

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.animation.ValueAnimator
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.LiveTrailPointEntity
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker

import android.text.format.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class TrailReplayActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var metaText: TextView
    private lateinit var playPauseButton: Button
    private lateinit var speedSpinner: Spinner
    private lateinit var seekBar: SeekBar
    private lateinit var timelineText: TextView
    private lateinit var followButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var replayPoints: List<LiveTrailPointEntity> = emptyList()
    private var replayMarker: Marker? = null
    private var replayIndex = 0
    private var replayPlaying = false
    private var replaySpeedMultiplier = 1
    private var replayPolyline: Polyline? = null
    private var fullReplayPolyline: Polyline? = null
    private var autoFollowReplay = true
    private var replayAnimator: ValueAnimator? = null

    private lateinit var statsDistance: TextView
    private lateinit var statsDuration: TextView
    private lateinit var statsAvgSpeed: TextView
    private lateinit var statsPoints: TextView
    private lateinit var statsStart: TextView
    private lateinit var statsEnd: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_trail_replay)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.trail_replay)

        mapView = findViewById(R.id.trailReplayMap)
        metaText = findViewById(R.id.trailReplayMeta)

        playPauseButton = findViewById(R.id.replayPlayPauseButton)
        speedSpinner = findViewById(R.id.replaySpeedSpinner)
        seekBar = findViewById(R.id.replaySeekBar)
        timelineText = findViewById(R.id.replayTimelineText)

        followButton = findViewById(R.id.replayFollowButton)
        followButton.setOnClickListener {
            autoFollowReplay = true
            updateReplayMarker()
        }

        statsDistance = findViewById(R.id.trailStatsDistance)
        statsDuration = findViewById(R.id.trailStatsDuration)
        statsAvgSpeed = findViewById(R.id.trailStatsAvgSpeed)
        statsPoints = findViewById(R.id.trailStatsPoints)
        statsStart = findViewById(R.id.trailStatsStart)
        statsEnd = findViewById(R.id.trailStatsEnd)

        setupReplayControls()

        val trailId =
            intent.getStringExtra(EXTRA_TRAIL_ID).orEmpty()

        if (trailId.isBlank()) {
            Toast.makeText(this, "Missing trail id", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadReplay(trailId)
    }

    private fun loadReplay(trailId: String) {

        Thread {

            val db = AppDatabaseProvider.get(this)

            val points =
                db.liveTrailDao().listPoints(trailId)

            runOnUiThread {
                renderTrail(points)
            }

        }.start()
    }

    private fun renderTrail(points: List<LiveTrailPointEntity>) {
        replayPoints = points
        replayIndex = 0

        if (points.isEmpty()) {
            metaText.setText(R.string.trail_replay_no_points)
            return
        }

        renderTrailStats(points)

        metaText.text =
            getString(R.string.trail_replay_points, points.size)

        val allGeoPoints =
            points.map {
                GeoPoint(it.lat, it.lon)
            }

        fullReplayPolyline = Polyline().apply {
            setPoints(allGeoPoints)
            outlinePaint.alpha = 70
        }

        replayPolyline = Polyline().apply {
            setPoints(emptyList())
        }

        mapView.overlays.clear()

        fullReplayPolyline?.let {
            mapView.overlays.add(it)
        }

        replayPolyline?.let {
            mapView.overlays.add(it)
        }

        val first = points.first()

        mapView.controller.setZoom(16.0)
        mapView.controller.setCenter(
            GeoPoint(first.lat, first.lon)
        )

        mapView.invalidate()

        seekBar.max =
            if (points.isEmpty()) 0 else points.size - 1

        updateReplayMarker()
    }

    private fun updateReplayMarker() {

        if (replayPoints.isEmpty()) return

        val point =
            replayPoints[replayIndex]

        val geo =
            GeoPoint(point.lat, point.lon)

        if (replayMarker == null) {
            replayMarker = Marker(mapView)
            mapView.overlays.add(replayMarker)
        }

        replayMarker?.position = geo
        replayMarker?.setAnchor(
            Marker.ANCHOR_CENTER,
            Marker.ANCHOR_CENTER
        )

        timelineText.text =
            getString(
                R.string.trail_replay_timeline,
                replayIndex + 1,
                replayPoints.size
            )

        seekBar.progress = replayIndex

        updateReplayPolyline()

        if (autoFollowReplay) {
            animateCameraToReplayPoint(geo)
        }

        mapView.invalidate()
    }

    private fun updateReplayPolyline() {

        if (replayPoints.isEmpty()) return

        val visiblePoints =
            replayPoints
                .take(replayIndex + 1)
                .map {
                    GeoPoint(it.lat, it.lon)
                }

        replayPolyline?.setPoints(visiblePoints)
    }

    private fun animateCameraToReplayPoint(point: GeoPoint) {

        replayAnimator?.cancel()

        replayAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {

                duration = 300L

                addUpdateListener {

                    mapView.controller.animateTo(point)
                }

                start()
            }
    }

    private fun scheduleReplayTick() {

        handler.postDelayed({

            if (!replayPlaying) return@postDelayed

            if (replayPoints.isEmpty()) {
                replayPlaying = false
                return@postDelayed
            }

            replayIndex++

            if (replayIndex >= replayPoints.size) {
                replayIndex = replayPoints.size - 1
                replayPlaying = false

                playPauseButton.setText(R.string.trail_replay_play)

                updateReplayMarker()

                return@postDelayed
            }

            updateReplayMarker()

            scheduleReplayTick()

        }, (1000L / replaySpeedMultiplier))
    }

    private fun setupReplayControls() {

        val speedValues = listOf("1x", "2x", "4x")

        val speedAdapter =
            object : ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                speedValues
            ) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    val view =
                        super.getView(position, convertView, parent) as TextView

                    view.setTextColor(
                        resolveThemeColor(this@TrailReplayActivity, android.R.attr.textColorPrimary)
                    )

                    return view
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    val view =
                        super.getDropDownView(position, convertView, parent) as TextView

                    view.setTextColor(
                        resolveThemeColor(this@TrailReplayActivity, android.R.attr.textColorPrimary)
                    )

                    view.setBackgroundColor(
                        resolveThemeColor(this@TrailReplayActivity, android.R.attr.colorBackground)
                    )

                    return view
                }
            }

        speedAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        speedSpinner.adapter = speedAdapter

        speedSpinner.setSelection(0)

        speedSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    replaySpeedMultiplier =
                        when (position) {
                            1 -> 2
                            2 -> 4
                            else -> 1
                        }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                }
            }

        playPauseButton.setOnClickListener {

            replayPlaying = !replayPlaying

            playPauseButton.setText(
                if (replayPlaying)
                    R.string.trail_replay_pause
                else
                    R.string.trail_replay_play
            )

            if (replayPlaying) {
                scheduleReplayTick()
            }
        }

        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {
                        replayPlaying = false
                        playPauseButton.setText(
                            R.string.trail_replay_play
                        )
                        replayIndex = progress
                        updateReplayMarker()
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        mapView.setOnTouchListener { _, _ ->
            autoFollowReplay = false
            false
        }
    }

    private fun renderTrailStats(
        points: List<LiveTrailPointEntity>
    ) {

        if (points.isEmpty()) return

        val first = points.first()
        val last = points.last()

        val distanceMeters =
            calculateDistanceMeters(points)

        val distanceKm =
            distanceMeters / 1000.0

        val durationSeconds =
            (last.t - first.t).coerceAtLeast(0L)

        val durationHours =
            durationSeconds / 3600.0

        val avgSpeed =
            if (durationHours <= 0.0) {
                0.0
            } else {
                distanceKm / durationHours
            }

        val startDate =
            Date(first.t * 1000L)

        val endDate =
            Date(last.t * 1000L)

        val dateFormat =
            DateFormat.getMediumDateFormat(this)

        val timeFormat =
            DateFormat.getTimeFormat(this)

        statsDistance.text =
            "${getString(R.string.trail_stats_distance)}: ${
                getString(
                    R.string.trail_stats_distance_format,
                    distanceKm
                )
            }"

        statsDuration.text =
            "${getString(R.string.trail_stats_duration)}: ${
                formatDuration(durationSeconds)
            }"

        statsAvgSpeed.text =
            "${getString(R.string.trail_stats_avg_speed)}: ${
                getString(
                    R.string.trail_stats_avg_speed_format,
                    avgSpeed
                )
            }"

        statsPoints.text =
            "${getString(R.string.trail_stats_points)}: ${
                getString(
                    R.string.trail_stats_points_format,
                    points.size
                )
            }"

        statsStart.text =
            "${getString(R.string.trail_stats_start)}: ${
                dateFormat.format(startDate)
            } ${
                timeFormat.format(startDate)
            }"

        statsEnd.text =
            "${getString(R.string.trail_stats_end)}: ${
                dateFormat.format(endDate)
            } ${
                timeFormat.format(endDate)
            }"
    }

    private fun calculateDistanceMeters(
        points: List<LiveTrailPointEntity>
    ): Double {

        if (points.size < 2) {
            return 0.0
        }

        var total = 0.0

        for (i in 1 until points.size) {

            val prev = points[i - 1]
            val curr = points[i]

            val results = FloatArray(1)

            android.location.Location.distanceBetween(
                prev.lat,
                prev.lon,
                curr.lat,
                curr.lon,
                results
            )

            total += results[0]
        }

        return total
    }

    private fun formatDuration(seconds: Long): String {

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return when {
            hours > 0 ->
                String.format(
                    Locale.US,
                    "%dh %02dm %02ds",
                    hours,
                    minutes,
                    secs
                )

            minutes > 0 ->
                String.format(
                    Locale.US,
                    "%dm %02ds",
                    minutes,
                    secs
                )

            else ->
                String.format(
                    Locale.US,
                    "%ds",
                    secs
                )
        }
    }

    private fun resolveThemeColor(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        replayPlaying = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TRAIL_ID = "extra_trail_id"
    }
}