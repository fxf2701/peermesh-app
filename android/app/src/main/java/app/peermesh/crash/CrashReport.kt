package app.peermesh.crash

data class CrashReport(
    val timestampIso: String,
    val appVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val threadName: String,
    val stacktrace: String,
) {
    fun summaryLine(): String {
        val firstLine = stacktrace.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.isNotBlank()) firstLine else "Unexpected issue"
    }
}
