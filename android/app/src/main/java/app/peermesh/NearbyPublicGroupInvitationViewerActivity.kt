package app.peermesh

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.peermesh.nearby.NearbyGroupInvitationLocalVisibilityResult
import app.peermesh.nearby.NearbyRoomRepository
import app.peermesh.nearby.presentation.NearbyPublicGroupInvitationPresentationMapper
import app.peermesh.nearby.presentation.NearbyPublicGroupInvitationViewerMapper
import app.peermesh.nearby.presentation.NearbyPublicGroupInvitationViewerState

class NearbyPublicGroupInvitationViewerActivity : AppCompatActivity() {
    private val repository by lazy { NearbyRoomRepository(applicationContext) }
    private val presentationMapper = NearbyPublicGroupInvitationPresentationMapper()
    private val viewerMapper = NearbyPublicGroupInvitationViewerMapper()

    private lateinit var content: View
    private lateinit var unavailable: TextView
    private lateinit var avatarContainer: FrameLayout
    private lateinit var avatarText: TextView
    private lateinit var sender: TextView
    private lateinit var time: TextView
    private lateinit var title: TextView
    private lateinit var description: TextView
    private lateinit var join: View
    private lateinit var hide: View
    private var currentRoomId: String? = null
    private var currentMessageId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_public_group_invitation_viewer)

        findViewById<TextView>(R.id.header_title).setText(R.string.nearby_public_invitation_viewer_title)
        findViewById<ImageButton>(R.id.header_menu).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            contentDescription = getString(R.string.nearby_public_invitation_back)
            setOnClickListener { finish() }
        }
        content = findViewById(R.id.public_invitation_viewer_content)
        unavailable = findViewById(R.id.public_invitation_viewer_unavailable)
        avatarContainer = findViewById(R.id.public_invitation_viewer_avatar_container)
        avatarText = findViewById(R.id.public_invitation_viewer_avatar_text)
        sender = findViewById(R.id.public_invitation_viewer_sender)
        time = findViewById(R.id.public_invitation_viewer_time)
        title = findViewById(R.id.public_invitation_viewer_invitation_title)
        description = findViewById(R.id.public_invitation_viewer_description)
        join = findViewById(R.id.public_invitation_viewer_join)
        hide = findViewById(R.id.public_invitation_viewer_hide)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
        if (roomId.isNullOrBlank() || messageId.isNullOrBlank()) {
            render(NearbyPublicGroupInvitationViewerState.Unavailable)
            return
        }
        currentRoomId = roomId
        currentMessageId = messageId
        join.setOnClickListener {
            val roomId = currentRoomId ?: return@setOnClickListener
            val messageId = currentMessageId ?: return@setOnClickListener
            NearbyPublicGroupInvitationJoin.open(this, roomId, messageId)
        }
        hide.setOnClickListener { hideInvitation() }
        load(roomId, messageId)
    }

    private fun load(roomId: String, messageId: String) {
        render(NearbyPublicGroupInvitationViewerState.Loading)
        Thread {
            val state = viewerMapper.map(
                presentationMapper.map(repository.inspectPublicGroupInvitationForTimeline(roomId, messageId))
            )
            runOnUiThread {
                if (!isFinishing && !isDestroyed) render(state)
            }
        }.start()
    }

    private fun render(state: NearbyPublicGroupInvitationViewerState) {
        when (state) {
            NearbyPublicGroupInvitationViewerState.Loading -> {
                content.visibility = View.GONE
                unavailable.visibility = View.GONE
            }
            is NearbyPublicGroupInvitationViewerState.Content -> {
                sender.text = state.senderDisplayName
                time.text = formatChatMessageTime(state.createdAtMs)
                title.text = state.title
                description.text = state.description
                description.visibility = if (state.description.isBlank()) View.GONE else View.VISIBLE
                val avatarColor = AvatarUtils.avatarColorForKey(state.senderId)
                avatarContainer.background.setTint(avatarColor)
                avatarText.setTextColor(ColorStateList.valueOf(AvatarUtils.avatarTextColor(avatarColor)))
                avatarText.text = AvatarUtils.avatarInitials(state.senderDisplayName)
                unavailable.visibility = View.GONE
                content.visibility = View.VISIBLE
            }
            NearbyPublicGroupInvitationViewerState.Unavailable -> {
                sender.text = ""
                time.text = ""
                title.text = ""
                description.text = ""
                content.visibility = View.GONE
                unavailable.visibility = View.VISIBLE
            }
        }
    }

    private fun hideInvitation() {
        val roomId = currentRoomId ?: return
        val messageId = currentMessageId ?: return
        hide.isEnabled = false
        Thread {
            val result = repository.setPublicGroupInvitationHiddenLocally(roomId, messageId, hidden = true)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                hide.isEnabled = true
                when (result) {
                    NearbyGroupInvitationLocalVisibilityResult.Updated,
                    NearbyGroupInvitationLocalVisibilityResult.AlreadyInRequestedState,
                    NearbyGroupInvitationLocalVisibilityResult.MessageNotFound -> finish()
                    NearbyGroupInvitationLocalVisibilityResult.NotGroupInvitation,
                    NearbyGroupInvitationLocalVisibilityResult.NotAuthorizedViewer,
                    NearbyGroupInvitationLocalVisibilityResult.InvitationInvalid ->
                        toast(getString(R.string.nearby_public_invitation_hide_failed))
                }
            }
        }.start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_ROOM_ID = "nearby_public_invitation_viewer_room_id"
        private const val EXTRA_MESSAGE_ID = "nearby_public_invitation_viewer_message_id"

        fun intent(context: Context, roomId: String, messageId: String): Intent =
            Intent(context, NearbyPublicGroupInvitationViewerActivity::class.java)
                .putExtra(EXTRA_ROOM_ID, roomId)
                .putExtra(EXTRA_MESSAGE_ID, messageId)
    }
}
