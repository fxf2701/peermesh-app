package app.peermesh.data.relay

import app.peermesh.core.id.RelayEventIdFactory
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.relay.RelayEvent
import app.peermesh.security.crypto.RelayPayloadCrypto
import app.peermesh.core.ttl.TtlHelper
import app.peermesh.data.db.entity.EventEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.encodeToJsonElement

class RelayEventMapper(
    private val groupKeyLoader: (String) -> ByteArray?,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    data class MappingResult(
        val relayEvent: RelayEvent?,
        val dropReason: String?,
        val dropDetail: String? = null,
    )

    fun toRelayEvent(entity: EventEntity): RelayEvent? {
        return mapWithReason(entity).relayEvent
    }

    fun mapWithReason(entity: EventEntity): MappingResult {
        val core = parseCore(entity.payload_json)
            ?: return MappingResult(null, "parse_core")
        val groupKey = groupKeyLoader(entity.gid)
            ?: return MappingResult(null, "missing_group_key")
        val keyId = core.meta?.kid?.takeIf { it.isNotBlank() }
            ?: return MappingResult(null, "missing_key_id")
        val ttlSeconds = TtlHelper.resolveTtlSeconds(core.type, core.meta?.ttl_s)
        val expiresAt = TtlHelper.expiresAt(core.time.t, ttlSeconds)
        return MappingResult(
            relayEvent = toRelayEvent(
                gid = entity.gid,
                core = core,
                expiresAt = expiresAt,
                groupKey = groupKey,
                keyId = keyId,
            ),
            dropReason = null,
            dropDetail = null,
        )
    }

    private fun parseCore(json: String): EventCore? {
        return try {
            CanonicalJson.json.decodeFromString(EventCore.serializer(), json)
        } catch (_: Exception) {
            null
        }
    }

    private fun toRelayEvent(
        gid: String,
        core: EventCore,
        expiresAt: Long,
        groupKey: ByteArray,
        keyId: String,
    ): RelayEvent {
        val eid = RelayEventIdFactory.fromEventId(core.id)
        val body = RelayPayloadCrypto.encryptBody(
            gid = gid,
            eid = eid,
            ts = core.time.t,
            exp = expiresAt,
            core = core,
            groupKey = groupKey,
            kid = keyId,
        )
        val unsigned = RelayEvent(
            eid = eid,
            gid = gid,
            ts = core.time.t,
            exp = expiresAt,
            body = CanonicalJson.json.encodeToJsonElement(body),
            sig = "",
        )
        return unsigned.copy(sig = RelayPayloadCrypto.signRelayEvent(unsigned, groupKey))
    }
}
