package app.peermesh.nearby

import app.peermesh.core.json.CanonicalJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object NearbyRoomTransportCodec {
    fun encode(envelope: NearbyRoomTransportEnvelope): ByteArray {
        val payload = buildJsonObject {
            put(FIELD_KIND, shortKind(envelope.kind) ?: envelope.kind)
            put(FIELD_ROOM_ID, envelope.room_id)
            put(FIELD_SENDER_DEVICE_ID, envelope.sender_device_id)
            envelope.owner_device_id?.let { put(FIELD_OWNER_DEVICE_ID, it) }
            envelope.sender_display_name?.let { put(FIELD_SENDER_DISPLAY_NAME, it) }
            envelope.avatar_id?.let { put(FIELD_AVATAR_ID, it) }
            envelope.profile_updated_at_ms?.let { put(FIELD_PROFILE_UPDATED_AT_MS, it) }
            when (envelope.kind) {
                NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1 -> {
                    envelope.recipient_encryption_supported?.let { put(FIELD_RECIPIENT_ENCRYPTION_SUPPORTED, it) }
                    envelope.recipient_encryption_algorithm?.let { put(FIELD_RECIPIENT_ENCRYPTION_ALGORITHM, it) }
                    envelope.recipient_encryption_key_id?.let { put(FIELD_RECIPIENT_ENCRYPTION_KEY_ID, it) }
                    envelope.recipient_encryption_public_key?.let { put(FIELD_RECIPIENT_ENCRYPTION_PUBLIC_KEY, it) }
                    envelope.self_info_revision?.let { put(FIELD_SELF_INFO_REVISION, it) }
                }
                NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1 -> {
                    envelope.self_info_revision?.let { put(FIELD_SELF_INFO_REVISION, it) }
                }
            }
            envelope.origin_device_id?.let { put(FIELD_ORIGIN_DEVICE_ID, it) }
            put(FIELD_CREATED_AT_MS, envelope.created_at_ms)
            put(FIELD_EXPIRES_AT_MS, envelope.expires_at_ms)
            envelope.edited_at_ms?.let { put(FIELD_EDITED_AT_MS, it) }
            envelope.deleted_at_ms?.let { put(FIELD_DELETED_AT_MS, it) }
            envelope.message_id?.let { put(FIELD_MESSAGE_ID, it) }
            envelope.reply_to?.let { put(FIELD_REPLY_TO, it) }
            envelope.message_compact_id?.let { put(FIELD_MESSAGE_COMPACT_ID, it) }
            envelope.ciphertext?.let { put(FIELD_CIPHERTEXT, it) }
            envelope.nonce?.let { put(FIELD_NONCE, it) }
            envelope.key_version?.let { put(FIELD_KEY_VERSION, it) }
            envelope.room_key?.let { put(FIELD_ROOM_KEY, it) }
            envelope.target_device_id?.let { put(FIELD_TARGET_DEVICE_ID, it) }
            envelope.room_name?.let { put(FIELD_ROOM_NAME, it) }
            envelope.participant_count?.let { put(FIELD_PARTICIPANT_COUNT, it) }
            envelope.closed_at_ms?.let { put(FIELD_CLOSED_AT_MS, it) }
        }
        val encoded = CanonicalJson.canonicalize(payload).toByteArray(Charsets.UTF_8)
        val maxBytes = when (envelope.kind) {
            NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1 -> NearbySelfInfoProtocol.MAX_SELF_INFO_WIRE_BYTES
            NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1 -> NearbySelfInfoProtocol.MAX_SELF_INFO_ACK_WIRE_BYTES
            else -> null
        }
        require(maxBytes == null || encoded.size <= maxBytes) {
            "${envelope.kind} exceeds wire budget"
        }
        return encoded
    }

    fun decode(bytes: ByteArray): NearbyRoomTransportEnvelope? {
        val raw = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val obj = runCatching {
            CanonicalJson.json.decodeFromString(JsonObject.serializer(), raw)
        }.getOrNull() ?: return null

        val kind = kindFromAlias(stringValue(obj, FIELD_KIND, LEGACY_FIELD_KIND) ?: return null) ?: return null
        val roomId = stringValue(obj, FIELD_ROOM_ID, LEGACY_FIELD_ROOM_ID) ?: return null
        val senderDeviceId = stringValue(obj, FIELD_SENDER_DEVICE_ID, LEGACY_FIELD_SENDER_DEVICE_ID) ?: return null
        val createdAtMs = longValue(obj, FIELD_CREATED_AT_MS, LEGACY_FIELD_CREATED_AT_MS) ?: return null
        val expiresAtMs = longValue(obj, FIELD_EXPIRES_AT_MS, LEGACY_FIELD_EXPIRES_AT_MS) ?: return null

        val isSelfInfo = kind == NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1
        val isSelfInfoAck = kind == NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1

        val envelope = NearbyRoomTransportEnvelope(
            kind = kind,
            room_id = roomId,
            sender_device_id = senderDeviceId,
            owner_device_id = stringValue(obj, FIELD_OWNER_DEVICE_ID, LEGACY_FIELD_OWNER_DEVICE_ID),
            sender_display_name = stringValue(obj, FIELD_SENDER_DISPLAY_NAME, LEGACY_FIELD_SENDER_DISPLAY_NAME),
            avatar_id = stringValue(obj, FIELD_AVATAR_ID, LEGACY_FIELD_AVATAR_ID),
            profile_updated_at_ms = longValue(obj, FIELD_PROFILE_UPDATED_AT_MS, LEGACY_FIELD_PROFILE_UPDATED_AT_MS),
            recipient_encryption_supported = if (isSelfInfo) {
                booleanValue(obj, FIELD_RECIPIENT_ENCRYPTION_SUPPORTED, LEGACY_FIELD_RECIPIENT_ENCRYPTION_SUPPORTED)
            } else null,
            recipient_encryption_algorithm = if (isSelfInfo) {
                strictStringValue(obj, FIELD_RECIPIENT_ENCRYPTION_ALGORITHM, LEGACY_FIELD_RECIPIENT_ENCRYPTION_ALGORITHM)
            } else null,
            recipient_encryption_key_id = if (isSelfInfo) {
                strictStringValue(obj, FIELD_RECIPIENT_ENCRYPTION_KEY_ID, LEGACY_FIELD_RECIPIENT_ENCRYPTION_KEY_ID)
            } else null,
            recipient_encryption_public_key = if (isSelfInfo) {
                strictStringValue(obj, FIELD_RECIPIENT_ENCRYPTION_PUBLIC_KEY, LEGACY_FIELD_RECIPIENT_ENCRYPTION_PUBLIC_KEY)
            } else null,
            self_info_revision = if (isSelfInfo || isSelfInfoAck) {
                strictStringValue(obj, FIELD_SELF_INFO_REVISION, LEGACY_FIELD_SELF_INFO_REVISION)
            } else null,
            origin_device_id = stringValue(obj, FIELD_ORIGIN_DEVICE_ID, LEGACY_FIELD_ORIGIN_DEVICE_ID)
                ?: stringValue(obj, FIELD_MESSAGE_OWNER_DEVICE_ID, LEGACY_FIELD_MESSAGE_OWNER_DEVICE_ID),
            created_at_ms = createdAtMs,
            expires_at_ms = expiresAtMs,
            edited_at_ms = longValue(obj, FIELD_EDITED_AT_MS, LEGACY_FIELD_EDITED_AT_MS),
            deleted_at_ms = longValue(obj, FIELD_DELETED_AT_MS, LEGACY_FIELD_DELETED_AT_MS),
            message_id = stringValue(obj, FIELD_MESSAGE_ID, LEGACY_FIELD_MESSAGE_ID),
            reply_to = stringValue(obj, FIELD_REPLY_TO, LEGACY_FIELD_REPLY_TO),
            message_compact_id = stringValue(obj, FIELD_MESSAGE_COMPACT_ID, LEGACY_FIELD_MESSAGE_COMPACT_ID),
            ciphertext = stringValue(obj, FIELD_CIPHERTEXT, LEGACY_FIELD_CIPHERTEXT),
            nonce = stringValue(obj, FIELD_NONCE, LEGACY_FIELD_NONCE),
            key_version = intValue(obj, FIELD_KEY_VERSION, LEGACY_FIELD_KEY_VERSION),
            room_key = stringValue(obj, FIELD_ROOM_KEY, LEGACY_FIELD_ROOM_KEY),
            target_device_id = stringValue(obj, FIELD_TARGET_DEVICE_ID, LEGACY_FIELD_TARGET_DEVICE_ID),
            room_name = stringValue(obj, FIELD_ROOM_NAME, LEGACY_FIELD_ROOM_NAME),
            participant_count = intValue(obj, FIELD_PARTICIPANT_COUNT, LEGACY_FIELD_PARTICIPANT_COUNT),
            closed_at_ms = longValue(obj, FIELD_CLOSED_AT_MS, LEGACY_FIELD_CLOSED_AT_MS),
        )
        return envelope.takeIf { ACCEPTED_KINDS.contains(it.kind) }
    }

    private fun shortKind(kind: String): String? {
        return when (kind) {
            NearbyRoomTransportEnvelope.KIND_MESSAGE_V2 -> NearbyRoomTransportEnvelope.KIND_SHORT_MESSAGE_V2
            NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_KEY_BOOTSTRAP_V1
            NearbyRoomTransportEnvelope.KIND_KEY_REQUEST_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_KEY_REQUEST_V1
            NearbyRoomTransportEnvelope.KIND_METADATA_REQUEST_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_METADATA_REQUEST_V1
            NearbyRoomTransportEnvelope.KIND_METADATA_RESPONSE_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_METADATA_RESPONSE_V1
            NearbyRoomTransportEnvelope.KIND_ROOM_CLOSED_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_ROOM_CLOSED_V1
            NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_OWNER_MUTE_V1
            NearbyRoomTransportEnvelope.KIND_OWNER_UNMUTE_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_OWNER_UNMUTE_V1
            NearbyRoomTransportEnvelope.KIND_MESSAGE_OFFER_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_MESSAGE_OFFER_V1
            NearbyRoomTransportEnvelope.KIND_MESSAGE_FETCH_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_MESSAGE_FETCH_V1
            NearbyRoomTransportEnvelope.KIND_MESSAGE_DATA_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_MESSAGE_DATA_V1
            NearbyRoomTransportEnvelope.KIND_JOIN_READY_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_JOIN_READY_V1
            NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_SELF_INFO_V1
            NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1 -> NearbyRoomTransportEnvelope.KIND_SHORT_SELF_INFO_ACK_V1
            else -> null
        }
    }

    private fun kindFromAlias(kind: String): String? {
        return when (kind) {
            NearbyRoomTransportEnvelope.KIND_MESSAGE_V2,
            NearbyRoomTransportEnvelope.KIND_SHORT_MESSAGE_V2,
            -> NearbyRoomTransportEnvelope.KIND_MESSAGE_V2
            NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_KEY_BOOTSTRAP_V1,
            -> NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1
            NearbyRoomTransportEnvelope.KIND_KEY_REQUEST_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_KEY_REQUEST_V1,
            -> NearbyRoomTransportEnvelope.KIND_KEY_REQUEST_V1
            NearbyRoomTransportEnvelope.KIND_METADATA_REQUEST_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_METADATA_REQUEST_V1,
            -> NearbyRoomTransportEnvelope.KIND_METADATA_REQUEST_V1
            NearbyRoomTransportEnvelope.KIND_METADATA_RESPONSE_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_METADATA_RESPONSE_V1,
            -> NearbyRoomTransportEnvelope.KIND_METADATA_RESPONSE_V1
            NearbyRoomTransportEnvelope.KIND_ROOM_CLOSED_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_ROOM_CLOSED_V1,
            -> NearbyRoomTransportEnvelope.KIND_ROOM_CLOSED_V1
            NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_OWNER_MUTE_V1,
            -> NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1
            NearbyRoomTransportEnvelope.KIND_OWNER_UNMUTE_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_OWNER_UNMUTE_V1,
            -> NearbyRoomTransportEnvelope.KIND_OWNER_UNMUTE_V1
            NearbyRoomTransportEnvelope.KIND_MESSAGE_OFFER_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_MESSAGE_OFFER_V1,
            -> NearbyRoomTransportEnvelope.KIND_MESSAGE_OFFER_V1
            NearbyRoomTransportEnvelope.KIND_MESSAGE_FETCH_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_MESSAGE_FETCH_V1,
            -> NearbyRoomTransportEnvelope.KIND_MESSAGE_FETCH_V1
            NearbyRoomTransportEnvelope.KIND_MESSAGE_DATA_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_MESSAGE_DATA_V1,
            -> NearbyRoomTransportEnvelope.KIND_MESSAGE_DATA_V1
            NearbyRoomTransportEnvelope.KIND_JOIN_READY_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_JOIN_READY_V1,
            -> NearbyRoomTransportEnvelope.KIND_JOIN_READY_V1
            NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_SELF_INFO_V1,
            -> NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1
            NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1,
            NearbyRoomTransportEnvelope.KIND_SHORT_SELF_INFO_ACK_V1,
            -> NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1
            else -> null
        }
    }

    private fun stringValue(obj: JsonObject, shortField: String, legacyField: String): String? {
        return primitiveValue(obj, shortField)?.takeIf { it.isNotBlank() }
            ?: primitiveValue(obj, legacyField)?.takeIf { it.isNotBlank() }
    }

    private fun strictStringValue(obj: JsonObject, shortField: String, legacyField: String): String? {
        return strictStringValue(obj[shortField])
            ?: strictStringValue(obj[legacyField])
    }

    private fun strictStringValue(value: kotlinx.serialization.json.JsonElement?): String? {
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    private fun booleanValue(obj: JsonObject, shortField: String, legacyField: String): Boolean? {
        return (obj[shortField] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: (obj[legacyField] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    }

    private fun longValue(obj: JsonObject, shortField: String, legacyField: String): Long? {
        return numericValue(obj[shortField])?.toLong()
            ?: numericValue(obj[legacyField])?.toLong()
    }

    private fun intValue(obj: JsonObject, shortField: String, legacyField: String): Int? {
        return numericValue(obj[shortField])?.toInt()
            ?: numericValue(obj[legacyField])?.toInt()
    }

    private fun primitiveValue(obj: JsonObject, field: String): String? {
        return (obj[field] as? JsonPrimitive)?.content
    }

    private fun numericValue(value: kotlinx.serialization.json.JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.content.toLongOrNull()
    }

    private val ACCEPTED_KINDS = setOf(
        NearbyRoomTransportEnvelope.KIND_MESSAGE_V2,
        NearbyRoomTransportEnvelope.KIND_KEY_BOOTSTRAP_V1,
        NearbyRoomTransportEnvelope.KIND_KEY_REQUEST_V1,
        NearbyRoomTransportEnvelope.KIND_METADATA_REQUEST_V1,
        NearbyRoomTransportEnvelope.KIND_METADATA_RESPONSE_V1,
        NearbyRoomTransportEnvelope.KIND_ROOM_CLOSED_V1,
        NearbyRoomTransportEnvelope.KIND_OWNER_MUTE_V1,
        NearbyRoomTransportEnvelope.KIND_OWNER_UNMUTE_V1,
        NearbyRoomTransportEnvelope.KIND_MESSAGE_OFFER_V1,
        NearbyRoomTransportEnvelope.KIND_MESSAGE_FETCH_V1,
        NearbyRoomTransportEnvelope.KIND_MESSAGE_DATA_V1,
        NearbyRoomTransportEnvelope.KIND_JOIN_READY_V1,
        NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1,
        NearbyRoomTransportEnvelope.KIND_SELF_INFO_ACK_V1,
    )

    private const val FIELD_KIND = "k"
    private const val FIELD_ROOM_ID = "r"
    private const val FIELD_SENDER_DEVICE_ID = "sd"
    private const val FIELD_OWNER_DEVICE_ID = "od"
    private const val FIELD_SENDER_DISPLAY_NAME = "sn"
    private const val FIELD_AVATAR_ID = "av"
    private const val FIELD_PROFILE_UPDATED_AT_MS = "pu"
    private const val FIELD_RECIPIENT_ENCRYPTION_SUPPORTED = "res"
    private const val FIELD_RECIPIENT_ENCRYPTION_ALGORITHM = "rea"
    private const val FIELD_RECIPIENT_ENCRYPTION_KEY_ID = "rek"
    private const val FIELD_RECIPIENT_ENCRYPTION_PUBLIC_KEY = "rep"
    private const val FIELD_SELF_INFO_REVISION = "sir"
    private const val FIELD_ORIGIN_DEVICE_ID = "og"
    private const val FIELD_CREATED_AT_MS = "ct"
    private const val FIELD_EXPIRES_AT_MS = "ex"
    private const val FIELD_EDITED_AT_MS = "et"
    private const val FIELD_DELETED_AT_MS = "dt"
    private const val FIELD_MESSAGE_ID = "mid"
    private const val FIELD_REPLY_TO = "rp"
    private const val FIELD_CIPHERTEXT = "ctxt"
    private const val FIELD_MESSAGE_COMPACT_ID = "m"
    private const val FIELD_MESSAGE_OWNER_DEVICE_ID = "mo"
    private const val FIELD_NONCE = "n"
    private const val FIELD_KEY_VERSION = "kv"
    private const val FIELD_ROOM_KEY = "rk"
    private const val FIELD_TARGET_DEVICE_ID = "td"
    private const val FIELD_ROOM_NAME = "rn"
    private const val FIELD_PARTICIPANT_COUNT = "pc"
    private const val FIELD_CLOSED_AT_MS = "cl"

    private const val LEGACY_FIELD_KIND = "kind"
    private const val LEGACY_FIELD_ROOM_ID = "room_id"
    private const val LEGACY_FIELD_SENDER_DEVICE_ID = "sender_device_id"
    private const val LEGACY_FIELD_OWNER_DEVICE_ID = "owner_device_id"
    private const val LEGACY_FIELD_SENDER_DISPLAY_NAME = "sender_display_name"
    private const val LEGACY_FIELD_AVATAR_ID = "avatar_id"
    private const val LEGACY_FIELD_PROFILE_UPDATED_AT_MS = "profile_updated_at_ms"
    private const val LEGACY_FIELD_RECIPIENT_ENCRYPTION_SUPPORTED = "recipient_encryption_supported"
    private const val LEGACY_FIELD_RECIPIENT_ENCRYPTION_ALGORITHM = "recipient_encryption_algorithm"
    private const val LEGACY_FIELD_RECIPIENT_ENCRYPTION_KEY_ID = "recipient_encryption_key_id"
    private const val LEGACY_FIELD_RECIPIENT_ENCRYPTION_PUBLIC_KEY = "recipient_encryption_public_key"
    private const val LEGACY_FIELD_SELF_INFO_REVISION = "self_info_revision"
    private const val LEGACY_FIELD_ORIGIN_DEVICE_ID = "origin_device_id"
    private const val LEGACY_FIELD_CREATED_AT_MS = "created_at_ms"
    private const val LEGACY_FIELD_EXPIRES_AT_MS = "expires_at_ms"
    private const val LEGACY_FIELD_EDITED_AT_MS = "edited_at_ms"
    private const val LEGACY_FIELD_DELETED_AT_MS = "deleted_at_ms"
    private const val LEGACY_FIELD_MESSAGE_ID = "message_id"
    private const val LEGACY_FIELD_REPLY_TO = "reply_to"
    private const val LEGACY_FIELD_CIPHERTEXT = "ciphertext"
    private const val LEGACY_FIELD_MESSAGE_COMPACT_ID = "message_compact_id"
    private const val LEGACY_FIELD_MESSAGE_OWNER_DEVICE_ID = "message_owner_device_id"
    private const val LEGACY_FIELD_NONCE = "nonce"
    private const val LEGACY_FIELD_KEY_VERSION = "key_version"
    private const val LEGACY_FIELD_ROOM_KEY = "room_key"
    private const val LEGACY_FIELD_TARGET_DEVICE_ID = "target_device_id"
    private const val LEGACY_FIELD_ROOM_NAME = "room_name"
    private const val LEGACY_FIELD_PARTICIPANT_COUNT = "participant_count"
    private const val LEGACY_FIELD_CLOSED_AT_MS = "closed_at_ms"
}
