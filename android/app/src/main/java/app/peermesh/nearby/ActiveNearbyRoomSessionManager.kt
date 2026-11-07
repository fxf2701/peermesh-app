package app.peermesh.nearby

import android.content.Context
import app.peermesh.PeerMeshPrefs
import java.util.concurrent.CopyOnWriteArraySet

object ActiveNearbyRoomSessionManager {
    private const val KEY_ACTIVE_ROOM_ID = "nearby_active_room_id"
    private const val KEY_DISCOVERY_VISIBLE = "nearby_discovery_visible"
    private const val KEY_DISCOVERY_ENTERED_AT_MS = "nearby_discovery_entered_at_ms"
    private const val KEY_ROOM_ENTERED_AT_MS = "nearby_room_entered_at_ms"
    private const val KEY_ROOM_LEFT_AT_MS = "nearby_room_left_at_ms"

    private val listeners = CopyOnWriteArraySet<(ActiveNearbyRoomSession) -> Unit>()

    fun get(context: Context): ActiveNearbyRoomSession {
        val prefs = prefs(context)
        return ActiveNearbyRoomSession(
            activeRoomId = prefs.getString(KEY_ACTIVE_ROOM_ID, null),
            discoveryVisible = prefs.getBoolean(KEY_DISCOVERY_VISIBLE, false),
            lastDiscoveryEnteredAtMs = prefs.getLong(KEY_DISCOVERY_ENTERED_AT_MS, 0L).takeIf { it > 0L },
            lastRoomEnteredAtMs = prefs.getLong(KEY_ROOM_ENTERED_AT_MS, 0L).takeIf { it > 0L },
            lastRoomLeftAtMs = prefs.getLong(KEY_ROOM_LEFT_AT_MS, 0L).takeIf { it > 0L },
        )
    }

    fun enterDiscovery(context: Context, nowMs: Long = System.currentTimeMillis()): ActiveNearbyRoomSession {
        return update(context) { current ->
            current.copy(
                activeRoomId = null,
                discoveryVisible = true,
                lastDiscoveryEnteredAtMs = nowMs,
                lastRoomLeftAtMs = if (current.activeRoomId != null) nowMs else current.lastRoomLeftAtMs,
            )
        }
    }

    fun leaveDiscovery(context: Context): ActiveNearbyRoomSession {
        return update(context) { current ->
            current.copy(discoveryVisible = false)
        }
    }

    fun enterRoom(
        context: Context,
        roomId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ActiveNearbyRoomSession {
        return update(context) { current ->
            current.copy(
                activeRoomId = roomId,
                discoveryVisible = false,
                lastRoomEnteredAtMs = nowMs,
            )
        }
    }

    fun leaveRoom(
        context: Context,
        roomId: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ActiveNearbyRoomSession {
        return update(context) { current ->
            if (roomId != null && current.activeRoomId != roomId) {
                current
            } else {
                current.copy(
                    activeRoomId = null,
                    lastRoomLeftAtMs = nowMs,
                )
            }
        }
    }

    fun clearForPrivateGroup(context: Context, nowMs: Long = System.currentTimeMillis()): ActiveNearbyRoomSession {
        return update(context) { current ->
            current.copy(
                activeRoomId = null,
                discoveryVisible = false,
                lastRoomLeftAtMs = nowMs,
            )
        }
    }

    fun addListener(listener: (ActiveNearbyRoomSession) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (ActiveNearbyRoomSession) -> Unit) {
        listeners.remove(listener)
    }

    private fun update(
        context: Context,
        transform: (ActiveNearbyRoomSession) -> ActiveNearbyRoomSession,
    ): ActiveNearbyRoomSession {
        val next = transform(get(context))
        prefs(context).edit()
            .putString(KEY_ACTIVE_ROOM_ID, next.activeRoomId)
            .putBoolean(KEY_DISCOVERY_VISIBLE, next.discoveryVisible)
            .putLong(KEY_DISCOVERY_ENTERED_AT_MS, next.lastDiscoveryEnteredAtMs ?: 0L)
            .putLong(KEY_ROOM_ENTERED_AT_MS, next.lastRoomEnteredAtMs ?: 0L)
            .putLong(KEY_ROOM_LEFT_AT_MS, next.lastRoomLeftAtMs ?: 0L)
            .apply()
        listeners.forEach { it(next) }
        return next
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PeerMeshPrefs.NAME, Context.MODE_PRIVATE)
}
