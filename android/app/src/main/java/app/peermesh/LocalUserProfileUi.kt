package app.peermesh

import android.graphics.drawable.GradientDrawable
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

object LocalUserProfileUi {
    const val FRIENDLY_FALLBACK_NAME = "Nearby User"

    fun resolveDisplayName(
        rawDisplayName: String?,
        isLocalUser: Boolean = false,
        localProfile: LocalUserProfile? = null,
    ): String {
        if (isLocalUser) {
            val localName = localProfile?.displayName?.let(LocalUserProfileValidator::sanitizeDisplayName)
            if (!localName.isNullOrBlank()) return localName
        }
        val sanitized = rawDisplayName?.let(LocalUserProfileValidator::sanitizeDisplayName)
        return sanitized?.takeIf { it.isNotBlank() } ?: FRIENDLY_FALLBACK_NAME
    }

    fun bindAvatarPreview(
        activity: AppCompatActivity,
        avatarId: String?,
        displayName: String,
        emojiView: TextView,
        labelView: TextView? = null,
    ) {
        val hasAvatar = !avatarId.isNullOrBlank()
        emojiView.text = if (hasAvatar) {
            AvatarUtils.avatarEmojiForId(activity, avatarId)
        } else {
            AvatarUtils.avatarInitials(displayName)
        }
        labelView?.text = if (hasAvatar) {
            avatarLabel(activity, avatarId)
        } else {
            activity.getString(R.string.profile_choose_avatar)
        }
    }

    fun showAvatarPicker(
        activity: AppCompatActivity,
        selected: String?,
        onSelected: (String) -> Unit,
    ) {
        lateinit var dialog: AlertDialog
        val density = activity.resources.displayMetrics.density
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), 0)
        }

        AvatarUtils.loadAvatarGroups(activity).forEach { group ->
            container.addView(
                TextView(activity).apply {
                    text = group.title
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(resolveThemeColor(activity, com.google.android.material.R.attr.colorOnSurface))
                    setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
                }
            )

            val grid = GridLayout(activity).apply {
                columnCount = 6
                useDefaultMargins = false
            }
            group.avatars.forEach { avatar ->
                val item = TextView(activity).apply {
                    text = avatar.emoji
                    textSize = 24f
                    gravity = android.view.Gravity.CENTER
                    contentDescription = avatar.label
                    setTextColor(resolveThemeColor(activity, com.google.android.material.R.attr.colorOnSurface))
                    setAvatarSelected(this, avatar.id == selected)
                    setOnClickListener {
                        onSelected(avatar.id)
                        dialog.dismiss()
                    }
                }
                val size = (48 * density).toInt()
                val margin = (4 * density).toInt()
                val params = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(margin, margin, margin, margin)
                }
                grid.addView(item, params)
            }
            container.addView(grid)
        }

        val scroll = ScrollView(activity).apply { addView(container) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.avatar_picker_title))
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun avatarLabel(activity: AppCompatActivity, avatarId: String?): String {
        return AvatarUtils.loadAvatarGroups(activity)
            .asSequence()
            .flatMap { it.avatars.asSequence() }
            .firstOrNull { it.id == avatarId }
            ?.label
            ?: activity.getString(R.string.profile_choose_avatar)
    }

    private fun setAvatarSelected(view: TextView, selected: Boolean) {
        val density = view.resources.displayMetrics.density
        val activity = view.context as AppCompatActivity
        val surface = resolveThemeColor(activity, com.google.android.material.R.attr.colorSurface)
        val border = resolveThemeColor(activity, R.attr.peerMeshMenuDivider)
        val accent = resolveThemeColor(activity, com.google.android.material.R.attr.colorPrimary)
        view.alpha = if (selected) 1.0f else 0.72f
        view.background = GradientDrawable().apply {
            cornerRadius = 12f * density
            setColor(surface)
            setStroke((1f * density).toInt().coerceAtLeast(1), if (selected) accent else border)
        }
    }

    private fun resolveThemeColor(activity: AppCompatActivity, attr: Int): Int {
        val typedArray = activity.obtainStyledAttributes(intArrayOf(attr))
        return try {
            typedArray.getColor(0, 0)
        } finally {
            typedArray.recycle()
        }
    }
}
