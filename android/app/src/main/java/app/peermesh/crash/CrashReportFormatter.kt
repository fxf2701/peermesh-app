package app.peermesh.crash

object CrashReportFormatter {
    fun feedbackMessage(report: CrashReport): String {
        return buildString {
            append("PeerMesh Crash Report\n\n")
            append("Timestamp: ").append(report.timestampIso).append('\n')
            append("App version: ").append(report.appVersion).append('\n')
            append("Android version: ").append(report.androidVersion).append('\n')
            append("Device model: ").append(report.deviceModel).append('\n')
            append("Thread: ").append(report.threadName).append('\n')
            append('\n')
            append(report.stacktrace)
        }
    }
}
