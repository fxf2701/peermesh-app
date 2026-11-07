package app.peermesh.nearby

import android.content.Context

object BleModeController {
    private const val DISCOVERY_BURST_WINDOW_MS = 10_000L
    private const val DISCOVERY_FAST_REFRESH_MS = 3_000L
    private const val DISCOVERY_STEADY_REFRESH_MS = 15_000L
    private const val ACTIVE_ROOM_BACKGROUND_DISCOVERY_REFRESH_MS = 30_000L

    sealed class Mode {
        data object Idle : Mode()
        data object Discovery : Mode()
        data class ActiveRoom(val roomId: String) : Mode()
    }

    fun currentMode(context: Context): Mode {
        val session = ActiveNearbyRoomSessionManager.get(context)
        return when {
            !session.activeRoomId.isNullOrBlank() -> Mode.ActiveRoom(session.activeRoomId)
            session.discoveryVisible -> Mode.Discovery
            else -> Mode.Idle
        }
    }

    fun shouldProcessDiscoveryMetadata(context: Context): Boolean {
        return when (currentMode(context)) {
            Mode.Idle -> false
            Mode.Discovery -> true
            is Mode.ActiveRoom -> true
        }
    }

    fun shouldProcessActiveRoomTransport(context: Context, roomId: String): Boolean {
        val mode = currentMode(context)
        return mode is Mode.ActiveRoom && mode.roomId == roomId
    }

    fun discoveryRefreshIntervalMs(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Long? {
        val session = ActiveNearbyRoomSessionManager.get(context)
        return when (currentMode(context)) {
            Mode.Idle -> null
            Mode.Discovery -> {
                val enteredAt = session.lastDiscoveryEnteredAtMs ?: nowMs
                if (nowMs - enteredAt <= DISCOVERY_BURST_WINDOW_MS) {
                    DISCOVERY_FAST_REFRESH_MS
                } else {
                    DISCOVERY_STEADY_REFRESH_MS
                }
            }
            is Mode.ActiveRoom -> ACTIVE_ROOM_BACKGROUND_DISCOVERY_REFRESH_MS
        }
    }
}
