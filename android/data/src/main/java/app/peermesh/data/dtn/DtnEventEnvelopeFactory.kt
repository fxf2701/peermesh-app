package app.peermesh.data.dtn

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.DtnMeta
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.ttl.TtlHelper
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.transport.DtnTransportPolicy
import app.peermesh.security.crypto.EnvelopeCrypto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class DtnEventEnvelopeFactory(
    private val groupKeyLoader: (String) -> ByteArray?,
) {
    fun toEnvelopeJson(entity: EventEntity): String? {
        val core = parseCore(entity.payload_json) ?: return null
        val groupKey = groupKeyLoader(entity.gid) ?: return null
        val ttlSeconds = transportTtlSeconds(core)
        val dtn = DtnMeta(
            ttl_seconds = ttlSeconds,
            hop_count = 0,
            max_hops = DtnTransportPolicy.DEFAULT_MAX_HOPS,
            priority = DtnTransportPolicy.priorityForEventType(core.type),
        )
        val envelope = EnvelopeCrypto.encryptAndSign(
            gid = entity.gid,
            mid = core.id,
            ts = core.time.t,
            ttl = ttlSeconds,
            dtn = dtn,
            core = core,
            groupKey = groupKey,
        )
        return CanonicalJson.json.encodeToString(EventEnvelope.serializer(), envelope)
    }

    private fun parseCore(json: String): EventCore? {
        return try {
            CanonicalJson.json.decodeFromString(EventCore.serializer(), json)
        } catch (_: Exception) {
            null
        }
    }

    private fun transportTtlSeconds(core: EventCore): Int {
        val ttlSeconds = if (
            EventTypes.isPriorityControl(core.type) ||
            EventTypes.isMap(core.type) ||
            EventTypes.isLiveTrail(core.type)
        ) {
            TtlHelper.resolveTtlSeconds(core.type, core.meta?.ttl_s)
        } else {
            DtnTransportPolicy.DEFAULT_TTL_SECONDS.toLong()
        }
        return ttlSeconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }
}
