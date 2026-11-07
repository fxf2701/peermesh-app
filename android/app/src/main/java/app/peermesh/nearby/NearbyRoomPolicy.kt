package app.peermesh.nearby

object NearbyRoomPolicy {
    const val DISCOVERY_NAME_PLACEHOLDER = "Nearby room"
    const val ROOM_TTL_MS: Long = 30 * 60 * 1000L
    const val MAX_ROOM_NAME_LENGTH = 50
    const val MAX_ROOM_NAME_BYTES = 100
    const val MAX_ADVERTISEMENT_ROOM_NAME_BYTES = 7
    const val MAX_MESSAGE_LENGTH = 500

    fun sanitizeRoomName(raw: String): String {
        return truncateUtf8ToByteLimit(
            text = raw.trim().take(MAX_ROOM_NAME_LENGTH),
            maxBytes = MAX_ROOM_NAME_BYTES,
        )
    }

    fun isValidRoomName(raw: String): Boolean {
        return sanitizeRoomName(raw).isNotBlank()
    }

    fun sanitizeAdvertisementRoomName(raw: String): String {
        return truncateUtf8ToByteLimit(
            text = raw.trim(),
            maxBytes = MAX_ADVERTISEMENT_ROOM_NAME_BYTES,
        )
    }

    fun temporaryDiscoveryDisplayName(roomId: String): String {
        val suffix = roomId.takeLast(4).uppercase()
        return "$DISCOVERY_NAME_PLACEHOLDER #$suffix"
    }

    fun sanitizeMessageBody(raw: String): String {
        return raw.trim().take(MAX_MESSAGE_LENGTH)
    }

    fun isValidMessageBody(raw: String): Boolean {
        return sanitizeMessageBody(raw).isNotBlank()
    }

    fun truncateUtf8ToByteLimit(text: String, maxBytes: Int): String {
        if (text.isEmpty() || maxBytes <= 0) return ""
        val builder = StringBuilder()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val candidate = builder.toString() + String(Character.toChars(codePoint))
            val candidateBytes = candidate.encodeToByteArray()
            if (candidateBytes.size > maxBytes) break
            builder.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }
}
