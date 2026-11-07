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
import app.peermesh.nearby.NearbyPublicGroupInvitationSendPipeline
import app.peermesh.nearby.NearbyPublicInvitationComposerAuthority
import app.peermesh.nearby.NearbyPublicInvitationGroupOption
import app.peermesh.nearby.NearbyRoomRepository
import app.peermesh.nearby.NearbyRoomTransportManager
import app.peermesh.nearby.presentation.NearbyPublicInvitationComposerOutcome
import app.peermesh.nearby.presentation.NearbyPublicInvitationComposerState

class NearbyPublicInvitationComposerActivity : AppCompatActivity() {
    private val repository by lazy { NearbyRoomRepository(applicationContext) }
    private val transportManager by lazy { NearbyRoomTransportManager(applicationContext, repository) }
    private val pipeline by lazy {
        NearbyPublicGroupInvitationSendPipeline(applicationContext, repository, transportManager)
    }
    private val authority by lazy {
        NearbyPublicInvitationComposerAuthority(applicationContext, pipeline)
    }
    private var groups: List<NearbyPublicInvitationGroupOption> = emptyList()
    private var state = NearbyPublicInvitationComposerState()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_public_invitation_composer)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        if (roomId.isNullOrBlank()) {
            finish()
            return
        }

        findViewById<TextView>(R.id.header_title).setText(R.string.nearby_public_invitation_compose_title)
        val spinner = findViewById<Spinner>(R.id.public_invitation_group)
        val title = findViewById<EditText>(R.id.public_invitation_title)
        val description = findViewById<EditText>(R.id.public_invitation_description)
        val titleCounter = findViewById<TextView>(R.id.public_invitation_title_counter)
        val descriptionCounter = findViewById<TextView>(R.id.public_invitation_description_counter)
        val previewSender = findViewById<TextView>(R.id.public_invitation_preview_sender)
        val previewTitle = findViewById<TextView>(R.id.public_invitation_preview_title)
        val previewDescription = findViewById<TextView>(R.id.public_invitation_preview_description)
        val send = findViewById<Button>(R.id.public_invitation_send)
        val cancel = findViewById<Button>(R.id.public_invitation_cancel)
        val status = findViewById<TextView>(R.id.public_invitation_status)
        val progress = findViewById<ProgressBar>(R.id.public_invitation_progress)

        title.filters = arrayOf(InputFilter.LengthFilter(NearbyPublicInvitationComposerState.MAX_TITLE_CHARS))
        description.filters = arrayOf(InputFilter.LengthFilter(NearbyPublicInvitationComposerState.MAX_DESCRIPTION_CHARS))
        previewSender.text = LocalUserProfileStore(applicationContext).load()?.displayName
            ?: getString(R.string.nearby_public_invitation_unknown_sender)

        fun render() {
            titleCounter.text = getString(R.string.character_counter, title.text.length, NearbyPublicInvitationComposerState.MAX_TITLE_CHARS)
            descriptionCounter.text = getString(R.string.character_counter, description.text.length, NearbyPublicInvitationComposerState.MAX_DESCRIPTION_CHARS)
            previewTitle.text = title.text.toString().ifBlank { getString(R.string.nearby_public_invitation_title_hint) }
            previewDescription.text = description.text.toString()
            previewDescription.visibility = if (description.text.isNullOrBlank()) View.GONE else View.VISIBLE
            send.isEnabled = state.canSend
            listOf(spinner, title, description, cancel).forEach { it.isEnabled = !state.sending && !state.alreadySent }
            status.visibility = if (state.alreadySent) View.VISIBLE else View.GONE
            progress.visibility = if (state.sending) View.VISIBLE else View.GONE
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                state = state.copy(title = title.text.toString(), description = description.text.toString())
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
                        listOf(getString(R.string.nearby_public_invitation_select_group)) + loadedGroups.map { it.displayName },
                    )
                    spinner.setSelection(0)
                    spinner.onItemSelectedListener = PublicInvitationItemSelectedListener { position ->
                        val group = groups.getOrNull(position - 1)
                        state = state.copy(selectedGroupId = group?.groupId)
                        if (group != null) title.setText(group.displayName)
                        render()
                    }
                }.onFailure {
                    toast(getString(R.string.nearby_public_invitation_create_failed))
                }
                render()
            }
        }.start()

        send.setOnClickListener {
            val draft = state.toDraft()
            ActivityLog.add(
                label = "Public invitation submit",
                source = "nearby",
                message = "action=requested draft_present=${draft != null} selected_group_present=${state.selectedGroupId != null}",
            )
            if (draft == null) return@setOnClickListener
            val submitStartedAtMs = SystemClock.elapsedRealtime()
            state = state.copy(sending = true)
            render()
            Thread {
                val result = runCatching { authority.send(roomId, draft) }
                val outcome = result.fold(
                    onSuccess = NearbyPublicInvitationComposerOutcome::from,
                    onFailure = { NearbyPublicInvitationComposerOutcome.RetryableCreationFailure },
                )
                ActivityLog.add(
                    label = "Public invitation submit",
                    source = "nearby",
                    message = "action=completed outcome=${outcome.javaClass.simpleName} pipeline_result=${result.getOrNull()?.javaClass?.simpleName ?: "none"} duration_ms=${SystemClock.elapsedRealtime() - submitStartedAtMs}",
                )
                runOnUiThread {
                    state = state.copy(
                        sending = false,
                        alreadySent = outcome == NearbyPublicInvitationComposerOutcome.SendLimitReached,
                    )
                    when (outcome) {
                        NearbyPublicInvitationComposerOutcome.Sent -> {
                            toast(getString(R.string.nearby_public_invitation_send_success)); setResult(RESULT_OK); finish()
                        }
                        NearbyPublicInvitationComposerOutcome.PersistedForRetry -> {
                            toast(getString(R.string.nearby_public_invitation_persisted_retry)); setResult(RESULT_OK); finish()
                        }
                        NearbyPublicInvitationComposerOutcome.SendLimitReached -> {
                            toast(getString(R.string.nearby_public_invitation_already_sent)); render()
                        }
                        NearbyPublicInvitationComposerOutcome.RetryableCreationFailure -> {
                            toast(getString(R.string.nearby_public_invitation_create_failed)); render()
                        }
                    }
                }
            }.start()
        }
        render()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
    }
}

private class PublicInvitationItemSelectedListener(
    private val selected: (Int) -> Unit,
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = selected(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = selected(0)
}
