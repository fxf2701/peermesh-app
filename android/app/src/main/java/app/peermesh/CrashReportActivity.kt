package app.peermesh

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.crash.CrashReportFormatter
import app.peermesh.crash.CrashStorage
import app.peermesh.feedback.FeedbackRepository
import java.io.File

class CrashReportActivity : AppCompatActivity() {
    @Volatile
    private var submitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_report)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        val summaryText = findViewById<TextView>(R.id.crash_summary)
        val versionText = findViewById<TextView>(R.id.crash_version)
        val androidText = findViewById<TextView>(R.id.crash_android)
        val deviceText = findViewById<TextView>(R.id.crash_device)
        val timeText = findViewById<TextView>(R.id.crash_time)
        val detailsToggle = findViewById<TextView>(R.id.crash_details_toggle)
        val detailsBody = findViewById<TextView>(R.id.crash_details_body)
        val deleteButton = findViewById<Button>(R.id.crash_delete)
        val sendButton = findViewById<Button>(R.id.crash_send)

        headerTitle.text = getString(R.string.crash_review_title)
        headerMenu.setImageResource(R.drawable.ic_close)
        headerMenu.setOnClickListener { finish() }

        val fileName = intent.getStringExtra(EXTRA_FILE_NAME)
        if (fileName.isNullOrBlank()) {
            Toast.makeText(this, R.string.crash_missing_report, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val file = File(CrashStorage.crashDir(this), fileName)
        val report = CrashStorage.readReport(file)
        if (report == null) {
            Toast.makeText(this, R.string.crash_missing_report, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        summaryText.text = report.summaryLine()
        versionText.text = report.appVersion
        androidText.text = report.androidVersion
        deviceText.text = report.deviceModel
        timeText.text = report.timestampIso

        detailsBody.text = report.stacktrace
        detailsBody.visibility = View.GONE
        detailsToggle.setOnClickListener {
            if (detailsBody.visibility == View.VISIBLE) {
                detailsBody.visibility = View.GONE
                detailsToggle.text = getString(R.string.crash_details_expand)
            } else {
                detailsBody.visibility = View.VISIBLE
                detailsToggle.text = getString(R.string.crash_details_collapse)
            }
        }

        deleteButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.crash_delete_title)
                .setMessage(R.string.crash_delete_body)
                .setNegativeButton(R.string.crash_delete_cancel, null)
                .setPositiveButton(R.string.crash_delete_confirm) { _, _ ->
                    CrashStorage.deleteReport(file)
                    Toast.makeText(this, R.string.crash_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
                .show()
        }

        sendButton.setOnClickListener {
            submitCrashReport(
                file = file,
                report = report,
                sendButton = sendButton,
            )
        }
    }

    private fun submitCrashReport(
        file: File,
        report: app.peermesh.crash.CrashReport,
        sendButton: Button,
    ) {
        if (submitting) return
        submitting = true
        sendButton.isEnabled = false

        Thread {
            val result = runCatching {
                FeedbackRepository(applicationContext).submitFeedback(
                    message = CrashReportFormatter.feedbackMessage(report),
                    category = FeedbackRepository.CATEGORY_BUG,
                    feedbackId = crashFeedbackId(file),
                    source = FeedbackRepository.SOURCE_ANDROID_BETA,
                    feedbackType = FeedbackRepository.TYPE_CRASH_REPORT,
                )
            }.getOrElse {
                FeedbackRepository.SubmitUiResult(
                    feedbackId = crashFeedbackId(file),
                    success = false,
                    queuedForRetry = true,
                    errorCode = "crash_submit_exception",
                )
            }

            runOnUiThread {
                submitting = false
                sendButton.isEnabled = true
                when {
                    result.success -> {
                        CrashStorage.deleteReport(file)
                        Toast.makeText(this, R.string.crash_sent_thanks, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    result.queuedForRetry -> {
                        CrashStorage.deleteReport(file)
                        Toast.makeText(this, R.string.crash_submit_retry_message, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    else -> {
                        Toast.makeText(this, R.string.crash_submit_failed_message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun crashFeedbackId(file: File): String {
        return "crash:${file.nameWithoutExtension}"
    }

    companion object {
        const val EXTRA_FILE_NAME = "crash_file_name"
    }
}
