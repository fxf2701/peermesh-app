package app.peermesh

object LocalUserProfileValidator {
    const val MIN_DISPLAY_NAME_LENGTH = 2
    const val MAX_DISPLAY_NAME_LENGTH = 32

    fun sanitizeDisplayName(value: String): String {
        return value.trim().replace(Regex("\\s+"), " ")
    }

    fun isValidDisplayName(value: String): Boolean {
        val sanitized = sanitizeDisplayName(value)
        return sanitized.length in MIN_DISPLAY_NAME_LENGTH..MAX_DISPLAY_NAME_LENGTH
    }

    fun validateDisplayName(value: String): String? {
        val sanitized = sanitizeDisplayName(value)
        return when {
            sanitized.isBlank() -> "required"
            sanitized.length < MIN_DISPLAY_NAME_LENGTH -> "too_short"
            sanitized.length > MAX_DISPLAY_NAME_LENGTH -> "too_long"
            else -> null
        }
    }
}
