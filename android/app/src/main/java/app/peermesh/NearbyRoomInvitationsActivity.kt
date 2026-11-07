package app.peermesh

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.nearby.NearbyPrivateGroupInvitationLocalAudienceRole
import app.peermesh.nearby.NearbyRoomRepository
import app.peermesh.nearby.presentation.NearbyPrivateGroupInvitationPresentationContext
import app.peermesh.nearby.presentation.NearbyPrivateGroupInvitationPresentationMapper
import app.peermesh.nearby.presentation.NearbyPrivateGroupInvitationTimelineProjection
import app.peermesh.nearby.presentation.NearbyPublicGroupInvitationPresentationMapper
import app.peermesh.nearby.presentation.NearbyRoomTimelineItem
import app.peermesh.nearby.presentation.PrivateInvitationAuthorizedAudience

/** Read-only room invitation index. It projects only Repository-authoritative visible invitations. */
class NearbyRoomInvitationsActivity : AppCompatActivity() {
    private val repository by lazy { NearbyRoomRepository(applicationContext) }
    private val privateMapper = NearbyPrivateGroupInvitationPresentationMapper()
    private val publicMapper = NearbyPublicGroupInvitationPresentationMapper()

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var count: TextView
    private lateinit var adapter: InvitationListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeConfig.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_room_invitations)

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        val targetMessageId = intent.getStringExtra(EXTRA_TARGET_MESSAGE_ID)
        if (roomId.isNullOrBlank()) {
            finish()
            return
        }

        list = findViewById(R.id.nearby_room_invitations_list)
        empty = findViewById(R.id.nearby_room_invitations_empty)
        count = findViewById(R.id.nearby_room_invitations_count)
        adapter = InvitationListAdapter(
            onOpen = { item ->
                when (item) {
                    is NearbyRoomTimelineItem.PublicInvitation -> startActivity(
                        NearbyPublicGroupInvitationViewerActivity.intent(this, roomId, item.messageId)
                    )
                    is NearbyRoomTimelineItem.PrivateInvitation -> startActivity(
                        NearbyPrivateGroupInvitationViewerActivity.intent(this, roomId, item.messageId)
                    )
                    else -> Unit
                }
            },
            onJoin = { item ->
                if (item is NearbyRoomTimelineItem.PublicInvitation) {
                    NearbyPublicGroupInvitationJoin.open(this, roomId, item.messageId)
                }
            },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<TextView>(R.id.header_title).text = getString(
            R.string.nearby_room_invitations_title,
            getString(R.string.nearby_room_invitations_room_fallback),
        )
        findViewById<ImageButton>(R.id.header_menu).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            contentDescription = getString(R.string.nearby_public_invitation_back)
            setOnClickListener { finish() }
        }

    }

    override fun onResume() {
        super.onResume()
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: return
        if (::adapter.isInitialized) load(roomId, intent.getStringExtra(EXTRA_TARGET_MESSAGE_ID))
    }

    private fun load(roomId: String, targetMessageId: String?) {
        Thread {
            val roomName = repository.getRoom(roomId)?.name.orEmpty().ifBlank {
                getString(R.string.nearby_room_invitations_room_fallback)
            }
            val items = repository.listMessages(roomId, limit = 300)
                .mapNotNull { message ->
                    val privateInspection = repository.inspectPrivateGroupInvitationForTimeline(roomId, message.message_id)
                    val privateContext = privateInspection.context?.let { context ->
                        NearbyPrivateGroupInvitationPresentationContext(
                            messageId = context.messageId,
                            createdAtMs = context.createdAtMs,
                            isHiddenLocally = context.hiddenLocally,
                            audience = when (context.audienceRole) {
                                NearbyPrivateGroupInvitationLocalAudienceRole.LOCAL_SENDER ->
                                    PrivateInvitationAuthorizedAudience.LOCAL_SENDER
                                NearbyPrivateGroupInvitationLocalAudienceRole.INTENDED_RECIPIENT ->
                                    PrivateInvitationAuthorizedAudience.INTENDED_RECIPIENT
                            },
                            senderId = context.logicalSenderId,
                            senderDisplayName = context.senderDisplayName,
                            canMutePeer = context.moderationCapabilities.canMutePeer,
                        )
                    }
                    when (val privateProjection = privateMapper.map(privateContext, privateInspection.receiveResult)) {
                        is NearbyPrivateGroupInvitationTimelineProjection.Item ->
                            privateProjection.item as? NearbyRoomTimelineItem.PrivateInvitation
                        NearbyPrivateGroupInvitationTimelineProjection.Omit -> null
                        NearbyPrivateGroupInvitationTimelineProjection.UseNormalMessage ->
                            publicMapper.mapForRoomInvitationIndex(
                                repository.inspectPublicGroupInvitationForTimeline(roomId, message.message_id)
                            )
                    }
                }
                .sortedWith(compareBy<NearbyRoomTimelineItem> { it.createdAtMs }.thenBy { it.messageId })

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                findViewById<TextView>(R.id.header_title).text = getString(
                    R.string.nearby_room_invitations_title,
                    roomName,
                )
                adapter.submit(items)
                count.text = resources.getQuantityString(
                    R.plurals.nearby_room_invitations_count,
                    items.size,
                    items.size,
                )
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                val targetIndex = targetMessageId?.let { id -> items.indexOfFirst { it.messageId == id } } ?: -1
                if (targetIndex >= 0) {
                    list.post { (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetIndex, 24) }
                }
            }
        }.start()
    }

    private class InvitationListAdapter(
        private val onOpen: (NearbyRoomTimelineItem) -> Unit,
        private val onJoin: (NearbyRoomTimelineItem) -> Unit,
    ) : RecyclerView.Adapter<InvitationListAdapter.Holder>() {
        private val items = mutableListOf<NearbyRoomTimelineItem>()

        fun submit(value: List<NearbyRoomTimelineItem>) {
            if (items == value) return
            items.clear()
            items.addAll(value)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_nearby_room_invitation_list, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position], onOpen, onJoin)
        override fun getItemCount(): Int = items.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val avatarContainer = view.findViewById<FrameLayout>(R.id.invitation_list_avatar_container)
            private val avatarText = view.findViewById<TextView>(R.id.invitation_list_avatar_text)
            private val sender = view.findViewById<TextView>(R.id.invitation_list_sender)
            private val time = view.findViewById<TextView>(R.id.invitation_list_time)
            private val kind = view.findViewById<TextView>(R.id.invitation_list_kind)
            private val title = view.findViewById<TextView>(R.id.invitation_list_title)
            private val description = view.findViewById<TextView>(R.id.invitation_list_description)
            private val join = view.findViewById<View>(R.id.invitation_list_join)

            fun bind(
                item: NearbyRoomTimelineItem,
                onOpen: (NearbyRoomTimelineItem) -> Unit,
                onJoin: (NearbyRoomTimelineItem) -> Unit,
            ) {
                val senderId: String
                val senderName: String
                val titleText: String
                val descriptionText: String
                val kindRes: Int
                when (item) {
                    is NearbyRoomTimelineItem.PublicInvitation -> {
                        senderId = item.senderId
                        senderName = item.senderDisplayName
                        titleText = item.title
                        descriptionText = item.shortDescription
                        kindRes = R.string.nearby_public_invitation_card_label
                    }
                    is NearbyRoomTimelineItem.PrivateInvitation -> {
                        senderId = item.senderId
                        senderName = item.senderDisplayName
                        titleText = item.title
                        descriptionText = item.shortDescription
                        kindRes = R.string.nearby_room_invitations_private_label
                    }
                    else -> return
                }
                sender.text = senderName
                time.text = formatChatMessageTime(item.createdAtMs)
                kind.setText(kindRes)
                title.text = titleText
                description.text = descriptionText
                description.visibility = if (descriptionText.isBlank()) View.GONE else View.VISIBLE
                val color = AvatarUtils.avatarColorForKey(senderId)
                avatarContainer.background.setTint(color)
                avatarText.setTextColor(ColorStateList.valueOf(AvatarUtils.avatarTextColor(color)))
                avatarText.text = AvatarUtils.avatarInitials(senderName)
                itemView.setOnClickListener { onOpen(item) }
                join.setOnClickListener { onJoin(item) }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_ROOM_ID = "nearby_room_invitations_room_id"
        private const val EXTRA_TARGET_MESSAGE_ID = "nearby_room_invitations_target_message_id"

        fun intent(context: Context, roomId: String, targetMessageId: String): Intent =
            Intent(context, NearbyRoomInvitationsActivity::class.java)
                .putExtra(EXTRA_ROOM_ID, roomId)
                .putExtra(EXTRA_TARGET_MESSAGE_ID, targetMessageId)
    }
}
