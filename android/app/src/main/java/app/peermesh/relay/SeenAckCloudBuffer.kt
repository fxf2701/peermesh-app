package app.peermesh.relay

import android.content.Context
import app.peermesh.DeviceIdProvider
import app.peermesh.KeyIdHelper
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.id.EventIdFactory
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.membership.GroupMembershipPolicy
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

object SeenAckCloudBuffer {
    private const val ACK_TYPE_SEEN = "seen"
    private const val ACK_TARGETS_PER_EVENT = 8

    private val pendingByGid = ConcurrentHashMap<String, LinkedHashSet<String>>()
    private val lock = Any()

    fun enqueue(gid: String, targetEventId: String) {
        synchronized(lock) {
            pendingByGid
                .getOrPut(gid) { LinkedHashSet() }
                .add(targetEventId)
        }
    }

    fun flushToDb(context: Context): Int {
        val batches = mutableListOf<Pair<String, List<String>>>()
        synchronized(lock) {
            pendingByGid.entries.forEach { (gid, targetIds) ->
                if (targetIds.isNotEmpty()) {
                    batches += gid to targetIds.toList()
                }
            }
            pendingByGid.clear()
        }
        if (batches.isEmpty()) return 0

        val db = AppDatabaseProvider.get(context)
        val actorDid = DeviceIdProvider.get(context)
        val nowSeconds = System.currentTimeMillis() / 1000L
        var insertedCount = 0

        batches.forEach { (gid, targetIds) ->
            if (GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)) {
                ActivityLog.add(
                    label = "SYS ACK blocked removed member",
                    source = "relay",
                    message = "reason=cloud_seen_flush_removed_member targets=${targetIds.size}",
                    gid = gid,
                )
                return@forEach
            }
            targetIds.chunked(ACK_TARGETS_PER_EVENT).forEach { targets ->
                val core = EventFactory.sysAck(
                    eventId = EventIdFactory.generate(gid, actorDid, nowSeconds, EventIdFactory.generateNonce()),
                    actorDid = actorDid,
                    createdSeconds = nowSeconds,
                    lamport = System.currentTimeMillis(),
                    targetEventId = targets.first(),
                    targetEventIds = targets,
                    ackType = ACK_TYPE_SEEN,
                )
                if (persistAckEvent(db, context, gid, core, nowSeconds)) {
                    insertedCount += 1
                }
            }
        }
        return insertedCount
    }

    private fun persistAckEvent(
        db: app.peermesh.data.db.AppDatabase,
        context: Context,
        gid: String,
        core: EventCore,
        insertedAtSeconds: Long,
    ): Boolean {
        val coreWithKid = KeyIdHelper.attachKeyId(context, gid, core)
        val payloadJson = CanonicalJson.json.encodeToString(EventCore.serializer(), coreWithKid)
        val event = EventEntity(
            gid = gid,
            event_id = coreWithKid.id,
            type = coreWithKid.type,
            source = "local",
            created_t = coreWithKid.time.t,
            time_lt = coreWithKid.time.lt ?: 0L,
            actor_did = coreWithKid.actor.did,
            ref_root = coreWithKid.ref.root,
            ref_parent = coreWithKid.ref.parent,
            ref_target = coreWithKid.ref.target,
            ttl_s = coreWithKid.meta?.ttl_s,
            payload_json = payloadJson,
            inserted_at = insertedAtSeconds,
        )
        var inserted = false
        db.runInTransaction {
            val result = db.eventsDao().insert(event)
            inserted = result != -1L
        }
        return inserted
    }
}
