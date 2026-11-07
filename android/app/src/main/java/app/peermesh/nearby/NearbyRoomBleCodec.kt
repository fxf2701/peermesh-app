package app.peermesh.nearby

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.random.Random

object NearbyRoomBleCodec {
    const val PROTOCOL_VERSION = 1
    private const val ROOM_ID_BYTES = 6
    private const val MAX_NAME_BYTES = NearbyRoomPolicy.MAX_ADVERTISEMENT_ROOM_NAME_BYTES
    const val MAX_PAYLOAD_BYTES = 17
    private const val MIN_PAYLOAD_BYTES = 3 + ROOM_ID_BYTES

    fun newCompactRoomId(): String {
        val bytes = ByteArray(ROOM_ID_BYTES)
        Random.Default.nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun encode(
        advertisement: NearbyRoomBleAdvertisement,
        nowMs: Long = System.currentTimeMillis(),
    ): ByteArray {
        val roomIdBytes = decodeHexRoomId(advertisement.roomId) ?: return ByteArray(0)
        val remainingMs = (advertisement.expiresAtMs - nowMs).coerceAtLeast(0L)
        val ttlRemainingMinutes = when {
            remainingMs <= 0L -> 0
            else -> ((remainingMs + 59_999L) / 60_000L)
                .coerceIn(1L, 0xFFL)
                .toInt()
        }
        // Keep BLE discovery payload within the advertisement budget.
        val nameBytes = ByteArray(0)
        val payload = ByteBuffer.allocate(MIN_PAYLOAD_BYTES + nameBytes.size)
            .put(advertisement.protocolVersion.toByte())
            .put(roomIdBytes)
            .put(ttlRemainingMinutes.toByte())
            .put(nameBytes.size.toByte())
            .put(nameBytes)
            .array()
        return if (payload.size <= MAX_PAYLOAD_BYTES) payload else ByteArray(0)
    }

    fun decode(
        bytes: ByteArray,
        ownerDeviceId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): NearbyRoomBleAdvertisement? {
        if (bytes.size !in MIN_PAYLOAD_BYTES..MAX_PAYLOAD_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes)
        val protocolVersion = buffer.get().toInt() and 0xFF
        if (protocolVersion != PROTOCOL_VERSION) return null
        val roomIdBytes = ByteArray(ROOM_ID_BYTES)
        buffer.get(roomIdBytes)
        val ttlRemainingMinutes = buffer.get().toInt() and 0xFF
        val nameLength = buffer.get().toInt() and 0xFF
        if (nameLength > MAX_NAME_BYTES || nameLength > buffer.remaining()) return null
        val nameBytes = ByteArray(nameLength)
        buffer.get(nameBytes)
        val roomName = decodeUtf8Strict(nameBytes) ?: return null
        val expiresAtMs = nowMs + ttlRemainingMinutes * 60_000L
        return NearbyRoomBleAdvertisement(
            roomId = roomIdBytes.joinToString("") { byte -> "%02x".format(byte) },
            roomName = roomName,
            ownerDeviceId = ownerDeviceId,
            // Advertisement only carries coarse TTL minutes, so it must not be treated as an
            // authoritative source for created_at_ms.
            createdAtMs = 0L,
            expiresAtMs = expiresAtMs,
            participantCount = null,
            protocolVersion = protocolVersion,
        )
    }

    private fun decodeHexRoomId(roomId: String): ByteArray? {
        val normalized = roomId.trim().lowercase()
        if (normalized.length != ROOM_ID_BYTES * 2) return null
        if (!normalized.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return normalized.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    }
}
