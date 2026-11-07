package app.peermesh.core.id

import java.security.MessageDigest

object RelayEventIdFactory {
    fun fromEventId(eventId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(eventId.toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        val bytes = hash.copyOfRange(0, 16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return toUuid(bytes)
    }

    private fun toUuid(bytes: ByteArray): String {
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return listOf(
            hex.substring(0, 8),
            hex.substring(8, 12),
            hex.substring(12, 16),
            hex.substring(16, 20),
            hex.substring(20, 32)
        ).joinToString("-")
    }
}
