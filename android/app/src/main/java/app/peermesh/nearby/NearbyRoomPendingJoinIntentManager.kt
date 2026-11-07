package app.peermesh.nearby

import android.content.Context
import android.content.Intent
import app.peermesh.NearbyRoomActivity
import app.peermesh.PeerMeshPrefs
import app.peermesh.data.debug.ActivityLog

object NearbyRoomPendingJoinIntentManager {
    private const val KEY_PENDING_ROOM_ID = "nearby_pending_join_room_id"
    private const val KEY_PENDING_SET_AT_MS = "nearby_pending_join_set_at_ms"
    private const val KEY_PENDING_SOURCE = "nearby_pending_join_source"

    data class PendingJoinIntent(
        val roomId: String,
        val setAtMs: Long,
        val source: String,
    )

    fun get(context: Context): PendingJoinIntent? {
        val prefs = prefs(context)
        val roomId = prefs.getString(KEY_PENDING_ROOM_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val setAtMs = prefs.getLong(KEY_PENDING_SET_AT_MS, 0L).takeIf { it > 0L } ?: return null
        val source = prefs.getString(KEY_PENDING_SOURCE, null)?.takeIf { it.isNotBlank() } ?: "unknown"
        return PendingJoinIntent(
            roomId = roomId,
            setAtMs = setAtMs,
            source = source,
        )
    }

    fun recordManualJoinIntent(
        context: Context,
        roomId: String,
        source: String = "manual_open",
        nowMs: Long = System.currentTimeMillis(),
    ) {
        prefs(context).edit()
            .putString(KEY_PENDING_ROOM_ID, roomId)
            .putLong(KEY_PENDING_SET_AT_MS, nowMs)
            .putString(KEY_PENDING_SOURCE, source)
            .apply()
        ActivityLog.add(
            label = "Nearby room pending join intent",
            source = "nearby",
            message = "action=record room_id=$roomId source=$source set_at_ms=$nowMs",
        )
    }

    fun clear(
        context: Context,
        roomId: String? = null,
        reason: String,
    ) {
        val existing = get(context) ?: return
        if (roomId != null && existing.roomId != roomId) return
        prefs(context).edit()
            .remove(KEY_PENDING_ROOM_ID)
            .remove(KEY_PENDING_SET_AT_MS)
            .remove(KEY_PENDING_SOURCE)
            .apply()
        ActivityLog.add(
            label = "Nearby room pending join intent",
            source = "nearby",
            message = "action=clear room_id=${existing.roomId} source=${existing.source} reason=$reason",
        )
    }

    fun maybeAutoOpenFromLateKey(
        context: Context,
        roomId: String,
        trigger: String,
    ): Boolean {
        val pending = get(context) ?: return false
        if (pending.roomId != roomId) return false
        val session = ActiveNearbyRoomSessionManager.get(context)
        if (session.activeRoomId != null) {
            ActivityLog.add(
                label = "Nearby room auto-open pending join",
                source = "nearby",
                message = "room_id=$roomId trigger=$trigger action=skip reason=active_room_open active_room_id=${session.activeRoomId}",
            )
            return false
        }
        if (!session.discoveryVisible) {
            ActivityLog.add(
                label = "Nearby room auto-open pending join",
                source = "nearby",
                message = "room_id=$roomId trigger=$trigger action=skip reason=discovery_not_visible",
            )
            return false
        }
        val appContext = context.applicationContext
        ActivityLog.add(
            label = "Nearby room auto-open pending join",
            source = "nearby",
            message = "room_id=$roomId trigger=$trigger action=open source=${pending.source}",
        )
        val attemptId = NearbyForegroundRuntimeCoordinator.beginJoining(
            context = appContext,
            roomId = roomId,
            reason = "late_key_auto_open",
        )
        appContext.startActivity(
            Intent(appContext, NearbyRoomActivity::class.java).apply {
                putExtra(NearbyRoomActivity.EXTRA_ROOM_ID, roomId)
                putExtra(NearbyRoomActivity.EXTRA_AUTO_RESUMED_JOIN, true)
                putExtra(NearbyRoomActivity.EXTRA_JOIN_ATTEMPT_ID, attemptId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PeerMeshPrefs.NAME, Context.MODE_PRIVATE)
}
