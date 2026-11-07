package app.peermesh.nearby

object NearbyRoomRelayIdCodec {
    fun compactMessageId(fullMessageId: String): String {
        val canonical = fullMessageId.filter { it.isLetterOrDigit() }
        if (canonical.length <= COMPACT_MESSAGE_ID_HEX_CHARS) {
            return canonical.lowercase()
        }
        val head = canonical.take(COMPACT_HALF_HEX_CHARS)
        val tail = canonical.takeLast(COMPACT_HALF_HEX_CHARS)
        return (head + tail).lowercase()
    }

    private const val COMPACT_HALF_HEX_CHARS = 12
    private const val COMPACT_MESSAGE_ID_HEX_CHARS = COMPACT_HALF_HEX_CHARS * 2
}
