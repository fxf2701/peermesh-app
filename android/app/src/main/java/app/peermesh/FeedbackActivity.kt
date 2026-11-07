package app.peermesh

import android.os.Bundle
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.feedback.FeedbackRepository

class FeedbackActivity : AppCompatActivity() {
    private lateinit var messageInput: android.widget.EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var sendButton: Button
    private lateinit var statusText: TextView

    @Volatile
    private var submitting = false
    private var pendingFeedbackId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback)

        val headerTitle = findViewById<TextView>(R.id.header_title)
        val headerMenu = findViewById<ImageButton>(R.id.header_menu)
        messageInput = findViewById(R.id.feedback_message_input)
        categorySpinner = findViewById(R.id.feedback_category_spinner)
        sendButton = findViewById(R.id.feedback_send_button)
        statusText = findViewById(R.id.feedback_status)

        headerTitle.text = getString(R.string.feedback_title)
        headerMenu.setImageResource(R.drawable.ic_close)
        headerMenu.setOnClickListener { finish() }

        val categories = listOf(
            CategoryOption(getString(R.string.feedback_category_general), FeedbackRepository.CATEGORY_GENERAL),
            CategoryOption(getString(R.string.feedback_category_bug), FeedbackRepository.CATEGORY_BUG),
            CategoryOption(
                getString(R.string.feedback_category_feature_request),
                FeedbackRepository.CATEGORY_FEATURE_REQUEST,
            ),
            CategoryOption(
                getString(R.string.feedback_category_nearby_rooms),
                FeedbackRepository.CATEGORY_NEARBY_ROOMS,
            ),
            CategoryOption(getString(R.string.feedback_category_groups), FeedbackRepository.CATEGORY_GROUPS),
            CategoryOption(
                getString(R.string.feedback_category_maps_awareness),
                FeedbackRepository.CATEGORY_MAPS_AWARENESS,
            ),
        )
        val adapter = ArrayAdapter(this, R.layout.spinner_item_primary, categories)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_primary)
        categorySpinner.adapter = adapter

        pendingFeedbackId = savedInstanceState?.getString(STATE_PENDING_FEEDBACK_ID)
        messageInput.setText(savedInstanceState?.getString(STATE_MESSAGE).orEmpty())
        val savedCategory = savedInstanceState?.getString(STATE_CATEGORY)
        val selectedIndex = categories.indexOfFirst { it.value == savedCategory }.takeIf { it >= 0 } ?: 0
        categorySpinner.setSelection(selectedIndex)

        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSendButtonState()
        })
        sendButton.setOnClickListener { submitFeedback() }
        updateSendButtonState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_FEEDBACK_ID, pendingFeedbackId)
        outState.putString(STATE_MESSAGE, messageInput.text?.toString())
        outState.putString(STATE_CATEGORY, selectedCategory().value)
    }

    private fun submitFeedback() {
        if (submitting) return
        val message = messageInput.text?.toString()?.trim().orEmpty()
        if (message.isBlank()) {
            messageInput.error = getString(R.string.feedback_message_required)
            return
        }
        messageInput.error = null
        submitting = true
        updateSendButtonState()
        statusText.visibility = View.GONE
        val category = selectedCategory().value
        Thread {
            val result = runCatching {
                FeedbackRepository(applicationContext).submitFeedback(
                    message = message,
                    category = category,
                    feedbackId = pendingFeedbackId,
                )
            }.getOrElse {
                FeedbackRepository.SubmitUiResult(
                    feedbackId = pendingFeedbackId ?: "",
                    success = false,
                    queuedForRetry = true,
                    errorCode = "submit_exception",
                )
            }
            runOnUiThread {
                submitting = false
                if (result.feedbackId.isNotBlank()) {
                    pendingFeedbackId = result.feedbackId
                }
                if (result.success) {
                    Toast.makeText(this, R.string.feedback_submit_success, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    val messageRes = if (result.queuedForRetry) {
                        R.string.feedback_submit_retry_message
                    } else {
                        R.string.feedback_submit_failed_message
                    }
                    statusText.visibility = View.VISIBLE
                    statusText.text = getString(messageRes)
                    if (result.queuedForRetry) {
                        sendButton.text = getString(R.string.feedback_retry_button)
                    }
                    Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
                    updateSendButtonState()
                }
            }
        }.start()
    }

    private fun selectedCategory(): CategoryOption {
        return categorySpinner.selectedItem as? CategoryOption
            ?: CategoryOption(getString(R.string.feedback_category_general), FeedbackRepository.CATEGORY_GENERAL)
    }

    private fun updateSendButtonState() {
        sendButton.isEnabled = !submitting && messageInput.text?.toString()?.trim()?.isNotEmpty() == true
        if (!submitting && sendButton.text != getString(R.string.feedback_retry_button)) {
            sendButton.text = getString(R.string.feedback_send_button)
        }
    }

    private data class CategoryOption(val label: String, val value: String) {
        override fun toString(): String = label
    }

    companion object {
        private const val STATE_PENDING_FEEDBACK_ID = "pending_feedback_id"
        private const val STATE_MESSAGE = "message"
        private const val STATE_CATEGORY = "category"
    }
}
