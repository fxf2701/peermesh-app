package app.peermesh.nearby

import android.content.Context
import android.util.Base64
import app.peermesh.PeerMeshPrefs
import app.peermesh.data.db.entity.NearbyRoomEntity

object NearbyRoomBleStateCache {
    private const val KEY_ACTIVE_ROOM_PAYLOAD = "nearby_active_room_ble_payload"
    private const val KEY_ACTIVE_ROOM_PAYLOAD_ROOM_ID = "nearby_active_room_ble_payload_room_id"
    @Volatile private var cachedPayload: ByteArray? = null
    @Volatile private var cachedRoomId: String? = null

    data class Snapshot(
        val roomId: String?,
        val payload: ByteArray?,
    )

    fun updateFromRoom(context: Context, room: NearbyRoomEntity): ByteArray? {
        val payload = NearbyRoomBleCodec.encode(
            NearbyRoomBleAdvertisement(
                roomId = room.room_id,
                roomName = room.name,
                ownerDeviceId = room.owner_device_id,
                createdAtMs = room.created_at_ms,
                expiresAtMs = room.expires_at_ms,
                participantCount = room.participant_count,
                protocolVersion = NearbyRoomBleCodec.PROTOCOL_VERSION,
            )
        ).takeIf { it.isNotEmpty() } ?: return clear(context)

        cachedRoomId = room.room_id
        cachedPayload = payload
        prefs(context).edit()
            .putString(KEY_ACTIVE_ROOM_PAYLOAD_ROOM_ID, room.room_id)
            .putString(KEY_ACTIVE_ROOM_PAYLOAD, Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        return payload
    }

    fun payloadForActiveSession(context: Context): ByteArray? {
        val session = ActiveNearbyRoomSessionManager.get(context.applicationContext)
        val activeRoomId = session.activeRoomId ?: return null
        val inMemoryRoomId = cachedRoomId
        val inMemoryPayload = cachedPayload
        if (inMemoryRoomId == activeRoomId && inMemoryPayload != null && inMemoryPayload.isNotEmpty()) {
            return inMemoryPayload.copyOf()
        }
        val prefs = prefs(context)
        val persistedRoomId = prefs.getString(KEY_ACTIVE_ROOM_PAYLOAD_ROOM_ID, null)
        if (persistedRoomId != activeRoomId) return null
        val encoded = prefs.getString(KEY_ACTIVE_ROOM_PAYLOAD, null) ?: return null
        val decoded = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        cachedRoomId = persistedRoomId
        cachedPayload = decoded
        return decoded.copyOf()
    }

    fun snapshot(context: Context): Snapshot {
        val session = ActiveNearbyRoomSessionManager.get(context.applicationContext)
        val activeRoomId = session.activeRoomId
        val payload = payloadForActiveSession(context)
        val roomId = if (activeRoomId != null && cachedRoomId == activeRoomId) activeRoomId else cachedRoomId
        return Snapshot(
            roomId = roomId,
            payload = payload,
        )
    }

    fun clear(context: Context): ByteArray? {
        cachedPayload = null
        cachedRoomId = null
        prefs(context).edit()
            .remove(KEY_ACTIVE_ROOM_PAYLOAD)
            .remove(KEY_ACTIVE_ROOM_PAYLOAD_ROOM_ID)
            .commit()
        return null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PeerMeshPrefs.NAME, Context.MODE_PRIVATE)
}
