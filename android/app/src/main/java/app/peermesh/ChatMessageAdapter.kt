package app.peermesh

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.widget.TextView
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import app.peermesh.data.db.entity.ChatMessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement

class ChatMessageAdapter(
    private val onReplyRequested: ((ChatMessageEntity) -> Unit)? = null,
    private val onReplyPreviewClicked: ((String) -> Unit)? = null,
    private val onEditRequested: ((ChatMessageEntity) -> Unit)? = null,
    private val onDeleteRequested: ((ChatMessageEntity) -> Unit)? = null,
    private val onReportMessageRequested: ((ChatMessageEntity) -> Unit)? = null,
    private val onReportPeerRequested: ((ChatMessageEntity) -> Unit)? = null,
    private val onBlockPeerRequested: ((ChatMessageEntity) -> Unit)? = null,
    private val onActionMenuVisibilityChanged: ((Boolean) -> Unit)? = null,
) : RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder>() {
    private val items = mutableListOf<ChatMessageEntity>()
    private var messagesById: Map<String, ChatMessageEntity> = emptyMap()
    private var profiles: Map<String, ProfileUi> = emptyMap()
    private var currentActorId: String? = null
    private var highlightedMessageId: String? = null
    private var actionsEnabled: Boolean = true

    fun submitList(messages: List<ChatMessageEntity>) {
        items.clear()
        items.addAll(messages)
        messagesById = messages.associateBy { it.message_id }
        notifyDataSetChanged()
    }

    fun setProfiles(profileMap: Map<String, ProfileUi>) {
        profiles = profileMap
        notifyDataSetChanged()
    }

    fun setCurrentActorId(actorDid: String?) {
        currentActorId = actorDid
        notifyDataSetChanged()
    }

    fun setHighlightedMessageId(messageId: String?) {
        highlightedMessageId = messageId
        notifyDataSetChanged()
    }

    fun setActionsEnabled(enabled: Boolean) {
        if (actionsEnabled == enabled) return
        actionsEnabled = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val root = view.findViewById<View>(R.id.message_root)
        private val body = view.findViewById<TextView>(R.id.message_body)
        private val displayName = view.findViewById<TextView>(R.id.message_display_name)
        private val time = view.findViewById<TextView>(R.id.message_time)
        private val editedBadge = view.findViewById<TextView>(R.id.message_edited_badge)
        private val avatarContainer = view.findViewById<FrameLayout>(R.id.message_avatar_container)
        private val avatarText = view.findViewById<TextView>(R.id.message_avatar_text)
        private val avatarIcon = view.findViewById<ImageView>(R.id.message_avatar_icon)
        private val replyContainer = view.findViewById<View>(R.id.message_reply_container)
        private val replyActor = view.findViewById<TextView>(R.id.message_reply_actor)
        private val replyText = view.findViewById<TextView>(R.id.message_reply_text)
        private val receiptsRow = view.findViewById<View>(R.id.message_receipts)
        private val receiptLabel = view.findViewById<TextView>(R.id.message_receipt_label)
        private val receiptAvatar1 = view.findViewById<FrameLayout>(R.id.message_receipt_avatar_1)
        private val receiptText1 = view.findViewById<TextView>(R.id.message_receipt_text_1)
        private val receiptAvatar2 = view.findViewById<FrameLayout>(R.id.message_receipt_avatar_2)
        private val receiptText2 = view.findViewById<TextView>(R.id.message_receipt_text_2)
        private val receiptMore = view.findViewById<TextView>(R.id.message_receipt_more)

        fun bind(
            message: ChatMessageEntity,
            profile: ProfileUi?,
            currentActor: String?,
            profiles: Map<String, ProfileUi>,
            replySource: ChatMessageEntity?,
            onReplyPreviewClicked: ((String) -> Unit)?,
            highlighted: Boolean,
        ) {
            val isDeleted = message.status == app.peermesh.data.reducer.ReducerConstants.STATUS_DELETED
            body.text = if (isDeleted) itemView.context.getString(R.string.chat_message_deleted) else message.text
            body.setTextColor(
                ContextCompat.getColor(
                    itemView.context,
                    if (isDeleted) android.R.color.darker_gray else android.R.color.transparent
                )
            )
            if (!isDeleted) {
                body.setTextColor(resolveThemeColor(itemView, com.google.android.material.R.attr.colorOnSurface))
            }
            body.alpha = if (isDeleted) 0.8f else 1.0f
            body.setTypeface(null, if (isDeleted) Typeface.ITALIC else Typeface.NORMAL)
            root.background = ContextCompat.getDrawable(
                itemView.context,
                if (highlighted) R.drawable.bg_chat_message_highlight else R.drawable.bg_chat_message
            )

            val actorLabel = profile?.displayName ?: message.actor_did.take(12)
            val createdMs = toEpochMillis(message.created_t)
            displayName.text = actorLabel
            time.text = formatChatMessageTime(createdMs)
            editedBadge.visibility = if (!isDeleted && message.edited_at != null) View.VISIBLE else View.GONE

            val avatarId = profile?.avatarId
            val initials = AvatarUtils.avatarInitials(profile?.displayName ?: actorLabel)
            val color = AvatarUtils.avatarColorForKey(message.actor_did)
            avatarContainer.background.setTint(color)
            val textColor = AvatarUtils.avatarTextColor(color)
            avatarText.setTextColor(ColorStateList.valueOf(textColor))
            avatarIcon.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
            if (avatarId != null) {
                avatarText.text = AvatarUtils.avatarEmojiForId(itemView.context, avatarId)
                avatarText.visibility = View.VISIBLE
                avatarIcon.visibility = View.GONE
            } else {
                avatarText.text = initials
                avatarText.visibility = View.VISIBLE
                avatarIcon.visibility = View.GONE
            }

            bindReplyPreview(message, replySource, profiles, onReplyPreviewClicked)
            bindReceipts(message, currentActor, profiles)
        }

        private fun bindReplyPreview(
            message: ChatMessageEntity,
            replySource: ChatMessageEntity?,
            profiles: Map<String, ProfileUi>,
            onReplyPreviewClicked: ((String) -> Unit)?,
        ) {
            val replyTo = message.reply_to
            if (replyTo.isNullOrBlank()) {
                replyContainer.visibility = View.GONE
                replyContainer.setOnClickListener(null)
                return
            }
            val actor = replySource?.let { source ->
                profiles[source.actor_did]?.displayName ?: source.actor_did.take(12)
            } ?: itemView.context.getString(R.string.chat_reply_original_unavailable)
            val text = replySource?.text
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    if (replySource.status == app.peermesh.data.reducer.ReducerConstants.STATUS_DELETED) {
                        itemView.context.getString(R.string.chat_message_deleted)
                    } else {
                        truncateReplyPreview(it)
                    }
                }
                ?: itemView.context.getString(R.string.chat_reply_original_unavailable)
            replyActor.text = truncateReplyPreview(actor, maxChars = 25)
            replyText.text = truncateReplyPreview(text, maxChars = 25)
            val replyDeleted = replySource?.status == app.peermesh.data.reducer.ReducerConstants.STATUS_DELETED
            val secondaryColor = resolveThemeColor(itemView, android.R.attr.textColorSecondary)
            replyActor.setTextColor(secondaryColor)
            replyText.setTextColor(secondaryColor)
            replyActor.alpha = if (replyDeleted) 0.8f else 1.0f
            replyText.alpha = if (replyDeleted) 0.8f else 1.0f
            replyActor.setTypeface(null, Typeface.BOLD_ITALIC)
            replyText.setTypeface(null, Typeface.ITALIC)
            replyContainer.visibility = View.VISIBLE
            replyContainer.setOnClickListener {
                onReplyPreviewClicked?.invoke(replyTo)
            }
        }

        private fun bindReceipts(message: ChatMessageEntity, currentActor: String?, profiles: Map<String, ProfileUi>) {
            if (currentActor == null || message.actor_did != currentActor) {
                receiptsRow.visibility = View.GONE
                return
            }
            val seen = parseSeenBy(message.seen_by)
                .filterKeys { it != currentActor }
            if (seen.isEmpty()) {
                bindDeliveryStatus(message.delivery_status)
                return
            }
            val sorted = seen.entries.sortedByDescending { it.value }
            val topTwo = sorted.take(2).map { it.key }
            val total = seen.size
            receiptsRow.visibility = View.VISIBLE
            receiptLabel.text = itemView.context.getString(R.string.chat_receipt_seen_by)

            bindReceiptAvatar(topTwo.getOrNull(0), receiptAvatar1, receiptText1, profiles)
            bindReceiptAvatar(topTwo.getOrNull(1), receiptAvatar2, receiptText2, profiles)
            receiptAvatar2.visibility = if (topTwo.size >= 2) View.VISIBLE else View.GONE

            val remaining = total - topTwo.size
            if (remaining > 0) {
                receiptMore.text = itemView.context.getString(R.string.receipt_more_format, remaining)
                receiptMore.visibility = View.VISIBLE
            } else {
                receiptMore.visibility = View.GONE
            }
        }

        private fun bindDeliveryStatus(deliveryStatus: String?) {
            receiptsRow.visibility = View.VISIBLE
            receiptLabel.text = deliveryStatusLabel(itemView.context, deliveryStatus)
            receiptAvatar1.visibility = View.GONE
            receiptAvatar2.visibility = View.GONE
            receiptMore.visibility = View.GONE
        }

        private fun bindReceiptAvatar(
            actorDid: String?,
            container: FrameLayout,
            textView: TextView,
            profiles: Map<String, ProfileUi>,
        ) {
            if (actorDid == null) {
                container.visibility = View.GONE
                return
            }
            val profile = profiles[actorDid]
            val display = profile?.displayName ?: actorDid.take(12)
            val avatarId = profile?.avatarId
            val initials = AvatarUtils.avatarInitials(display)
            val color = AvatarUtils.avatarColorForKey(actorDid)
            container.background.setTint(color)
            val textColor = AvatarUtils.avatarTextColor(color)
            textView.setTextColor(ColorStateList.valueOf(textColor))
            textView.text = avatarId?.let { AvatarUtils.avatarEmojiForId(itemView.context, it) } ?: initials
            container.visibility = View.VISIBLE
        }
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = items[position]
        holder.itemView.setOnClickListener {
            if (!actionsEnabled) return@setOnClickListener
            showPrimaryActions(holder.itemView, message)
        }
        holder.itemView.setOnLongClickListener(null)
        holder.bind(
            message = message,
            profile = profiles[message.actor_did],
            currentActor = currentActorId,
            profiles = profiles,
            replySource = message.reply_to?.let { messagesById[it] },
            onReplyPreviewClicked = onReplyPreviewClicked,
            highlighted = message.message_id == highlightedMessageId,
        )
    }

    data class ProfileUi(
        val displayName: String?,
        val avatarId: String?,
    )

    private fun showPrimaryActions(anchor: View, message: ChatMessageEntity) {
        if (message.status == app.peermesh.data.reducer.ReducerConstants.STATUS_DELETED) return
        val popup = android.widget.PopupMenu(anchor.context, anchor)
        popup.menu.add(0, ACTION_REPLY_ID, 0, anchor.context.getString(R.string.chat_reply_action))
        val canEditOwn = currentActorId != null && message.actor_did == currentActorId
        val canModeratePeer = currentActorId != null && message.actor_did != currentActorId
        if (canEditOwn) {
            popup.menu.add(0, ACTION_EDIT_ID, 1, anchor.context.getString(R.string.chat_edit_action))
            popup.menu.add(0, ACTION_DELETE_ID, 2, anchor.context.getString(R.string.chat_delete_action))
        }
        if (canModeratePeer) {
            val baseOrder = if (canEditOwn) 3 else 1
            popup.menu.add(0, ACTION_REPORT_MESSAGE_ID, baseOrder, anchor.context.getString(R.string.moderation_report_message))
            popup.menu.add(0, ACTION_REPORT_PEER_ID, baseOrder + 1, anchor.context.getString(R.string.moderation_report_peer))
            popup.menu.add(0, ACTION_BLOCK_PEER_ID, baseOrder + 2, anchor.context.getString(R.string.moderation_block_peer))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ACTION_REPLY_ID -> {
                    onReplyRequested?.invoke(message)
                    true
                }
                ACTION_EDIT_ID -> {
                    onEditRequested?.invoke(message)
                    true
                }
                ACTION_DELETE_ID -> {
                    onDeleteRequested?.invoke(message)
                    true
                }
                ACTION_REPORT_MESSAGE_ID -> {
                    onReportMessageRequested?.invoke(message)
                    true
                }
                ACTION_REPORT_PEER_ID -> {
                    onReportPeerRequested?.invoke(message)
                    true
                }
                ACTION_BLOCK_PEER_ID -> {
                    onBlockPeerRequested?.invoke(message)
                    true
                }
                else -> false
            }
        }
        popup.setOnDismissListener {
            onActionMenuVisibilityChanged?.invoke(false)
        }
        onActionMenuVisibilityChanged?.invoke(true)
        popup.show()
    }

    companion object {
        private const val ACTION_REPLY_ID = 1
        private const val ACTION_EDIT_ID = 2
        private const val ACTION_DELETE_ID = 3
        private const val ACTION_REPORT_MESSAGE_ID = 4
        private const val ACTION_REPORT_PEER_ID = 5
        private const val ACTION_BLOCK_PEER_ID = 6
    }

}

private fun deliveryStatusLabel(context: android.content.Context, value: String?): String {
    return when (value) {
        app.peermesh.data.reducer.DeliveryStatus.DELIVERED -> context.getString(R.string.chat_delivery_delivered)
        app.peermesh.data.reducer.DeliveryStatus.SEEN -> context.getString(R.string.chat_delivery_seen)
        else -> context.getString(R.string.chat_delivery_sent)
    }
}

private fun parseSeenBy(raw: String?): Map<String, Long> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        val element = Json.decodeFromString(JsonElement.serializer(), raw)
        val obj = element as? JsonObject ?: return emptyMap()
        obj.mapNotNull { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            val ts = if (primitive.isString) primitive.content.toLongOrNull() else primitive.toString().toLongOrNull()
            ts?.let { key to it }
        }.toMap()
    } catch (_: Exception) {
        emptyMap()
    }
}


private fun toEpochMillis(value: Long): Long {
    return if (value < 10_000_000_000L) value * 1000L else value
}

private fun resolveThemeColor(view: View, attr: Int): Int {
    val typedArray = view.context.obtainStyledAttributes(intArrayOf(attr))
    return try {
        typedArray.getColor(0, 0)
    } finally {
        typedArray.recycle()
    }
}

private fun truncateReplyPreview(text: String, maxChars: Int = 15): String {
    val trimmed = text.trim()
    if (trimmed.length <= maxChars) return trimmed
    return trimmed.take(maxChars) + "..."
}
