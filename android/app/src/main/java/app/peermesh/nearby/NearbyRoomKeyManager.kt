package app.peermesh.nearby

import android.content.Context
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.security.crypto.SecureKeyStore
import java.security.SecureRandom

class NearbyRoomKeyManager(
    context: Context,
    private val keyStore: SecureKeyStore = SecureKeyStore(context.applicationContext),
) {
    fun ensureOwnerKey(room: NearbyRoomEntity): ByteArray? {
        if (!room.is_local_owner) return null
        load(room.room_id)?.let { return it }
        val generated = ByteArray(KEY_SIZE_BYTES)
        SecureRandom().nextBytes(generated)
        keyStore.storeNearbyRoomKey(room.room_id, generated)
        return generated
    }

    fun store(roomId: String, key: ByteArray) {
        keyStore.storeNearbyRoomKey(roomId, key)
    }

    fun load(roomId: String): ByteArray? = keyStore.loadNearbyRoomKey(roomId)

    fun clear(roomId: String) {
        keyStore.clearNearbyRoomKey(roomId)
    }

    companion object {
        private const val KEY_SIZE_BYTES = 32
    }
}
