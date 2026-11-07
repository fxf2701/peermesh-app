package app.peermesh.nearby

import kotlinx.serialization.Serializable

@Serializable
data class NearbyRoomTransportEnvelope(
    val kind: String,
    val room_id: String,
    val sender_device_id: String,
    val owner_device_id: String? = null,
    val sender_display_name: String? = null,
    val avatar_id: String? = null,
    val profile_updated_at_ms: Long? = null,
    val recipient_encryption_supported: Boolean? = null,
    val recipient_encryption_algorithm: String? = null,
    val recipient_encryption_key_id: String? = null,
    val recipient_encryption_public_key: String? = null,
    val self_info_revision: String? = null,
    val origin_device_id: String? = null,
    val created_at_ms: Long,
    val expires_at_ms: Long,
    val edited_at_ms: Long? = null,
    val deleted_at_ms: Long? = null,
    val message_id: String? = null,
    val reply_to: String? = null,
    val message_compact_id: String? = null,
    val ciphertext: String? = null,
    val nonce: String? = null,
    val key_version: Int? = null,
    val room_key: String? = null,
    val target_device_id: String? = null,
    val room_name: String? = null,
    val participant_count: Int? = null,
    val closed_at_ms: Long? = null,
) {
    companion object {
        const val KIND_MESSAGE_V2 = "NEARBY_ROOM_MESSAGE_V2"
        const val KIND_KEY_BOOTSTRAP_V1 = "NEARBY_ROOM_KEY_BOOTSTRAP_V1"
        const val KIND_KEY_REQUEST_V1 = "NEARBY_ROOM_KEY_REQUEST_V1"
        const val KIND_METADATA_REQUEST_V1 = "NEARBY_ROOM_METADATA_REQUEST_V1"
        const val KIND_METADATA_RESPONSE_V1 = "NEARBY_ROOM_METADATA_RESPONSE_V1"
        const val KIND_ROOM_CLOSED_V1 = "NEARBY_ROOM_CLOSED_V1"
        const val KIND_OWNER_MUTE_V1 = "NEARBY_ROOM_OWNER_MUTE_V1"
        const val KIND_OWNER_UNMUTE_V1 = "NEARBY_ROOM_OWNER_UNMUTE_V1"
        const val KIND_MESSAGE_OFFER_V1 = "NEARBY_ROOM_MESSAGE_OFFER_V1"
        const val KIND_MESSAGE_FETCH_V1 = "NEARBY_ROOM_MESSAGE_FETCH_V1"
        const val KIND_MESSAGE_DATA_V1 = "NEARBY_ROOM_MESSAGE_DATA_V1"
        const val KIND_JOIN_READY_V1 = "NEARBY_ROOM_JOIN_READY_V1"
        const val KIND_SELF_INFO_V1 = "NEARBY_ROOM_SELF_INFO_V1"
        const val KIND_SELF_INFO_ACK_V1 = "NEARBY_ROOM_SELF_INFO_ACK_V1"

        const val KIND_SHORT_MESSAGE_V2 = "M2"
        const val KIND_SHORT_KEY_BOOTSTRAP_V1 = "KB1"
        const val KIND_SHORT_KEY_REQUEST_V1 = "KR1"
        const val KIND_SHORT_METADATA_REQUEST_V1 = "MR1"
        const val KIND_SHORT_METADATA_RESPONSE_V1 = "MS1"
        const val KIND_SHORT_ROOM_CLOSED_V1 = "RC1"
        const val KIND_SHORT_OWNER_MUTE_V1 = "UM1"
        const val KIND_SHORT_OWNER_UNMUTE_V1 = "UU1"
        const val KIND_SHORT_MESSAGE_OFFER_V1 = "MO1"
        const val KIND_SHORT_MESSAGE_FETCH_V1 = "MF1"
        const val KIND_SHORT_MESSAGE_DATA_V1 = "MD1"
        const val KIND_SHORT_JOIN_READY_V1 = "JR1"
        const val KIND_SHORT_SELF_INFO_V1 = "SI1"
        const val KIND_SHORT_SELF_INFO_ACK_V1 = "SA1"
    }
}
