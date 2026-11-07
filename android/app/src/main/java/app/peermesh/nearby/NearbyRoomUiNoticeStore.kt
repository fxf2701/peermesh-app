package app.peermesh.nearby

import android.content.Context
import app.peermesh.PeerMeshPrefs

object NearbyRoomUiNoticeStore {
    private const val KEY_OWNER_CLOSED_ROOM_ID = "nearby_owner_closed_room_id"

    fun recordOwnerClosedNotice(context: Context, roomId: String) {
        prefs(context).edit().putString(KEY_OWNER_CLOSED_ROOM_ID, roomId).apply()
    }

    fun consumeOwnerClosedNotice(context: Context, roomId: String): Boolean {
        val prefs = prefs(context)
        val pendingRoomId = prefs.getString(KEY_OWNER_CLOSED_ROOM_ID, null)
        if (pendingRoomId != roomId) return false
        prefs.edit().remove(KEY_OWNER_CLOSED_ROOM_ID).apply()
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PeerMeshPrefs.NAME, Context.MODE_PRIVATE)
}
