package app.peermesh

import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.data.debug.ActivityLog
import app.peermesh.nearby.NearbyPrivateGroupInvitationSendPipeline
import app.peermesh.nearby.NearbyPrivateInvitationComposerAuthority
import app.peermesh.nearby.NearbyPrivateInvitationGroupOption
import app.peermesh.nearby.NearbyRoomRepository
import app.peermesh.nearby.NearbyRoomTransportManager
import app.peermesh.nearby.presentation.NearbyPrivateInvitationComposerOutcome
import app.peermesh.nearby.presentation.NearbyPrivateInvitationComposerState

class NearbyPrivateInvitationComposerActivity : AppCompatActivity() {
    private val repository by lazy { NearbyRoomRepository(applicationContext) }
    private val transportManager by lazy { NearbyRoomTransportManager(applicationContext, repository) }
    private val pipeline by lazy {
        NearbyPrivateGroupInvitationSendPipeline(applicationContext, repository, transportManager)
    }
    private val authority by lazy {
        NearbyPrivateInvitationComposerAuthority(applicationContext, pipeline)
    }
    private var groups: List<NearbyPrivateInvitationGroupOption> = emptyList()
    private var state = NearbyPrivateInvitationComposerState()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_private_invitation_composer)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        val targetId = intent.getStringExtra(EXTRA_TARGET_PARTICIPANT_ID)
        if (
            roomId.isNullOrBlank() ||
            targetId.isNullOrBlank() ||
            targetId == authority.localDeviceId()
        ) {
            finish()
            return
        }

        findViewById<TextView>(R.id.header_title)
            .setText(R.string.nearby_private_invitation_compose_title)

        val spinner = findViewById<Spinner>(R.id.private_invitation_group)
        val title = findViewById<EditText>(R.id.private_invitation_title)
        val description = findViewById<EditText>(R.id.private_invitation_description)
        val titleCounter = findViewById<TextView>(R.id.private_invitation_title_counter)
        val descriptionCounter = findViewById<TextView>(R.id.private_invitation_description_counter)
        val previewSender = findViewById<TextView>(R.id.private_invitation_preview_sender)
        val previewTitle = findViewById<TextView>(R.id.private_invitation_preview_title)
        val previewDescription = findViewById<TextView>(R.id.private_invitation_preview_description)
        val send = findViewById<Button>(R.id.private_invitation_send)
        val cancel = findViewById<Button>(R.id.private_invitation_cancel)
        val status = findViewById<TextView>(R.id.private_invitation_status)
        val progress = findViewById<ProgressBar>(R.id.private_invitation_progress)

        title.filters = arrayOf(
            InputFilter.LengthFilter(NearbyPrivateInvitationComposerState.MAX_TITLE_CHARS)
        )
        description.filters = arrayOf(
            InputFilter.LengthFilter(NearbyPrivateInvitationComposerState.MAX_DESCRIPTION_CHARS)
        )
        previewSender.text = LocalUserProfileStore(applicationContext).load()?.displayName
            ?: getString(R.string.nearby_private_invitation_unknown_sender)

        fun render() {
            titleCounter.text = getString(
                R.string.character_counter,
                title.text.length,
                NearbyPrivateInvitationComposerState.MAX_TITLE_CHARS,
            )
            descriptionCounter.text = getString(
                R.string.character_counter,
                description.text.length,
                NearbyPrivateInvitationComposerState.MAX_DESCRIPTION_CHARS,
            )
            previewTitle.text = title.text.toString()
                .ifBlank { getString(R.string.nearby_private_invitation_title_hint) }
            previewDescription.text = description.text.toString()
            previewDescription.visibility =
                if (description.text.isNullOrBlank()) View.GONE else View.VISIBLE
            send.isEnabled = state.canSend
            listOf(spinner, title, description, cancel).forEach {
                it.isEnabled = !state.sending && !state.alreadySent
            }
            status.visibility = if (state.alreadySent) View.VISIBLE else View.GONE
            progress.visibility = if (state.sending) View.VISIBLE else View.GONE
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) {
                state = state.copy(
                    title = title.text.toString(),
                    description = description.text.toString(),
                )
                render()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        }
        title.addTextChangedListener(watcher)
        description.addTextChangedListener(watcher)
        cancel.setOnClickListener { finish() }

        Thread {
            val available = runCatching { authority.loadEligibleGroups() }
            runOnUiThread {
                available.onSuccess { loadedGroups ->
                    groups = loadedGroups
                    spinner.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf(getString(R.string.nearby_private_invitation_select_group)) +
                            loadedGroups.map { it.displayName },
                    )
                    spinner.setSelection(0)
                    spinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
                        val group = groups.getOrNull(position - 1)
                        state = state.copy(selectedGroupId = group?.groupId)
                        if (group != null) {
                            title.setText(group.displayName)
                        }
                        render()
                    }
                }.onFailure {
                    toast(getString(R.string.nearby_private_invitation_create_failed))
                }
                render()
            }
        }.start()

        send.setOnClickListener {
            val draft = state.toDraft()
            ActivityLog.add(
                label = "Private invitation submit",
                source = "nearby",
                message =
                    "action=requested draft_present=${draft != null} " +
                        "selected_group_present=${state.selectedGroupId != null}",
            )
            if (draft == null) return@setOnClickListener

            val submitStartedAtMs = SystemClock.elapsedRealtime()
            state = state.copy(sending = true)
            render()
            Thread {
                val result = runCatching { authority.send(roomId, targetId, draft) }
                val outcome = result.fold(
                    onSuccess = NearbyPrivateInvitationComposerOutcome::from,
                    onFailure = { NearbyPrivateInvitationComposerOutcome.RetryableCreationFailure },
                )
                val durationMs = SystemClock.elapsedRealtime() - submitStartedAtMs
                ActivityLog.add(
                    label = "Private invitation submit",
                    source = "nearby",
                    message = buildString {
                        append("action=completed outcome=")
                        append(outcome.javaClass.simpleName)
                        append(" pipeline_result=")
                        append(result.getOrNull()?.javaClass?.simpleName ?: "none")
                        append(" duration_ms=")
                        append(durationMs)
                        result.exceptionOrNull()?.let { error ->
                            append(" exception_type=")
                            append(error.javaClass.simpleName)
                        }
                    },
                )
                runOnUiThread {
                    state = state.copy(
                        sending = false,
                        alreadySent = outcome == NearbyPrivateInvitationComposerOutcome.SendLimitReached,
                    )
                    when (outcome) {
                        NearbyPrivateInvitationComposerOutcome.Sent -> {
                            toast(getString(R.string.nearby_private_invitation_send_success))
                            setResult(RESULT_OK)
                            finish()
                        }
                        NearbyPrivateInvitationComposerOutcome.PersistedForRetry -> {
                            toast(getString(R.string.nearby_private_invitation_persisted_retry))
                            setResult(RESULT_OK)
                            finish()
                        }
                        NearbyPrivateInvitationComposerOutcome.SendLimitReached -> {
                            toast(getString(R.string.nearby_private_invitation_already_sent))
                            render()
                        }
                        NearbyPrivateInvitationComposerOutcome.RetryableCreationFailure -> {
                            toast(getString(R.string.nearby_private_invitation_create_failed))
                            render()
                        }
                    }
                }
            }.start()
        }

        render()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_TARGET_PARTICIPANT_ID = "extra_target_participant_id"
    }
}

private class SimpleItemSelectedListener(
    private val selected: (Int) -> Unit,
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long,
    ) = selected(position)

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = selected(0)
}
