package app.peermesh

import android.content.Context
import android.content.SharedPreferences

class LocalUserProfileStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun load(): LocalUserProfile? {
        val displayName = LocalUserProfileValidator.sanitizeDisplayName(
            prefs.getString(KEY_DISPLAY_NAME, null).orEmpty()
        )
        val avatarId = prefs.getString(KEY_AVATAR_ID, null).orEmpty().trim()
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        if (!LocalUserProfileValidator.isValidDisplayName(displayName) || avatarId.isBlank()) return null
        return LocalUserProfile(
            displayName = displayName,
            avatarId = avatarId,
            updatedAt = updatedAt,
        )
    }

    fun save(
        displayName: String,
        avatarId: String,
        updatedAt: Long = System.currentTimeMillis(),
    ): LocalUserProfile {
        val sanitizedDisplayName = LocalUserProfileValidator.sanitizeDisplayName(displayName)
        require(LocalUserProfileValidator.isValidDisplayName(sanitizedDisplayName)) { "Display name is invalid" }
        require(avatarId.isNotBlank()) { "Avatar is required" }
        val profile = LocalUserProfile(
            displayName = sanitizedDisplayName,
            avatarId = avatarId.trim(),
            updatedAt = updatedAt,
        )
        prefs.edit()
            .putString(KEY_DISPLAY_NAME, profile.displayName)
            .putString(KEY_AVATAR_ID, profile.avatarId)
            .putLong(KEY_UPDATED_AT, profile.updatedAt)
            .apply()
        return profile
    }

    companion object {
        private const val PREFS_NAME = PeerMeshPrefs.NAME
        private const val KEY_DISPLAY_NAME = "local_user_profile_display_name"
        private const val KEY_AVATAR_ID = "local_user_profile_avatar_id"
        private const val KEY_UPDATED_AT = "local_user_profile_updated_at"
    }
}
