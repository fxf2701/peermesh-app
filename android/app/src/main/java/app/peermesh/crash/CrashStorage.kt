package app.peermesh.crash

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CrashStorage {
    private const val MAX_FILES = 5
    private const val DIR_NAME = "crash_logs"

    fun write(context: Context, report: CrashReport): File? {
        val dir = crashDir(context)
        if (!dir.exists()) dir.mkdirs()
        val stamp = fileStamp()
        val file = File(dir, "crash_$stamp.log")
        val json = JSONObject().apply {
            put("timestamp", report.timestampIso)
            put("app_version", report.appVersion)
            put("android_version", report.androidVersion)
            put("device_model", report.deviceModel)
            put("thread", report.threadName)
            put("stacktrace", report.stacktrace)
        }
        return runCatching {
            file.writeText(json.toString(2))
            enforceRetention(dir)
            file
        }.getOrNull()
    }

    fun listReports(context: Context): List<File> {
        val dir = crashDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun latestReportFile(context: Context): File? {
        return listReports(context).firstOrNull()
    }

    fun readReport(file: File): CrashReport? {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return CrashReport(
            timestampIso = json.optString("timestamp"),
            appVersion = json.optString("app_version"),
            androidVersion = json.optString("android_version"),
            deviceModel = json.optString("device_model"),
            threadName = json.optString("thread"),
            stacktrace = json.optString("stacktrace"),
        )
    }

    fun deleteReport(file: File): Boolean {
        return runCatching { file.delete() }.getOrDefault(false)
    }

    fun crashDir(context: Context): File {
        return File(context.filesDir, DIR_NAME)
    }

    private fun enforceRetention(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_FILES)
            .forEach { it.delete() }
    }

    private fun fileStamp(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return formatter.format(Date())
    }

    fun isoTimestamp(now: Date = Date()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(now)
    }
}
