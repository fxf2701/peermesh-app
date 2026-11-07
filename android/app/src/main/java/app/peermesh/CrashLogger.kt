package app.peermesh

import android.content.Context
import android.os.Build
import app.peermesh.crash.CrashReport
import app.peermesh.crash.CrashStorage
import java.io.PrintWriter
import java.io.StringWriter

object CrashLogger {
    fun log(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val report = CrashReport(
            timestampIso = CrashStorage.isoTimestamp(),
            appVersion = BuildConfig.VERSION_NAME,
            androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            threadName = thread.name,
            stacktrace = stack,
        )
        CrashStorage.write(context, report)
        
    }
}
