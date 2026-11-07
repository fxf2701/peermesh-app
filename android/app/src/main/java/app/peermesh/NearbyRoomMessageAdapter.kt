package app.peermesh

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.nearby.presentation.NearbyRoomTimelineItem
import app.peermesh.nearby.presentation.PrivateInvitationAction
import app.peermesh.nearby.presentation.PrivateInvitationAudienceLabel
import app.peermesh.nearby.presentation.PrivateInvitationPlaceholderKind

class NearbyRoomMessageAdapter(
    private val currentDeviceId: String,
    private val onReplyRequested: ((NearbyRoomMessageEntity) -> Unit)? = null,
    private val onReplyPreviewClicked: ((String) -> Unit)? = null,
    private val onBlockRequested: ((NearbyRoomMessageEntity) -> Unit)? = null,
    private val onMuteRequested: ((NearbyRoomMessageEntity) -> Unit)? = null,
    private val onReportMessageRequested: ((NearbyRoomMessageEntity) -> Unit)? = null,
    private val onReportPeerRequested: ((NearbyRoomMessageEntity) -> Unit)? = null,
    private val onEditRequested: ((NearbyRoomMessageEntity) -> Unit)? = null,
    private val onDeleteRequested: ((NearbyRoomMessageEntity) -> Unit)? = null,
    private val onPrivateInvitationAction: ((String, String, PrivateInvitationAction) -> Unit)? = null,
    private val onPublicInvitationOpen: ((String) -> Unit)? = null,
    private val onPublicInvitationJoin: ((String) -> Unit)? = null,
    private val onPublicInvitationHide: ((String) -> Unit)? = null,
    private val onPublicInvitationUnhide: ((String) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    data class ParticipantProfileUi(val displayName: String?, val avatarId: String?)

    private val items = mutableListOf<NearbyRoomTimelineItem>()
    private var messagesById: Map<String, NearbyRoomMessageEntity> = emptyMap()
    private var highlightedMessageId: String? = null
    private var isLocalRoomOwner: Boolean = false
    private var mutedPeerIds: Set<String> = emptySet()
    private var localProfile: LocalUserProfile? = null
    private var participantProfilesByDeviceId: Map<String, ParticipantProfileUi> = emptyMap()

    init { setHasStableIds(true) }

    fun submitList(timelineItems: List<NearbyRoomTimelineItem>): Boolean {
        if (items == timelineItems) return false
        items.clear()
        items.addAll(timelineItems)
        messagesById = timelineItems.mapNotNull { (it as? NearbyRoomTimelineItem.NormalMessage)?.source }
            .associateBy { it.message_id }
        notifyDataSetChanged()
        return true
    }

    fun currentLastMessageId(): String? = items.lastOrNull()?.messageId
    fun indexOfMessageId(messageId: String): Int = items.indexOfFirst { it.messageId == messageId }
    fun normalMessages(): List<NearbyRoomMessageEntity> = items.mapNotNull { (it as? NearbyRoomTimelineItem.NormalMessage)?.source }

    fun updateRoomModerationState(isLocalOwner: Boolean, mutedPeerIds: Set<String>) {
        if (this.isLocalRoomOwner == isLocalOwner && this.mutedPeerIds == mutedPeerIds) return
        this.isLocalRoomOwner = isLocalOwner
        this.mutedPeerIds = mutedPeerIds
        notifyDataSetChanged()
    }
    fun updateLocalProfile(localProfile: LocalUserProfile?) { if (this.localProfile != localProfile) { this.localProfile = localProfile; notifyDataSetChanged() } }
    fun updateParticipantProfiles(value: Map<String, ParticipantProfileUi>) { if (participantProfilesByDeviceId != value) { participantProfilesByDeviceId = value; notifyDataSetChanged() } }
    fun setHighlightedMessageId(messageId: String?) { if (highlightedMessageId != messageId) { highlightedMessageId = messageId; notifyDataSetChanged() } }

    override fun getItemId(position: Int): Long = items[position].messageId.hashCode().toLong()
    override fun getItemCount(): Int = items.size
    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is NearbyRoomTimelineItem.NormalMessage -> VIEW_NORMAL
        is NearbyRoomTimelineItem.PrivateInvitation -> VIEW_INVITATION
        is NearbyRoomTimelineItem.PublicInvitation -> VIEW_PUBLIC_INVITATION
        is NearbyRoomTimelineItem.HiddenPublicInvitation -> VIEW_HIDDEN_PUBLIC_INVITATION
        is NearbyRoomTimelineItem.HiddenPrivateInvitation,
        is NearbyRoomTimelineItem.PrivateInvitationPlaceholder -> VIEW_PLACEHOLDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_NORMAL -> NormalMessageViewHolder(inflater.inflate(R.layout.item_nearby_room_message, parent, false))
            VIEW_INVITATION -> PrivateInvitationViewHolder(inflater.inflate(R.layout.item_nearby_room_private_invitation, parent, false))
            VIEW_PUBLIC_INVITATION -> PublicInvitationViewHolder(inflater.inflate(R.layout.item_nearby_room_public_invitation, parent, false))
            VIEW_HIDDEN_PUBLIC_INVITATION -> HiddenPublicInvitationViewHolder(inflater.inflate(R.layout.item_nearby_room_public_invitation_hidden, parent, false))
            else -> PrivateInvitationPlaceholderViewHolder(inflater.inflate(R.layout.item_nearby_room_invitation_placeholder, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is NearbyRoomTimelineItem.NormalMessage -> bindNormal(holder as NormalMessageViewHolder, item.source)
            is NearbyRoomTimelineItem.PrivateInvitation -> (holder as PrivateInvitationViewHolder).bind(item, onPrivateInvitationAction)
            is NearbyRoomTimelineItem.PublicInvitation -> (holder as PublicInvitationViewHolder).bind(
                item = item,
                openCallback = onPublicInvitationOpen,
                joinCallback = onPublicInvitationJoin,
                hideCallback = onPublicInvitationHide,
                currentDeviceId = currentDeviceId,
                localProfile = localProfile,
                participantProfilesByDeviceId = participantProfilesByDeviceId,
            )
            is NearbyRoomTimelineItem.HiddenPublicInvitation -> (holder as HiddenPublicInvitationViewHolder).bind(
                item = item,
                unhideCallback = onPublicInvitationUnhide,
                currentDeviceId = currentDeviceId,
                localProfile = localProfile,
                participantProfilesByDeviceId = participantProfilesByDeviceId,
            )
            is NearbyRoomTimelineItem.HiddenPrivateInvitation -> (holder as PrivateInvitationPlaceholderViewHolder).bindHidden(item, onPrivateInvitationAction)
            is NearbyRoomTimelineItem.PrivateInvitationPlaceholder -> (holder as PrivateInvitationPlaceholderViewHolder).bindFailure(item, onPrivateInvitationAction)
        }
    }

    private fun bindNormal(holder: NormalMessageViewHolder, message: NearbyRoomMessageEntity) {
        val isDeleted = message.deleted_at_ms != null
        holder.bind(message, message.reply_to?.let(messagesById::get), onReplyPreviewClicked,
            message.message_id == highlightedMessageId, currentDeviceId, localProfile, participantProfilesByDeviceId)
        holder.itemView.setOnClickListener {
            if (message.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM || isDeleted) return@setOnClickListener
            val popup = android.widget.PopupMenu(holder.itemView.context, holder.itemView)
            popup.menu.add(0, ACTION_REPLY_ID, 0, holder.itemView.context.getString(R.string.chat_reply_action))
            val ownerDeviceId = message.owner_device_id ?: message.sender_device_id
            if (ownerDeviceId == currentDeviceId) {
                popup.menu.add(0, ACTION_EDIT_ID, 1, holder.itemView.context.getString(R.string.chat_edit_action))
                popup.menu.add(0, ACTION_DELETE_ID, 2, holder.itemView.context.getString(R.string.chat_delete_action))
            } else {
                popup.menu.add(0, ACTION_REPORT_MESSAGE_ID, 1, holder.itemView.context.getString(R.string.moderation_report_message))
                popup.menu.add(0, ACTION_REPORT_PEER_ID, 2, holder.itemView.context.getString(R.string.moderation_report_peer))
                popup.menu.add(0, ACTION_BLOCK_ID, 3, holder.itemView.context.getString(R.string.moderation_block_peer))
                if (isLocalRoomOwner && !mutedPeerIds.contains(ownerDeviceId)) popup.menu.add(0, ACTION_MUTE_ID, 4, holder.itemView.context.getString(R.string.nearby_room_mute_peer))
            }
            popup.setOnMenuItemClickListener { menu -> when (menu.itemId) {
                ACTION_REPLY_ID -> { onReplyRequested?.invoke(message); true }
                ACTION_EDIT_ID -> { onEditRequested?.invoke(message); true }
                ACTION_DELETE_ID -> { onDeleteRequested?.invoke(message); true }
                ACTION_REPORT_MESSAGE_ID -> { onReportMessageRequested?.invoke(message); true }
                ACTION_REPORT_PEER_ID -> { onReportPeerRequested?.invoke(message); true }
                ACTION_BLOCK_ID -> { onBlockRequested?.invoke(message); true }
                ACTION_MUTE_ID -> { onMuteRequested?.invoke(message); true }
                else -> false
            } }
            popup.show()
        }
    }

    class NormalMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val container = view
        private val avatarContainer = view.findViewById<FrameLayout>(R.id.nearby_room_message_avatar_container)
        private val avatarText = view.findViewById<TextView>(R.id.nearby_room_message_avatar_text)
        private val actor = view.findViewById<TextView>(R.id.nearby_room_message_actor)
        private val time = view.findViewById<TextView>(R.id.nearby_room_message_time)
        private val editedBadge = view.findViewById<TextView>(R.id.nearby_room_message_edited_badge)
        private val replyContainer = view.findViewById<View>(R.id.nearby_room_message_reply_container)
        private val replyActor = view.findViewById<TextView>(R.id.nearby_room_message_reply_actor)
        private val replyText = view.findViewById<TextView>(R.id.nearby_room_message_reply_text)
        private val body = view.findViewById<TextView>(R.id.nearby_room_message_body)
        private val meta = view.findViewById<TextView>(R.id.nearby_room_message_meta)
        private val defaultBodyTextColor = body.currentTextColor
        private val defaultMetaTextColor = meta.currentTextColor
        private val defaultBodyTextSize = body.textSize
        private val defaultBodyTypeface = body.typeface

        fun bind(
            message: NearbyRoomMessageEntity,
            replySource: NearbyRoomMessageEntity?,
            onReplyPreviewClicked: ((String) -> Unit)?,
            highlighted: Boolean,
            currentDeviceId: String,
            localProfile: LocalUserProfile?,
            participantProfilesByDeviceId: Map<String, ParticipantProfileUi>,
        ) {
            val context = itemView.context
            val timeText = formatChatMessageTime(message.created_at_ms)
            val isDeleted = message.deleted_at_ms != null
            if (message.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM) {
                container.background = null
                avatarContainer.visibility = View.GONE
                actor.visibility = View.GONE
                time.visibility = View.GONE
                editedBadge.visibility = View.GONE
                replyContainer.visibility = View.GONE
                body.text = message.body
                body.gravity = android.view.Gravity.CENTER_HORIZONTAL
                body.setTextColor(resolveNearbyThemeColor(itemView, android.R.attr.textColorSecondary))
                body.setTypeface(null, Typeface.NORMAL)
                body.textSize = 14f
                meta.visibility = View.GONE
            } else {
                container.background = ContextCompat.getDrawable(context, R.drawable.bg_chat_message)
                container.background = ContextCompat.getDrawable(
                    context,
                    if (highlighted) R.drawable.bg_chat_message_highlight else R.drawable.bg_chat_message
                )
                avatarContainer.visibility = View.VISIBLE
                actor.visibility = View.VISIBLE
                time.visibility = View.VISIBLE
                val isLocalUser = (message.owner_device_id ?: message.sender_device_id) == currentDeviceId
                val profileKey = message.owner_device_id ?: message.sender_device_id
                val participantProfile = participantProfilesByDeviceId[profileKey]
                val actorLabel = LocalUserProfileUi.resolveDisplayName(
                    rawDisplayName = message.sender_display_name ?: participantProfile?.displayName,
                    isLocalUser = isLocalUser,
                    localProfile = localProfile,
                )
                actor.text = actorLabel
                time.text = timeText
                val avatarColor = AvatarUtils.avatarColorForKey(message.sender_device_id)
                avatarContainer.background.setTint(avatarColor)
                avatarText.setTextColor(ColorStateList.valueOf(AvatarUtils.avatarTextColor(avatarColor)))
                avatarText.text = when {
                    isLocalUser && !localProfile?.avatarId.isNullOrBlank() ->
                        AvatarUtils.avatarEmojiForId(context, localProfile?.avatarId)
                    !participantProfile?.avatarId.isNullOrBlank() ->
                        AvatarUtils.avatarEmojiForId(context, participantProfile?.avatarId)
                    else -> AvatarUtils.avatarInitials(actorLabel)
                }
                editedBadge.visibility = if (!isDeleted && message.edited_at_ms != null) View.VISIBLE else View.GONE
                body.text = if (isDeleted) itemView.context.getString(R.string.chat_message_deleted) else message.body
                body.gravity = android.view.Gravity.NO_GRAVITY
                body.setTextColor(
                    if (isDeleted) {
                        ContextCompat.getColor(itemView.context, android.R.color.darker_gray)
                    } else {
                        defaultBodyTextColor
                    }
                )
                body.setTypeface(null, if (isDeleted) Typeface.ITALIC else defaultBodyTypeface.style)
                body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, defaultBodyTextSize)
                bindReplyPreview(message, replySource, onReplyPreviewClicked)
                meta.text = nearbyRoomDeliveryLabel(context, message.delivery_state)
                meta.visibility = View.VISIBLE
                meta.gravity = android.view.Gravity.NO_GRAVITY
                meta.setTextColor(defaultMetaTextColor)
            }
        }

        private fun bindReplyPreview(
            message: NearbyRoomMessageEntity,
            replySource: NearbyRoomMessageEntity?,
            onReplyPreviewClicked: ((String) -> Unit)?,
        ) {
            val replyTo = message.reply_to
            if (replyTo.isNullOrBlank()) {
                replyContainer.visibility = View.GONE
                replyContainer.setOnClickListener(null)
                return
            }
            val actorLabel = replySource?.let {
                LocalUserProfileUi.resolveDisplayName(it.sender_display_name)
            } ?: itemView.context.getString(R.string.chat_reply_original_unavailable)
            val textLabel = replySource?.body
                ?.takeIf { it.isNotBlank() }
                ?.let { truncateNearbyReplyPreview(it, maxChars = 25) }
                ?: itemView.context.getString(R.string.chat_reply_original_unavailable)
            val resolvedTextLabel = if (replySource?.deleted_at_ms != null) {
                itemView.context.getString(R.string.chat_message_deleted)
            } else {
                textLabel
            }
            val secondaryColor = resolveNearbyThemeColor(itemView, android.R.attr.textColorSecondary)
            replyActor.text = truncateNearbyReplyPreview(actorLabel, maxChars = 25)
            replyText.text = truncateNearbyReplyPreview(resolvedTextLabel, maxChars = 25)
            replyActor.setTextColor(secondaryColor)
            replyText.setTextColor(secondaryColor)
            replyActor.setTypeface(null, Typeface.BOLD_ITALIC)
            replyText.setTypeface(null, Typeface.ITALIC)
            replyContainer.visibility = View.VISIBLE
            replyContainer.setOnClickListener { onReplyPreviewClicked?.invoke(replyTo) }
        }
    }

    class PublicInvitationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatarContainer = view.findViewById<FrameLayout>(R.id.public_invitation_avatar_container)
        private val avatarText = view.findViewById<TextView>(R.id.public_invitation_avatar_text)
        private val sender = view.findViewById<TextView>(R.id.public_invitation_sender)
        private val title = view.findViewById<TextView>(R.id.public_invitation_title)
        private val description = view.findViewById<TextView>(R.id.public_invitation_description)
        private val time = view.findViewById<TextView>(R.id.public_invitation_time)
        private val content = view.findViewById<View>(R.id.public_invitation_content)
        private val join = view.findViewById<View>(R.id.public_invitation_join)
        private val hide = view.findViewById<View>(R.id.public_invitation_hide)

        fun bind(
            item: NearbyRoomTimelineItem.PublicInvitation,
            openCallback: ((String) -> Unit)?,
            joinCallback: ((String) -> Unit)?,
            hideCallback: ((String) -> Unit)?,
            currentDeviceId: String,
            localProfile: LocalUserProfile?,
            participantProfilesByDeviceId: Map<String, ParticipantProfileUi>,
        ) {
            val context = itemView.context
            sender.text = item.senderDisplayName
            title.text = item.title
            description.text = item.shortDescription
            description.visibility = if (item.shortDescription.isBlank()) View.GONE else View.VISIBLE
            time.text = formatChatMessageTime(item.createdAtMs)

            val isLocalUser = item.senderId == currentDeviceId
            val participantProfile = participantProfilesByDeviceId[item.senderId]
            val avatarColor = AvatarUtils.avatarColorForKey(item.senderId)
            avatarContainer.background.setTint(avatarColor)
            avatarText.setTextColor(ColorStateList.valueOf(AvatarUtils.avatarTextColor(avatarColor)))
            avatarText.text = when {
                isLocalUser && !localProfile?.avatarId.isNullOrBlank() ->
                    AvatarUtils.avatarEmojiForId(context, localProfile?.avatarId)
                !participantProfile?.avatarId.isNullOrBlank() ->
                    AvatarUtils.avatarEmojiForId(context, participantProfile?.avatarId)
                else -> AvatarUtils.avatarInitials(item.senderDisplayName)
            }

            itemView.contentDescription = listOf(sender.text, title.text, description.text)
                .filter { it.isNotBlank() }
                .joinToString(". ")
            content.setOnClickListener { openCallback?.invoke(item.messageId) }
            join.setOnClickListener { joinCallback?.invoke(item.messageId) }
            hide.setOnClickListener { hideCallback?.invoke(item.messageId) }
        }
    }

    class HiddenPublicInvitationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatarContainer = view.findViewById<FrameLayout>(R.id.hidden_public_invitation_avatar_container)
        private val avatarText = view.findViewById<TextView>(R.id.hidden_public_invitation_avatar_text)
        private val sender = view.findViewById<TextView>(R.id.hidden_public_invitation_sender)
        private val time = view.findViewById<TextView>(R.id.hidden_public_invitation_time)
        private val unhide = view.findViewById<TextView>(R.id.hidden_public_invitation_unhide)

        fun bind(
            item: NearbyRoomTimelineItem.HiddenPublicInvitation,
            unhideCallback: ((String) -> Unit)?,
            currentDeviceId: String,
            localProfile: LocalUserProfile?,
            participantProfilesByDeviceId: Map<String, ParticipantProfileUi>,
        ) {
            val context = itemView.context
            sender.text = item.senderDisplayName
            time.text = formatChatMessageTime(item.createdAtMs)

            val isLocalUser = item.senderId == currentDeviceId
            val participantProfile = participantProfilesByDeviceId[item.senderId]
            val avatarColor = AvatarUtils.avatarColorForKey(item.senderId)
            avatarContainer.background.setTint(avatarColor)
            avatarText.setTextColor(ColorStateList.valueOf(AvatarUtils.avatarTextColor(avatarColor)))
            avatarText.text = when {
                isLocalUser && !localProfile?.avatarId.isNullOrBlank() ->
                    AvatarUtils.avatarEmojiForId(context, localProfile?.avatarId)
                !participantProfile?.avatarId.isNullOrBlank() ->
                    AvatarUtils.avatarEmojiForId(context, participantProfile?.avatarId)
                else -> AvatarUtils.avatarInitials(item.senderDisplayName)
            }
            itemView.contentDescription = context.getString(
                R.string.nearby_public_invitation_hidden_accessibility,
                item.senderDisplayName,
            )
            unhide.text = context.getString(R.string.nearby_public_invitation_unhide)
            unhide.setOnClickListener { unhideCallback?.invoke(item.messageId) }
        }
    }

    class PrivateInvitationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val sender = view.findViewById<TextView>(R.id.private_invitation_sender)
        private val audience = view.findViewById<TextView>(R.id.private_invitation_audience)
        private val title = view.findViewById<TextView>(R.id.private_invitation_title)
        private val description = view.findViewById<TextView>(R.id.private_invitation_description)
        private val time = view.findViewById<TextView>(R.id.private_invitation_time)
        fun bind(item: NearbyRoomTimelineItem.PrivateInvitation, callback: ((String, String, PrivateInvitationAction) -> Unit)?) {
            sender.text = item.senderDisplayName
            audience.text = itemView.context.getString(if (item.audienceLabel == PrivateInvitationAudienceLabel.SENT_PRIVATELY) R.string.nearby_private_invitation_sent else R.string.nearby_private_invitation_received)
            title.text = item.title
            description.text = item.shortDescription
            description.visibility = if (item.shortDescription.isBlank()) View.GONE else View.VISIBLE
            time.text = formatChatMessageTime(item.createdAtMs)
            itemView.contentDescription = listOf(sender.text, audience.text, title.text, description.text).filter { it.isNotBlank() }.joinToString(". ")
            itemView.setOnClickListener { showInvitationActions(itemView, item.messageId, item.senderId, item.actions, callback) }
        }
    }

    class PrivateInvitationPlaceholderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val sender = view.findViewById<TextView>(R.id.private_invitation_placeholder_sender)
        private val text = view.findViewById<TextView>(R.id.private_invitation_placeholder_text)
        private val time = view.findViewById<TextView>(R.id.private_invitation_placeholder_time)
        fun bindHidden(item: NearbyRoomTimelineItem.HiddenPrivateInvitation, callback: ((String, String, PrivateInvitationAction) -> Unit)?) {
            bind(item.messageId, item.senderId, item.senderDisplayName, item.createdAtMs, item.actions, R.string.nearby_private_invitation_hidden, callback)
        }
        fun bindFailure(item: NearbyRoomTimelineItem.PrivateInvitationPlaceholder, callback: ((String, String, PrivateInvitationAction) -> Unit)?) {
            val label = when (item.kind) {
                PrivateInvitationPlaceholderKind.UNAVAILABLE -> R.string.nearby_private_invitation_unavailable
                PrivateInvitationPlaceholderKind.UNSUPPORTED -> R.string.nearby_private_invitation_unsupported
                PrivateInvitationPlaceholderKind.INVALID -> R.string.nearby_private_invitation_invalid
            }
            bind(item.messageId, item.senderId, item.senderDisplayName, item.createdAtMs, item.actions, label, callback)
        }
        private fun bind(messageId: String, senderId: String, senderName: String, createdAtMs: Long, actions: Set<PrivateInvitationAction>, label: Int, callback: ((String, String, PrivateInvitationAction) -> Unit)?) {
            sender.text = senderName
            text.setText(label)
            time.text = formatChatMessageTime(createdAtMs)
            itemView.contentDescription = "${sender.text}. ${text.text}"
            itemView.setOnClickListener { showInvitationActions(itemView, messageId, senderId, actions, callback) }
        }
    }

    companion object {
        private const val VIEW_NORMAL = 0
        private const val VIEW_INVITATION = 1
        private const val VIEW_PLACEHOLDER = 2
        private const val VIEW_PUBLIC_INVITATION = 3
        private const val VIEW_HIDDEN_PUBLIC_INVITATION = 4
        private const val ACTION_REPLY_ID = 1
        private const val ACTION_EDIT_ID = 2
        private const val ACTION_DELETE_ID = 3
        private const val ACTION_BLOCK_ID = 4
        private const val ACTION_REPORT_MESSAGE_ID = 5
        private const val ACTION_REPORT_PEER_ID = 6
        private const val ACTION_MUTE_ID = 7
    }
}

private fun showInvitationActions(view: View, messageId: String, senderId: String, actions: Set<PrivateInvitationAction>, callback: ((String, String, PrivateInvitationAction) -> Unit)?) {
    if (actions.isEmpty()) { view.setOnClickListener(null); return }
    val popup = android.widget.PopupMenu(view.context, view)
    actions.forEachIndexed { index, action ->
        val title = when (action) {
            PrivateInvitationAction.OPEN -> R.string.nearby_private_invitation_open
            PrivateInvitationAction.HIDE -> R.string.nearby_private_invitation_hide
            PrivateInvitationAction.UNHIDE -> R.string.nearby_private_invitation_unhide
            PrivateInvitationAction.REPORT_INVITATION -> R.string.moderation_report_message
            PrivateInvitationAction.REPORT_PEER -> R.string.moderation_report_peer
            PrivateInvitationAction.BLOCK_PEER -> R.string.moderation_block_peer
            PrivateInvitationAction.MUTE_PEER -> R.string.nearby_room_mute_peer
        }
        popup.menu.add(0, action.ordinal + 100, index, view.context.getString(title))
    }
    popup.setOnMenuItemClickListener { item ->
        val action = actions.firstOrNull { it.ordinal + 100 == item.itemId } ?: return@setOnMenuItemClickListener false
        callback?.invoke(messageId, senderId, action)
        true
    }
    popup.show()
}

private fun nearbyRoomDeliveryLabel(
    context: android.content.Context,
    deliveryState: String,
): String {
    return when (deliveryState) {
        NearbyRoomMessageEntity.DELIVERY_LOCAL -> context.getString(R.string.chat_delivery_sent)
        NearbyRoomMessageEntity.DELIVERY_SENT -> context.getString(R.string.chat_delivery_delivered)
        NearbyRoomMessageEntity.DELIVERY_RECEIVED -> "Received"
        else -> deliveryState
    }
}

private fun resolveNearbyThemeColor(view: View, attr: Int): Int {
    val typedArray = view.context.obtainStyledAttributes(intArrayOf(attr))
    return try {
        typedArray.getColor(0, 0)
    } finally {
        typedArray.recycle()
    }
}

private fun truncateNearbyReplyPreview(text: String, maxChars: Int = 15): String {
    val trimmed = text.trim()
    if (trimmed.length <= maxChars) return trimmed
    return trimmed.take(maxChars) + "..."
}
