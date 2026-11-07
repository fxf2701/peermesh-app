package app.peermesh.nearby

import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import java.util.concurrent.ConcurrentHashMap

object NearbyRoomHiddenMessageRelayCache {
    private val messagesByKey = ConcurrentHashMap<String, NearbyRoomMessageEntity>()

    fun store(message: NearbyRoomMessageEntity, nowMs: Long = System.currentTimeMillis()) {
        pruneExpired(nowMs)
        messagesByKey[key(message.room_id, message.message_id)] = message
    }

    fun findByCompactId(
        roomId: String,
        compactMessageId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): NearbyRoomMessageEntity? {
        pruneExpired(nowMs)
        return messagesByKey.values.firstOrNull { message ->
            message.room_id == roomId &&
                NearbyRoomRelayIdCodec.compactMessageId(message.message_id) == compactMessageId
        }
    }

    private fun pruneExpired(nowMs: Long) {
        messagesByKey.entries.removeIf { nowMs >= it.value.ttl_expires_at_ms }
    }

    private fun key(roomId: String, messageId: String): String = "$roomId|$messageId"
}
