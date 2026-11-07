package app.peermesh.data.ingest

import app.peermesh.core.cache.ReplayCache
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.ttl.TtlHelper
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.time.EventClockSkew
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.dao.EventDedupeDao
import app.peermesh.data.db.entity.EventDedupeEntity
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.membership.GroupMembershipPolicy
import app.peermesh.data.map.processing.MapEventProcessor
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.RoomMapEventProcessorFactory
import app.peermesh.data.reducer.ChatReducer
import app.peermesh.data.reducer.LiveTrailReducer
import app.peermesh.data.reducer.SysReducer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.util.Log

class EventIngestor(
    val db: AppDatabase,
    private val replayCache: ReplayCache = ReplayCache(24 * 60 * 60),
    private val dedupeTtlSeconds: Long = 24 * 60 * 60L,
    private val localActorProvider: (() -> String?)? = null,
    private val hiddenMessagePeerIdsProvider: ((String) -> Set<String>)? = null,
    private val mapEventProcessor: MapEventProcessor = RoomMapEventProcessorFactory.lazy(db),
) {
    private val chatReducer = ChatReducer(db.chatDao(), db.deletedEventDao(), db.eventsDao())
    private val sysReducer = SysReducer(db.groupDao(), db.userProfileDao(), db.removedMemberDao())
    private val liveTrailReducer = LiveTrailReducer(db.liveTrailDao())
    @Volatile private var lastDedupePruneAtSeconds: Long = 0L
    @Volatile private var lastDeletedEventPruneAtSeconds: Long = 0L

    fun ingest(envelope: EventEnvelope, core: EventCore, source: String = EventSources.MESH): Boolean {
        if (core.id != envelope.mid) {
            Log.w(TAG, "Drop: id mismatch core=${core.id} mid=${envelope.mid}")
            return false
        }
        val localActorDid = localActorProvider?.invoke()
        val nowSeconds = nowSeconds()
        if (EventTypes.isMap(core.type)) {
            return ingestRemoteMap(envelope, core, source, nowSeconds, localActorDid)
        }
        val cacheKey = "${envelope.gid}:${core.id}"
        if (replayCache.seen(cacheKey, nowSeconds)) {
            Log.w(TAG, "Drop: replay cache hit id=${core.id}")
            ActivityLog.add(
                label = "Hybrid dedupe memory hit",
                source = "ingest",
                message = "id=${core.id}",
                eventId = core.id,
                gid = envelope.gid,
            )
            ActivityLog.add(
                label = "DB event duplicate drop",
                source = source,
                message = "id=${core.id} type=${core.type} reason=replay_cache",
                eventId = core.id,
                gid = envelope.gid,
            )
            return false
        }
        logChatEditReceipt(envelope.gid, core, source, localActorDid)
        val dedupeDao = db.eventDedupeDao()
        if (dedupeDao.countActive(envelope.gid, core.id, nowSeconds) > 0) {
            Log.w(TAG, "Drop: dedupe store hit id=${core.id}")
            ActivityLog.add(
                label = "Hybrid dedupe db hit",
                source = "ingest",
                message = "id=${core.id}",
                eventId = core.id,
                gid = envelope.gid,
            )
            ActivityLog.add(
                label = "DB event duplicate drop",
                source = source,
                message = "id=${core.id} type=${core.type} reason=dedupe_store",
                eventId = core.id,
                gid = envelope.gid,
            )
            return false
        }
        maybePruneDedupeStore(dedupeDao, nowSeconds)
        maybePruneDeletedEventStore(nowSeconds)
        val adjustedCore = adjustClock(core, nowSeconds)
        if (isExpired(adjustedCore, nowSeconds)) {
            Log.w(TAG, "Drop: expired id=${core.id}")
            return false
        }
        if (shouldRejectDeletedTarget(envelope.gid, adjustedCore, nowSeconds)) {
            Log.w(TAG, "Drop: deleted tombstone hit id=${core.id} type=${core.type}")
            ActivityLog.add(
                label = "Deleted tombstone reject",
                source = "ingest",
                message = "id=${core.id} type=${core.type}",
                eventId = core.id,
                gid = envelope.gid,
            )
            return false
        }
        if (GroupMembershipPolicy.shouldRejectForRemovedLocalMember(db, envelope.gid, adjustedCore, localActorDid)) {
            Log.w(TAG, "Drop: local removed member id=${core.id} type=${core.type}")
            ActivityLog.add(
                label = "Removed local member event rejected",
                source = "ingest",
                message = "id=${core.id} type=${core.type} local_actor=${localActorDid ?: "-"} actor=${adjustedCore.actor.did}",
                eventId = core.id,
                gid = envelope.gid,
            )
            return false
        }
        if (GroupMembershipPolicy.shouldRejectEvent(db, envelope.gid, adjustedCore)) {
            Log.w(TAG, "Drop: removed member actor id=${core.id} type=${core.type}")
            ActivityLog.add(
                label = "Removed member event rejected",
                source = "ingest",
                message = "id=${core.id} type=${core.type} actor=${adjustedCore.actor.did}",
                eventId = core.id,
                gid = envelope.gid,
            )
            return false
        }
        if (adjustedCore.type == EventTypes.SYS_GROUP_REKEY || adjustedCore.type == EventTypes.SYS_MEMBER_REMOVED) {
            ActivityLog.add(
                label = "Membership control event ingest",
                source = source,
                message = "id=${adjustedCore.id} type=${adjustedCore.type} actor=${adjustedCore.actor.did}",
                eventId = adjustedCore.id,
                gid = envelope.gid,
            )
        }

        val payloadJson = CanonicalJson.json.encodeToString(EventCore.serializer(), core)
        val eventEntity = EventEntity(
            gid = envelope.gid,
            event_id = core.id,
            type = core.type,
            source = source,
            created_t = core.time.t,
            time_lt = core.time.lt ?: 0L,
            actor_did = core.actor.did,
            ref_root = core.ref.root,
            ref_parent = core.ref.parent,
            ref_target = core.ref.target,
            ttl_s = core.meta?.ttl_s,
            payload_json = payloadJson,
            inserted_at = nowSeconds,
        )

        var inserted = false
        val hiddenMessagePeerIds = hiddenMessagePeerIdsProvider?.invoke(envelope.gid).orEmpty()
        val skipChatMaterialization =
            EventTypes.isChat(core.type) && core.actor.did in hiddenMessagePeerIds
        db.runInTransaction {
            val result = db.eventsDao().insert(eventEntity)
            if (result != -1L) {
                inserted = true
                if (skipChatMaterialization) {
                    ActivityLog.add(
                        label = "Blocked chat materialization skipped",
                        source = "moderation",
                        message =
                            "gid=${envelope.gid} actor=${core.actor.did} type=${core.type} " +
                                "reason=blocked_peer no_message_insert=true",
                        eventId = core.id,
                        gid = envelope.gid,
                    )
                } else {
                    chatReducer.apply(core, envelope.gid)
                }
                sysReducer.apply(core, envelope.gid)
                liveTrailReducer.apply(core, envelope.gid)
                replayBufferedLiveTrailPoints(envelope.gid, core)
            }
        }
        if (inserted) {
            upsertDedupeMark(dedupeDao, envelope.gid, core.id, nowSeconds)
            Log.i(TAG, "Inserted event id=${core.id} gid=${envelope.gid} source=$source")
            ActivityLog.add(
                label = "DB event insert",
                source = source,
                message = eventLogMessage(core),
                eventId = core.id,
                gid = envelope.gid,
            )
            IngestNotifier.notifyInserted(envelope.gid, core.id)
        } else {
            upsertDedupeMark(dedupeDao, envelope.gid, core.id, nowSeconds)
            Log.w(TAG, "Drop: duplicate event id=${core.id}")
            ActivityLog.add(
                label = "DB event duplicate drop",
                source = source,
                message = "id=${core.id} type=${core.type} reason=insert_conflict",
                eventId = core.id,
                gid = envelope.gid,
            )
        }
        return inserted
    }

    /**
     * Map events intentionally bypass EventIngestor's replay-cache and durable-dedupe fast paths:
     * both are keyed only by event ID and therefore cannot distinguish exact from conflicting
     * duplicates. The shared processor performs authoritative classification transactionally.
     */
    private fun ingestRemoteMap(
        envelope: EventEnvelope,
        core: EventCore,
        source: String,
        nowSeconds: Long,
        localActorDid: String?,
    ): Boolean {
        val dedupeDao = db.eventDedupeDao()
        maybePruneDedupeStore(dedupeDao, nowSeconds)
        val outcome = DefaultRemoteMapEventResultMapper.map(
            mapEventProcessor.process(
                MapEventProcessingRequest(
                    gid = envelope.gid,
                    core = core,
                    source = source,
                    insertedAtSeconds = nowSeconds,
                    localActorDid = localActorDid,
                )
            )
        )
        return when (outcome) {
            is RemoteMapIngestOutcome.Accepted,
            is RemoteMapIngestOutcome.AcceptedNoChange,
            -> {
                val eventId = requireNotNull(outcome.eventId)
                upsertDedupeMark(dedupeDao, envelope.gid, eventId, nowSeconds)
                Log.i(TAG, "Inserted map event id=$eventId gid=${envelope.gid} source=$source no_change=${outcome is RemoteMapIngestOutcome.AcceptedNoChange}")
                ActivityLog.add(
                    label = "DB event insert",
                    source = source,
                    message = "${eventLogMessage(core)} map_no_change=${outcome is RemoteMapIngestOutcome.AcceptedNoChange}",
                    eventId = eventId,
                    gid = envelope.gid,
                )
                IngestNotifier.notifyInserted(envelope.gid, eventId)
                true
            }
            is RemoteMapIngestOutcome.AcceptedDeferred -> {
                upsertDedupeMark(dedupeDao, envelope.gid, outcome.eventId, nowSeconds)
                Log.i(
                    TAG,
                    "Deferred map event id=${outcome.eventId} gid=${envelope.gid} " +
                        "source=$source root=${outcome.rootEventId ?: "unknown"}",
                )
                ActivityLog.add(
                    label = "Map event deferred",
                    source = source,
                    message = "id=${outcome.eventId} type=${core.type} " +
                        "reason=missing_canonical root=${outcome.rootEventId ?: "unknown"}",
                    eventId = outcome.eventId,
                    gid = envelope.gid,
                )
                outcome.rootEventId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { rootEventId ->
                        IngestNotifier.notifyCanonicalMapRepairRequested(
                            gid = envelope.gid,
                            rootEventId = rootEventId,
                        )
                    }
                true
            }
            is RemoteMapIngestOutcome.ExactDuplicate -> {
                Log.w(TAG, "Drop: exact duplicate map event id=${outcome.eventId} source=$source")
                ActivityLog.add("DB event duplicate drop", source, "id=${outcome.eventId} type=${core.type} reason=exact_duplicate", outcome.eventId, envelope.gid)
                false
            }
            is RemoteMapIngestOutcome.ConflictingDuplicate -> {
                Log.w(TAG, "Drop: conflicting duplicate map event id=${outcome.eventId} source=$source")
                ActivityLog.add("DB event conflicting duplicate", source, "id=${outcome.eventId} type=${core.type} reason=conflicting_duplicate", outcome.eventId, envelope.gid)
                false
            }
            is RemoteMapIngestOutcome.Rejected -> {
                Log.w(TAG, "Reject map event id=${outcome.eventId} source=$source reason=${outcome.reason.persistedValue}")
                ActivityLog.add("Map event semantic rejection", source, "id=${outcome.eventId ?: core.id} type=${core.type} reason=${outcome.reason.persistedValue}", outcome.eventId, envelope.gid)
                false
            }
            is RemoteMapIngestOutcome.InvalidPersistedState -> {
                Log.e(TAG, "Invalid persisted map state id=${outcome.eventId} source=$source detail=${outcome.detail}")
                ActivityLog.add("Map event invalid persisted state", source, "id=${outcome.eventId} detail=${outcome.detail}", outcome.eventId, envelope.gid)
                false
            }
            is RemoteMapIngestOutcome.PersistenceFailure -> {
                Log.e(TAG, "Map event persistence failure id=${outcome.eventId} source=$source", outcome.cause)
                ActivityLog.add("Map event persistence failure", source, "id=${outcome.eventId ?: core.id} cause=${outcome.cause::class.java.simpleName}", outcome.eventId, envelope.gid)
                false
            }
        }
    }

    private fun logChatEditReceipt(
        gid: String,
        core: EventCore,
        source: String,
        localActorDid: String?,
    ) {
        if (core.type != EventTypes.CHAT_EDIT) return
        val targetId = core.ref.target
            ?: (core.data as? JsonObject)?.get("target_id")?.jsonPrimitive?.content
            ?: "-"
        ActivityLog.add(
            label = "Chat edit received",
            source = source,
            message = "via=$source target=$targetId actor=${core.actor.did}",
            eventId = core.id,
            gid = gid,
        )
        if (source == EventSources.RELAY && !localActorDid.isNullOrBlank() && localActorDid == core.actor.did) {
            ActivityLog.add(
                label = "Event transport outcome",
                source = source,
                message = "status=relay_echo_only type=${core.type} target=$targetId actor=${core.actor.did}",
                eventId = core.id,
                gid = gid,
            )
        }
    }

    private fun replayBufferedLiveTrailPoints(gid: String, core: EventCore) {
        if (core.type != EventTypes.LIVE_TRAIL_START) return
        val trailId = liveTrailId(core) ?: return
        val bufferedPointEvents = db.eventsDao().listByTypeAndTarget(
            gid = gid,
            type = EventTypes.LIVE_TRAIL_POINT_BATCH,
            targetId = trailId,
        )
        if (bufferedPointEvents.isEmpty()) return
        var replayed = 0
        bufferedPointEvents.forEach { event ->
            val pointCore = runCatching {
                CanonicalJson.json.decodeFromString<EventCore>(event.payload_json)
            }.getOrNull() ?: return@forEach
            liveTrailReducer.apply(pointCore, gid)
            replayed += 1
        }
        ActivityLog.add(
            label = "Live trail point replay",
            source = "ingest",
            message = "trail=$trailId events=$replayed trigger=${core.id}",
            eventId = core.id,
            gid = gid,
        )
    }

    private fun liveTrailId(core: EventCore): String? {
        val dataTrailId = (core.data as? JsonObject)
            ?.get("trail_id")
            ?.jsonPrimitive
            ?.content
            ?.takeIf { it.isNotBlank() }
        return dataTrailId ?: core.ref.target
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun shouldRejectDeletedTarget(gid: String, core: EventCore, nowSeconds: Long): Boolean {
        return when (core.type) {
            EventTypes.CHAT_MSG, EventTypes.CHAT_REPLY -> {
                db.deletedEventDao().findActive(gid, core.id, nowSeconds) != null
            }
            EventTypes.CHAT_EDIT -> {
                val targetId = core.ref.target
                    ?: (core.data as? JsonObject)?.get("target_id")?.jsonPrimitive?.content
                !targetId.isNullOrBlank() && db.deletedEventDao().findActive(gid, targetId, nowSeconds) != null
            }
            else -> false
        }
    }

    private fun upsertDedupeMark(
        dedupeDao: EventDedupeDao,
        gid: String,
        eventId: String,
        nowSeconds: Long,
    ) {
        dedupeDao.upsert(
            EventDedupeEntity(
                gid = gid,
                event_id = eventId,
                seen_at = nowSeconds,
                expires_at = nowSeconds + dedupeTtlSeconds,
            )
        )
        ActivityLog.add(
            label = "Hybrid dedupe mark upsert",
            source = "ingest",
            message = "id=$eventId ttl_s=$dedupeTtlSeconds",
            eventId = eventId,
            gid = gid,
        )
    }

    private fun maybePruneDedupeStore(
        dedupeDao: EventDedupeDao,
        nowSeconds: Long,
    ) {
        if (nowSeconds - lastDedupePruneAtSeconds < DEDUPE_PRUNE_INTERVAL_SECONDS) return
        val deleted = dedupeDao.deleteExpired(nowSeconds)
        if (deleted > 0) {
            ActivityLog.add(
                label = "Hybrid dedupe prune",
                source = "ingest",
                message = "deleted=$deleted",
            )
        }
        lastDedupePruneAtSeconds = nowSeconds
    }

    private fun maybePruneDeletedEventStore(nowSeconds: Long) {
        if (nowSeconds - lastDeletedEventPruneAtSeconds < DEDUPE_PRUNE_INTERVAL_SECONDS) return
        val deleted = db.deletedEventDao().deleteExpired(nowSeconds)
        if (deleted > 0) {
            ActivityLog.add(
                label = "Deleted tombstone prune",
                source = "ingest",
                message = "deleted=$deleted",
            )
        }
        lastDeletedEventPruneAtSeconds = nowSeconds
    }

    private fun isExpired(core: EventCore, nowSeconds: Long): Boolean {
        val ttlSeconds = TtlHelper.resolveTtlSeconds(core.type, core.meta?.ttl_s)
        val expiresAt = TtlHelper.expiresAt(core.time.t, ttlSeconds)
        return expiresAt <= nowSeconds
    }

    private fun adjustClock(core: EventCore, arrivalSeconds: Long): EventCore {
        val maxSkewSeconds = EventClockSkew.maxSkewSecondsForType(core.type) ?: return core
        if (EventClockSkew.isSkewed(core.time.t, arrivalSeconds, maxSkewSeconds)) {
            Log.w(
                TAG,
                "Clock skew detected id=${core.id} type=${core.type} event_t=${core.time.t} arrival_t=$arrivalSeconds max_skew_s=$maxSkewSeconds (UTC epoch seconds)",
            )
        }
        val effectiveTime = EventClockSkew.resolveEventTimeSeconds(core.time.t, arrivalSeconds, maxSkewSeconds)
        return if (effectiveTime == core.time.t) {
            core
        } else {
            core.copy(time = core.time.copy(t = effectiveTime))
        }
    }

    private fun eventLogMessage(core: EventCore): String = buildString {
        val text = runCatching {
            (core.data as? JsonObject)
                ?.get("text")
                ?.jsonPrimitive
                ?.content
        }.getOrNull()
        if (!text.isNullOrBlank()) {
            append("text=")
            append(text.trim())
            append(" ")
        }
        append("actor=")
        append(core.actor.did)
        append(" created_s=")
        append(core.time.t)
        append(" type=")
        append(core.type)
    }
}

private const val TAG = "EventIngestor"
private const val DEDUPE_PRUNE_INTERVAL_SECONDS = 300L

object EventSources {
    const val MESH = "mesh"
    const val RELAY = "relay"
    const val DTN = "dtn"
}
