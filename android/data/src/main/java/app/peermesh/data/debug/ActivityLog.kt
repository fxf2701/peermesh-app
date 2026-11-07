package app.peermesh.data.debug

import app.peermesh.core.model.EventCore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object ActivityLog {
    @Volatile
    private var enabled = false
    @Volatile
    private var allowedLabels: Set<String> = emptySet()
    data class Entry(
        val timeMs: Long,
        val label: String,
        val source: String?,
        val message: String?,
        val eventId: String?,
        val gid: String?,
    )

    data class CaptureResult(
        val entries: List<Entry>,
        val patternCounts: Map<String, Int>,
    )

    interface Listener {
        fun onUpdated(entries: List<Entry>)
    }

    private const val MAX_ENTRIES = 200
    private val entries = ArrayDeque<Entry>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var captureActive = false
    private val patternCounters = LinkedHashMap<String, Int>()

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            allowedLabels = emptySet()
            synchronized(entries) {
                captureActive = false
                entries.clear()
                patternCounters.clear()
            }
            listeners.forEach { it.onUpdated(emptyList()) }
        }
    }

    fun isEnabled(): Boolean = enabled

    fun add(
        label: String,
        source: String? = null,
        message: String? = null,
        eventId: String? = null,
        gid: String? = null,
        timeMs: Long = System.currentTimeMillis(),
    ) {
        if (!enabled) return
        if (!captureActive) return
        val allow = allowedLabels
        if (allow.isNotEmpty() && !allow.contains(label)) return
        val entry = Entry(timeMs, label, source, message?.trim(), eventId, gid)
        val snapshot = synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
            updatePatternCounts(entry)
            entries.toList()
        }
        listeners.forEach { it.onUpdated(snapshot) }
    }

    fun addFromCore(
        label: String,
        source: String?,
        gid: String?,
        core: EventCore,
    ) {
        if (!enabled) return
        add(
            label = label,
            source = source,
            message = extractMessage(core),
            eventId = core.id,
            gid = gid,
        )
    }

    fun clear() {
        if (!enabled) return
        val snapshot = synchronized(entries) {
            entries.clear()
            patternCounters.clear()
            emptyList<Entry>()
        }
        listeners.forEach { it.onUpdated(snapshot) }
    }

    fun snapshot(): List<Entry> {
        if (!enabled) return emptyList()
        return synchronized(entries) { entries.toList() }
    }

    fun addListener(listener: Listener) {
        if (!enabled) {
            listener.onUpdated(emptyList())
            return
        }
        listeners.addIfAbsent(listener)
        listener.onUpdated(snapshot())
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun startCapture(patterns: List<String>) {
        if (!enabled) return
        val cleaned = patterns.mapNotNull { it.trim().ifEmpty { null } }
        val snapshot = synchronized(entries) {
            entries.clear()
            patternCounters.clear()
            cleaned.forEach { patternCounters[it] = 0 }
            captureActive = true
            addInternal(
                label = "Activity log started",
                source = "debug",
                message = "patterns=${cleaned.size} labels=${allowedLabels.size}",
            )
            entries.toList()
        }
        listeners.forEach { it.onUpdated(snapshot) }
    }

    fun setAllowedLabels(labels: List<String>) {
        if (!enabled) {
            allowedLabels = emptySet()
            return
        }
        allowedLabels = labels.mapNotNull { it.trim().ifEmpty { null } }.toSet()
    }

    fun stopCapture(): CaptureResult? {
        if (!enabled) return null
        val result = synchronized(entries) {
            if (!captureActive) return null
            captureActive = false
            val snapshot = entries.toList()
            val counts = patternCounters.toMap()
            entries.clear()
            patternCounters.clear()
            CaptureResult(snapshot, counts)
        }
        listeners.forEach { it.onUpdated(emptyList()) }
        return result
    }

    fun isCapturing(): Boolean = enabled && captureActive

    fun format(entries: List<Entry>): String {
        if (!enabled) return ""
        return entries.joinToString("\n") { entry ->
            formatEntry(entry)
        }
    }

    private fun extractMessage(core: EventCore): String? {
        val data = core.data as? JsonObject ?: return null
        val primitive = data["text"]?.jsonPrimitive ?: return null
        val text = if (primitive.isString) primitive.content else null
        return text?.trim()?.ifBlank { null }
    }

    private fun formatTime(timeMs: Long): String {
        val formatter = timeFormatter.get() ?: SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return formatter.format(Date(timeMs))
    }

    private fun truncate(value: String, maxLen: Int = 120): String {
        return if (value.length <= maxLen) value else value.take(maxLen - 1) + "…"
    }

    private fun updatePatternCounts(entry: Entry) {
        if (patternCounters.isEmpty()) return
        val line = formatEntry(entry)
        patternCounters.keys.forEach { pattern ->
            if (line.contains(pattern)) {
                patternCounters[pattern] = (patternCounters[pattern] ?: 0) + 1
            }
        }
    }

    private fun addInternal(
        label: String,
        source: String? = null,
        message: String? = null,
        eventId: String? = null,
        gid: String? = null,
        timeMs: Long = System.currentTimeMillis(),
    ) {
        val entry = Entry(timeMs, label, source, message?.trim(), eventId, gid)
        val snapshot = synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
            updatePatternCounts(entry)
            entries.toList()
        }
        listeners.forEach { it.onUpdated(snapshot) }
    }

    private fun formatEntry(entry: Entry): String {
        val time = formatTime(entry.timeMs)
        val source = entry.source?.let { "source=$it" }
        val gid = entry.gid?.let { "gid=$it" }
        val eventId = entry.eventId?.let { "eventId=$it" }
        val msg = entry.message?.let { "msg=\"${truncate(it)}\"" }
        return listOfNotNull(time, entry.label, source, gid, eventId, msg).joinToString(" ")
    }

    private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }

}
