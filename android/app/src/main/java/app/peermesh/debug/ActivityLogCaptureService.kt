package app.peermesh.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import app.peermesh.R
import app.peermesh.data.debug.ActivityLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ActivityLogCaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null
    private var flushRunnable: Runnable? = null

    private var sessionId: String? = null
    private var startedAtMs: Long = 0L
    private var stopAtMs: Long = 0L
    private var durationMs: Long = 0L
    private var patterns: List<String> = emptyList()
    private var labels: List<String> = emptyList()
    private var filePrefix: String? = null
    private var liveFileName: String? = null
    private var benchmarkLiveFileName: String? = null
    private var lastFlushedMs: Long = 0L
    private var benchmarkSamples = mutableListOf<BenchmarkSample>()
    private var benchmarkRunnable: Runnable? = null
    private var lastCpuElapsedRealtimeMs: Long = 0L
    private var lastCpuProcessTimeMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ActivityLog.isEnabled()) {
            stopCapture(finalize = false, stopService = false)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCaptureFromIntent(intent)
            ACTION_STOP_CAPTURE -> stopCapture(finalize = true, stopService = true)
            ACTION_RESTORE_CAPTURE -> restoreCaptureIfNeeded()
            null -> restoreCaptureIfNeeded()
            else -> Unit
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!ActivityLog.isEnabled()) return
        if (sessionId == null) return
        val restartIntent = Intent(applicationContext, ActivityLogCaptureService::class.java).apply {
            action = ACTION_RESTORE_CAPTURE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }

    override fun onDestroy() {
        stopCapture(finalize = false, stopService = false)
        super.onDestroy()
    }

    private fun startCaptureFromIntent(intent: Intent) {
        if (!ActivityLog.isEnabled()) {
            stopSelf()
            return
        }
        val requestedDurationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        if (requestedDurationMs <= 0L) return
        val requestedPatterns = intent.getStringArrayListExtra(EXTRA_PATTERNS)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val requestedLabels = intent.getStringArrayListExtra(EXTRA_LABELS)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val requestedPrefix = sanitizeFilePrefix(intent.getStringExtra(EXTRA_FILE_PREFIX))

        stopCapture(finalize = true, stopService = false)

        sessionId = buildSessionId()
        startedAtMs = System.currentTimeMillis()
        durationMs = requestedDurationMs
        stopAtMs = startedAtMs + requestedDurationMs
        patterns = requestedPatterns
        labels = requestedLabels
        filePrefix = requestedPrefix
        lastFlushedMs = 0L
        liveFileName = prefixedFileName("activity_log_live_${sessionId}.txt")
        benchmarkLiveFileName = prefixedFileName("activity_log_benchmark_live_${sessionId}.json")
        benchmarkSamples = mutableListOf()
        lastCpuElapsedRealtimeMs = 0L
        lastCpuProcessTimeMs = 0L

        ActivityLog.setAllowedLabels(labels)
        ActivityLog.startCapture(patterns)
        ensureLiveFileHeader()
        writeBenchmarkLiveFile()
        persistState(status = STATUS_RUNNING, completedAtMs = null, finalFileName = null)
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleFlush()
        scheduleBenchmark()
        scheduleStop()
    }

    private fun restoreCaptureIfNeeded() {
        if (!ActivityLog.isEnabled()) {
            clearState()
            stopSelf()
            return
        }
        val state = loadState() ?: run {
            stopSelf()
            return
        }
        if (state.status != STATUS_RUNNING) {
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        val remaining = state.stopAtMs - now
        if (remaining <= 0L) {
            clearState()
            stopSelf()
            return
        }

        sessionId = state.sessionId
        startedAtMs = state.startedAtMs
        stopAtMs = state.stopAtMs
        durationMs = state.durationMs
        patterns = state.patterns
        labels = state.labels
        filePrefix = state.filePrefix
        liveFileName = state.liveFileName
        benchmarkLiveFileName = state.benchmarkLiveFileName
        lastFlushedMs = state.lastFlushedMs
        benchmarkSamples = loadBenchmarkSamples().toMutableList()
        lastCpuElapsedRealtimeMs = 0L
        lastCpuProcessTimeMs = 0L

        if (!ActivityLog.isCapturing()) {
            ActivityLog.setAllowedLabels(labels)
            ActivityLog.startCapture(patterns)
        }
        ensureLiveFileHeader()
        writeBenchmarkLiveFile()
        persistState(status = STATUS_RUNNING, completedAtMs = null, finalFileName = null)
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleFlush()
        scheduleBenchmark()
        scheduleStop()
    }

    private fun scheduleStop() {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        val delay = (stopAtMs - System.currentTimeMillis()).coerceAtLeast(1L)
        val runnable = Runnable { stopCapture(finalize = true, stopService = true) }
        stopRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun scheduleFlush() {
        flushRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (sessionId == null) return
                flushLiveLog()
                mainHandler.postDelayed(this, FLUSH_INTERVAL_MS)
            }
        }
        flushRunnable = runnable
        mainHandler.postDelayed(runnable, FLUSH_INTERVAL_MS)
    }

    private fun scheduleBenchmark() {
        benchmarkRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (sessionId == null) return
                captureBenchmarkSample()
                mainHandler.postDelayed(this, BENCHMARK_SAMPLE_INTERVAL_MS)
            }
        }
        benchmarkRunnable = runnable
        captureBenchmarkSample()
        mainHandler.postDelayed(runnable, BENCHMARK_SAMPLE_INTERVAL_MS)
    }

    private fun flushLiveLog() {
        if (!ActivityLog.isCapturing()) return
        val snapshot = ActivityLog.snapshot()
        val fresh = snapshot.filter { it.timeMs > lastFlushedMs }
        if (fresh.isEmpty()) return
        val liveFile = liveFile() ?: return
        val content = ActivityLog.format(fresh) + "\n"
        runCatching { liveFile.appendText(content) }
        lastFlushedMs = fresh.maxOf { it.timeMs }
        persistState(status = STATUS_RUNNING, completedAtMs = null, finalFileName = null)
    }

    private fun stopCapture(finalize: Boolean, stopService: Boolean) {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        stopRunnable = null
        flushRunnable?.let { mainHandler.removeCallbacks(it) }
        flushRunnable = null
        benchmarkRunnable?.let { mainHandler.removeCallbacks(it) }
        benchmarkRunnable = null

        val currentSessionId = sessionId
        if (currentSessionId == null) {
            if (stopService) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return
        }

        if (finalize) {
            flushLiveLog()
            val result = ActivityLog.stopCapture()
            val finalFileName = if (result == null) null else exportCapture(currentSessionId, result)
            exportBenchmark(currentSessionId)
            persistState(
                status = STATUS_COMPLETED,
                completedAtMs = System.currentTimeMillis(),
                finalFileName = finalFileName,
            )
        }

        sessionId = null
        startedAtMs = 0L
        stopAtMs = 0L
        durationMs = 0L
        patterns = emptyList()
        labels = emptyList()
        filePrefix = null
        liveFileName = null
        benchmarkLiveFileName = null
        lastFlushedMs = 0L
        benchmarkSamples = mutableListOf()
        lastCpuElapsedRealtimeMs = 0L
        lastCpuProcessTimeMs = 0L
        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun ensureLiveFileHeader() {
        val file = liveFile() ?: return
        if (file.exists()) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAtMs))
        val header = buildString {
            append("Session: ").append(sessionId).append('\n')
            append("Captured at: ").append(stamp).append('\n')
            append("Duration ms: ").append(durationMs).append('\n')
            append("Patterns: ").append(patterns.size).append('\n')
            append("Labels: ").append(labels.size).append("\n\n")
        }
        runCatching { file.writeText(header) }
    }

    private fun exportCapture(session: String, result: ActivityLog.CaptureResult): String? {
        val fileName = prefixedFileName("activity_log_${session}.txt")
        val file = File(activityLogDir(), fileName)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAtMs))
        val header = buildString {
            append("Session: ").append(session).append('\n')
            append("Captured at: ").append(stamp).append('\n')
            append("Duration ms: ").append(durationMs).append('\n')
            if (result.patternCounts.isNotEmpty()) {
                append("Pattern counts:\n")
                result.patternCounts.forEach { (pattern, count) ->
                    append(" - ").append(pattern).append(" x ").append(count).append('\n')
                }
            }
            append('\n')
        }
        val liveBody = liveFile()
            ?.takeIf { it.exists() }
            ?.let { source ->
                runCatching {
                    val text = source.readText()
                    val separator = "\n\n"
                    val splitIndex = text.indexOf(separator)
                    if (splitIndex >= 0) text.substring(splitIndex + separator.length) else text
                }.getOrNull()
            }
            ?.trim()
        val body = if (!liveBody.isNullOrEmpty()) {
            liveBody
        } else {
            ActivityLog.format(result.entries)
        }
        return runCatching {
            file.writeText(header + body)
            fileName
        }.getOrNull()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.activity_log_capture_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val now = System.currentTimeMillis()
        val remainingMs = (stopAtMs - now).coerceAtLeast(0L)
        val remainingSec = remainingMs / 1000L
        val content = getString(R.string.activity_log_capture_running_format, remainingSec)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mesh)
            .setContentTitle(getString(R.string.activity_log_capture_title))
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    private fun activityLogDir(): File {
        val root = getExternalFilesDir(null) ?: filesDir
        val dir = File(root, "activity_logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun stateFile(): File = File(activityLogDir(), STATE_FILE_NAME)

    private fun liveFile(): File? {
        val name = liveFileName ?: return null
        return File(activityLogDir(), name)
    }

    private fun benchmarkLiveFile(): File? {
        val name = benchmarkLiveFileName ?: return null
        return File(activityLogDir(), name)
    }

    private fun persistState(status: String, completedAtMs: Long?, finalFileName: String?) {
        val id = sessionId ?: return
        val json = JSONObject().apply {
            put("session_id", id)
            put("status", status)
            put("started_at_ms", startedAtMs)
            put("stop_at_ms", stopAtMs)
            put("duration_ms", durationMs)
            put("file_prefix", filePrefix)
            put("live_file_name", liveFileName)
            put("benchmark_live_file_name", benchmarkLiveFileName)
            put("last_flushed_ms", lastFlushedMs)
            put("patterns", JSONArray(patterns))
            put("labels", JSONArray(labels))
            if (completedAtMs != null) put("completed_at_ms", completedAtMs)
            if (finalFileName != null) put("final_file_name", finalFileName)
        }
        runCatching { stateFile().writeText(json.toString()) }
    }

    private fun clearState() {
        runCatching { stateFile().delete() }
    }

    private fun loadState(): SessionState? {
        val file = stateFile()
        if (!file.exists()) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return SessionState(
            sessionId = json.optString("session_id"),
            status = json.optString("status"),
            startedAtMs = json.optLong("started_at_ms"),
            stopAtMs = json.optLong("stop_at_ms"),
            durationMs = json.optLong("duration_ms"),
            filePrefix = sanitizeFilePrefix(json.optString("file_prefix").ifBlank { null }),
            liveFileName = json.optString("live_file_name").ifBlank { null },
            benchmarkLiveFileName = json.optString("benchmark_live_file_name").ifBlank { null },
            lastFlushedMs = json.optLong("last_flushed_ms"),
            patterns = json.optJSONArray("patterns").toStringList(),
            labels = json.optJSONArray("labels").toStringList(),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        val array = this ?: return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }

    private fun buildSessionId(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "$stamp-${UUID.randomUUID().toString().take(8)}"
    }

    private fun prefixedFileName(baseName: String): String {
        val prefix = filePrefix
        return if (prefix.isNullOrBlank()) baseName else "${prefix}_$baseName"
    }

    private fun sanitizeFilePrefix(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val normalized = buildString(trimmed.length) {
            trimmed.forEach { ch ->
                when {
                    ch.isLetterOrDigit() || ch == '-' || ch == '_' -> append(ch)
                    ch.isWhitespace() -> append('_')
                    else -> append('_')
                }
            }
        }.replace(Regex("_+"), "_").trim('_')
        return normalized.ifEmpty { null }
    }

    private fun captureBenchmarkSample() {
        val session = sessionId ?: return
        val nowMs = System.currentTimeMillis()
        val uptimeMs = SystemClock.elapsedRealtime()
        val processCpuTimeMs = Process.getElapsedCpuTime()
        val cpuPct = if (lastCpuElapsedRealtimeMs > 0L && lastCpuProcessTimeMs > 0L) {
            val elapsedDelta = (uptimeMs - lastCpuElapsedRealtimeMs).coerceAtLeast(1L)
            val cpuDelta = (processCpuTimeMs - lastCpuProcessTimeMs).coerceAtLeast(0L)
            (cpuDelta.toDouble() / elapsedDelta.toDouble()) * 100.0
        } else {
            null
        }
        lastCpuElapsedRealtimeMs = uptimeMs
        lastCpuProcessTimeMs = processCpuTimeMs

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val processMemory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }

        val sample = BenchmarkSample(
            sessionId = session,
            capturedAtMs = nowMs,
            appCpuPct = cpuPct,
            appTotalPssKb = processMemory.totalPss,
            appPrivateDirtyKb = processMemory.totalPrivateDirty,
            appSharedDirtyKb = processMemory.totalSharedDirty,
            systemAvailMemKb = memoryInfo.availMem / 1024L,
            systemTotalMemKb = memoryInfo.totalMem / 1024L,
            lowMemory = memoryInfo.lowMemory,
            thresholdKb = memoryInfo.threshold / 1024L,
        )
        benchmarkSamples.add(sample)
        writeBenchmarkLiveFile()
    }

    private fun writeBenchmarkLiveFile() {
        val file = benchmarkLiveFile() ?: return
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("started_at_ms", startedAtMs)
            put("duration_ms", durationMs)
            put("sample_interval_ms", BENCHMARK_SAMPLE_INTERVAL_MS)
            put("summary", buildBenchmarkSummaryJson())
            put("samples", JSONArray().apply {
                benchmarkSamples.forEach { put(it.toJson()) }
            })
        }
        runCatching { file.writeText(json.toString(2)) }
    }

    private fun loadBenchmarkSamples(): List<BenchmarkSample> {
        val file = benchmarkLiveFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        val samples = root.optJSONArray("samples") ?: return emptyList()
        return buildList {
            for (i in 0 until samples.length()) {
                val obj = samples.optJSONObject(i) ?: continue
                add(BenchmarkSample.fromJson(obj))
            }
        }
    }

    private fun exportBenchmark(session: String): String? {
        val fileName = prefixedFileName("activity_log_benchmark_${session}.json")
        val file = File(activityLogDir(), fileName)
        val json = JSONObject().apply {
            put("session_id", session)
            put("started_at_ms", startedAtMs)
            put("completed_at_ms", System.currentTimeMillis())
            put("duration_ms", durationMs)
            put("sample_interval_ms", BENCHMARK_SAMPLE_INTERVAL_MS)
            put("summary", buildBenchmarkSummaryJson())
            put("samples", JSONArray().apply {
                benchmarkSamples.forEach { put(it.toJson()) }
            })
        }
        return runCatching {
            file.writeText(json.toString(2))
            fileName
        }.getOrNull()
    }

    private fun buildBenchmarkSummaryJson(): JSONObject {
        val cpu = benchmarkSamples.mapNotNull { it.appCpuPct }
        val totalPss = benchmarkSamples.map { it.appTotalPssKb }
        val privateDirty = benchmarkSamples.map { it.appPrivateDirtyKb }
        val sharedDirty = benchmarkSamples.map { it.appSharedDirtyKb }
        val lowMemoryCount = benchmarkSamples.count { it.lowMemory }
        return JSONObject().apply {
            put("sample_count", benchmarkSamples.size)
            put("low_memory_count", lowMemoryCount)
            put("low_memory_ratio", ratio(lowMemoryCount, benchmarkSamples.size))
            put("cpu", numericSummary(cpu))
            put("app_total_pss_kb", numericSummary(totalPss))
            put("app_private_dirty_kb", numericSummary(privateDirty))
            put("app_shared_dirty_kb", numericSummary(sharedDirty))
        }
    }

    private fun numericSummary(values: List<Number>): JSONObject {
        val normalized = values.map { it.toDouble() }
        if (normalized.isEmpty()) {
            return JSONObject().put("count", 0)
        }
        return JSONObject().apply {
            put("count", normalized.size)
            put("min", normalized.minOrNull())
            put("avg", normalized.average())
            put("max", normalized.maxOrNull())
        }
    }

    private fun ratio(numerator: Int, denominator: Int): Double {
        if (denominator <= 0) return 0.0
        return numerator.toDouble() / denominator.toDouble()
    }

    private data class SessionState(
        val sessionId: String,
        val status: String,
        val startedAtMs: Long,
        val stopAtMs: Long,
        val durationMs: Long,
        val filePrefix: String?,
        val liveFileName: String?,
        val benchmarkLiveFileName: String?,
        val lastFlushedMs: Long,
        val patterns: List<String>,
        val labels: List<String>,
    )

    private data class BenchmarkSample(
        val sessionId: String,
        val capturedAtMs: Long,
        val appCpuPct: Double?,
        val appTotalPssKb: Int,
        val appPrivateDirtyKb: Int,
        val appSharedDirtyKb: Int,
        val systemAvailMemKb: Long,
        val systemTotalMemKb: Long,
        val lowMemory: Boolean,
        val thresholdKb: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("session_id", sessionId)
            put("captured_at_ms", capturedAtMs)
            if (appCpuPct != null) put("app_cpu_pct", appCpuPct)
            put("app_total_pss_kb", appTotalPssKb)
            put("app_private_dirty_kb", appPrivateDirtyKb)
            put("app_shared_dirty_kb", appSharedDirtyKb)
            put("system_avail_mem_kb", systemAvailMemKb)
            put("system_total_mem_kb", systemTotalMemKb)
            put("low_memory", lowMemory)
            put("threshold_kb", thresholdKb)
        }

        companion object {
            fun fromJson(json: JSONObject): BenchmarkSample = BenchmarkSample(
                sessionId = json.optString("session_id"),
                capturedAtMs = json.optLong("captured_at_ms"),
                appCpuPct = if (json.has("app_cpu_pct")) json.optDouble("app_cpu_pct") else null,
                appTotalPssKb = json.optInt("app_total_pss_kb"),
                appPrivateDirtyKb = json.optInt("app_private_dirty_kb"),
                appSharedDirtyKb = json.optInt("app_shared_dirty_kb"),
                systemAvailMemKb = json.optLong("system_avail_mem_kb"),
                systemTotalMemKb = json.optLong("system_total_mem_kb"),
                lowMemory = json.optBoolean("low_memory"),
                thresholdKb = json.optLong("threshold_kb"),
            )
        }
    }

    companion object {
        private const val ACTION_START_CAPTURE = "app.peermesh.action.START_ACTIVITY_LOG_CAPTURE"
        private const val ACTION_STOP_CAPTURE = "app.peermesh.action.STOP_ACTIVITY_LOG_CAPTURE"
        private const val ACTION_RESTORE_CAPTURE = "app.peermesh.action.RESTORE_ACTIVITY_LOG_CAPTURE"
        private const val EXTRA_DURATION_MS = "duration_ms"
        private const val EXTRA_PATTERNS = "patterns"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_FILE_PREFIX = "file_prefix"
        private const val CHANNEL_ID = "activity_log_capture"
        private const val NOTIFICATION_ID = 1102
        private const val FLUSH_INTERVAL_MS = 5_000L
        private const val BENCHMARK_SAMPLE_INTERVAL_MS = 5_000L
        private const val STATE_FILE_NAME = "activity_log_capture_state.json"
        private const val STATUS_RUNNING = "running"
        private const val STATUS_COMPLETED = "completed"

        fun startCapture(
            context: Context,
            durationMs: Long,
            patterns: List<String>,
            labels: List<String>,
            filePrefix: String = "",
        ) {
            if (!ActivityLog.isEnabled()) return
            val intent = Intent(context, ActivityLogCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_DURATION_MS, durationMs)
                putStringArrayListExtra(EXTRA_PATTERNS, ArrayList(patterns))
                putStringArrayListExtra(EXTRA_LABELS, ArrayList(labels))
                putExtra(EXTRA_FILE_PREFIX, filePrefix)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopCapture(context: Context) {
            if (!ActivityLog.isEnabled()) return
            val intent = Intent(context, ActivityLogCaptureService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.startService(intent)
        }
    }
}
