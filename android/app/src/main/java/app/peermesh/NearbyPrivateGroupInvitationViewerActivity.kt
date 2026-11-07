package app.peermesh

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.nearby.NearbyPrivateGroupInvitationLocalAudienceRole
import app.peermesh.nearby.NearbyRoomRepository
import app.peermesh.nearby.presentation.NearbyPrivateGroupInvitationPresentationContext
import app.peermesh.nearby.presentation.NearbyPrivateGroupInvitationPresentationMapper
import app.peermesh.nearby.presentation.NearbyPrivateGroupInvitationViewerMapper
import app.peermesh.nearby.presentation.NearbyPrivateGroupInvitationViewerState
import app.peermesh.nearby.presentation.PrivateInvitationAuthorizedAudience

class NearbyPrivateGroupInvitationViewerActivity : AppCompatActivity() {
    private val repository by lazy { NearbyRoomRepository(applicationContext) }
    private val presentationMapper = NearbyPrivateGroupInvitationPresentationMapper()
    private val viewerMapper = NearbyPrivateGroupInvitationViewerMapper()

    private lateinit var content: View
    private lateinit var unavailable: TextView
    private lateinit var sender: TextView
    private lateinit var recipientOnlyHint: TextView
    private lateinit var title: TextView
    private lateinit var description: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_private_group_invitation_viewer)

        findViewById<TextView>(R.id.header_title).setText(R.string.nearby_private_invitation_viewer_title)
        findViewById<ImageButton>(R.id.header_menu).visibility = View.GONE
        content = findViewById(R.id.private_invitation_viewer_content)
        unavailable = findViewById(R.id.private_invitation_viewer_unavailable)
        sender = findViewById(R.id.private_invitation_viewer_sender)
        recipientOnlyHint = findViewById(R.id.private_invitation_viewer_recipient_only_hint)
        title = findViewById(R.id.private_invitation_viewer_invitation_title)
        description = findViewById(R.id.private_invitation_viewer_description)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
        if (roomId.isNullOrBlank() || messageId.isNullOrBlank()) {
            render(NearbyPrivateGroupInvitationViewerState.Unavailable)
            return
        }
        load(roomId, messageId)
    }

    private fun load(roomId: String, messageId: String) {
        render(NearbyPrivateGroupInvitationViewerState.Loading)
        Thread {
            val inspection = repository.inspectPrivateGroupInvitationForTimeline(roomId, messageId)
            val context = inspection.context?.let {
                NearbyPrivateGroupInvitationPresentationContext(
                    messageId = it.messageId,
                    createdAtMs = it.createdAtMs,
                    isHiddenLocally = it.hiddenLocally,
                    audience = when (it.audienceRole) {
                        NearbyPrivateGroupInvitationLocalAudienceRole.LOCAL_SENDER -> PrivateInvitationAuthorizedAudience.LOCAL_SENDER
                        NearbyPrivateGroupInvitationLocalAudienceRole.INTENDED_RECIPIENT -> PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT
                    },
                    senderId = it.logicalSenderId,
                    senderDisplayName = it.senderDisplayName,
                    canMutePeer = it.moderationCapabilities.canMutePeer,
                )
            }
            val state = viewerMapper.map(presentationMapper.map(context, inspection.receiveResult))
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    render(state)
                }
            }
        }.start()
    }

    private fun render(state: NearbyPrivateGroupInvitationViewerState) {
        when (state) {
            NearbyPrivateGroupInvitationViewerState.Loading -> {
                content.visibility = View.GONE
                unavailable.visibility = View.GONE
            }
            is NearbyPrivateGroupInvitationViewerState.Content -> {
                sender.text = state.senderDisplayName
                title.text = state.title
                description.text = state.description
                recipientOnlyHint.visibility =
                    if (state.showRecipientOnlyHint) View.VISIBLE else View.GONE
                description.visibility = if (state.description.isBlank()) View.GONE else View.VISIBLE
                unavailable.visibility = View.GONE
                content.visibility = View.VISIBLE
            }
            NearbyPrivateGroupInvitationViewerState.Unavailable -> {
                sender.text = ""
                title.text = ""
                description.text = ""
                recipientOnlyHint.visibility = View.GONE
                content.visibility = View.GONE
                unavailable.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        private const val EXTRA_ROOM_ID = "nearby_private_invitation_viewer_room_id"
        private const val EXTRA_MESSAGE_ID = "nearby_private_invitation_viewer_message_id"

        fun intent(context: Context, roomId: String, messageId: String): Intent =
            Intent(context, NearbyPrivateGroupInvitationViewerActivity::class.java)
                .putExtra(EXTRA_ROOM_ID, roomId)
                .putExtra(EXTRA_MESSAGE_ID, messageId)
    }
}
