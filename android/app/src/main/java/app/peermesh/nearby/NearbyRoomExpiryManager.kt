package app.peermesh.nearby

import app.peermesh.data.db.entity.NearbyRoomEntity

class NearbyRoomExpiryManager(
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    fun remainingMs(room: NearbyRoomEntity, nowMs: Long = nowMsProvider()): Long {
        return (room.expires_at_ms - nowMs).coerceAtLeast(0L)
    }

    fun isExpired(room: NearbyRoomEntity, nowMs: Long = nowMsProvider()): Boolean {
        return room.state == NearbyRoomEntity.STATE_EXPIRED ||
            room.state == NearbyRoomEntity.STATE_CLOSED ||
            remainingMs(room, nowMs) <= 0L
    }

    fun formatRemaining(room: NearbyRoomEntity, nowMs: Long = nowMsProvider()): String {
        val remaining = remainingMs(room, nowMs)
        val totalSeconds = remaining / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }
}
