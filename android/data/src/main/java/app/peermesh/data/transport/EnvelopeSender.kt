package app.peermesh.data.transport

import app.peermesh.core.model.EventCore
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.DtnMeta
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.ttl.TtlHelper
import app.peermesh.core.types.EventTypes
import app.peermesh.core.transport.EnvelopeBroadcaster
import app.peermesh.data.debug.ActivityLog
import app.peermesh.security.crypto.EnvelopeCrypto
import kotlinx.serialization.encodeToString
import app.peermesh.data.transport.AppBatcher

class EnvelopeSender(
    private val broadcaster: EnvelopeBroadcaster,
    private val groupKeyLoader: (String) -> ByteArray?,
) {
    fun send(
        gid: String,
        core: EventCore,
        ttlSeconds: Int? = null,
        maxHops: Int = DtnTransportPolicy.DEFAULT_MAX_HOPS,
        priority: Int? = null,
        targetDeviceIds: Set<String> = emptySet(),
    ): Boolean {
        val groupKey = groupKeyLoader(gid) ?: return false
        val resolvedTtlSeconds = ttlSeconds ?: if (EventTypes.isPriorityControl(core.type)) {
            TtlHelper.resolveTtlSeconds(core.type, core.meta?.ttl_s).toInt()
        } else {
            DtnTransportPolicy.DEFAULT_TTL_SECONDS
        }
        val dtnMeta = DtnMeta(
            ttl_seconds = resolvedTtlSeconds,
            hop_count = 0,
            max_hops = maxHops,
            priority = priority ?: DtnTransportPolicy.priorityForEventType(core.type),
        )
        val envelope = EnvelopeCrypto.encryptAndSign(
            gid = gid,
            mid = core.id,
            ts = core.time.t,
            ttl = resolvedTtlSeconds,
            dtn = dtnMeta,
            core = core,
            groupKey = groupKey,
        )
        ActivityLog.add(
            label = "DTN metadata attached",
            source = "mesh",
            message = "type=${core.type} ttl_s=${dtnMeta.ttl_seconds} hop=${dtnMeta.hop_count}/${dtnMeta.max_hops} prio=${dtnMeta.priority}",
            eventId = core.id,
            gid = gid,
        )
        if (EventTypes.isPriorityControl(core.type)) {
            ActivityLog.add(
                label = "Membership control DTN prepared",
                source = "mesh",
                message = "type=${core.type} ttl_s=${dtnMeta.ttl_seconds} prio=${dtnMeta.priority} targets=${targetDeviceIds.size}",
                eventId = core.id,
                gid = gid,
            )
        }
        val json = CanonicalJson.json.encodeToString(EventEnvelope.serializer(), envelope)
        ActivityLog.add(
            label = "Sent message at",
            source = "mesh",
            message = "type=${core.type} event_id=${core.id} created_s=${core.time.t}",
            eventId = core.id,
            gid = gid,
        )
        if (EventTypes.isPriorityControl(core.type)) {
            ActivityLog.add(
                label = "Membership control send",
                source = "mesh",
                message = "type=${core.type} event_id=${core.id}",
                eventId = core.id,
                gid = gid,
            )
        }
        android.util.Log.i(TAG, "Sent message at ${System.currentTimeMillis()} gid=$gid eventId=${core.id}")
        return AppBatcher.enqueue(
            broadcaster = broadcaster,
            gid = gid,
            coreType = core.type,
            envelopeBytes = json.toByteArray(Charsets.UTF_8),
            targetDeviceIds = targetDeviceIds,
        )
    }

    companion object {
        private const val TAG = "EnvelopeSender"
    }
}
