package app.peermesh.nearby

data class ActiveNearbyRoomSession(
    val activeRoomId: String?,
    val discoveryVisible: Boolean,
    val lastDiscoveryEnteredAtMs: Long?,
    val lastRoomEnteredAtMs: Long?,
    val lastRoomLeftAtMs: Long?,
) {
    companion object {
        val EMPTY = ActiveNearbyRoomSession(
            activeRoomId = null,
            discoveryVisible = false,
            lastDiscoveryEnteredAtMs = null,
            lastRoomEnteredAtMs = null,
            lastRoomLeftAtMs = null,
        )
    }
}
