package app.peermesh

import android.content.Context
import kotlin.math.absoluteValue

object AvatarUtils {
    const val AVATAR_CAT = "pet_cat"
    const val AVATAR_DOG = "pet_dog"
    const val AVATAR_FOX = "pet_fox"
    const val AVATAR_BEAR = "pet_bear"

    data class AvatarOption(
        val id: String,
        val emoji: String,
        val label: String,
    )

    data class AvatarGroup(
        val title: String,
        val avatars: List<AvatarOption>,
    )

    fun loadAvatarGroups(context: Context): List<AvatarGroup> {
        val groupTitles = context.resources.getStringArray(R.array.avatar_group_titles)
        val idArrays = context.resources.obtainTypedArray(R.array.avatar_group_id_arrays)
        val emojiArrays = context.resources.obtainTypedArray(R.array.avatar_group_emoji_arrays)
        val labelArrays = context.resources.obtainTypedArray(R.array.avatar_group_label_arrays)

        return try {
            groupTitles.mapIndexedNotNull { index, title ->
                val idsResId = idArrays.getResourceId(index, 0)
                val emojisResId = emojiArrays.getResourceId(index, 0)
                val labelsResId = labelArrays.getResourceId(index, 0)
                if (idsResId == 0 || emojisResId == 0 || labelsResId == 0) return@mapIndexedNotNull null

                val ids = context.resources.getStringArray(idsResId)
                val emojis = context.resources.getStringArray(emojisResId)
                val labels = context.resources.getStringArray(labelsResId)
                val size = minOf(ids.size, emojis.size, labels.size)
                AvatarGroup(
                    title = title,
                    avatars = (0 until size).map { itemIndex ->
                        AvatarOption(
                            id = ids[itemIndex],
                            emoji = emojis[itemIndex],
                            label = labels[itemIndex],
                        )
                    }
                )
            }
        } finally {
            idArrays.recycle()
            emojiArrays.recycle()
            labelArrays.recycle()
        }
    }

    fun loadAvatarIds(context: Context): List<String> {
        return loadAvatarGroups(context).flatMap { group -> group.avatars.map { it.id } }
    }

    fun avatarInitials(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "?"
        val parts = trimmed.split(" ").filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> {
                (parts[0].take(1) + parts[1].take(1)).uppercase()
            }
            trimmed.length >= 2 -> trimmed.take(2).uppercase()
            else -> trimmed.take(1).uppercase()
        }
    }

    fun avatarEmojiForId(context: Context, avatarId: String?): String {
        return loadAvatarGroups(context)
            .asSequence()
            .flatMap { it.avatars.asSequence() }
            .firstOrNull { it.id == avatarId }
            ?.emoji
            ?: "🐱"
    }

    fun avatarColorForKey(key: String): Int {
        val palette = intArrayOf(
            0xFF22D3EE.toInt(),
            0xFF06B6D4.toInt(),
            0xFF0E7490.toInt(),
            0xFF22C55E.toInt(),
            0xFF64748B.toInt(),
            0xFF1F2937.toInt()
        )
        val idx = (key.hashCode().absoluteValue) % palette.size
        return palette[idx]
    }

    fun avatarTextColor(bg: Int): Int {
        val r = (bg shr 16 and 0xFF) / 255.0
        val g = (bg shr 8 and 0xFF) / 255.0
        val b = (bg and 0xFF) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luminance > 0.6) 0xFF111827.toInt() else 0xFFFFFFFF.toInt()
    }
}
