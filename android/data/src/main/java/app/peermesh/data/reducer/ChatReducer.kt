package app.peermesh.data.reducer

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.time.EventClock
import app.peermesh.core.time.EventClockComparator
import app.peermesh.core.ttl.TtlHelper
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.dao.ChatDao
import app.peermesh.data.db.dao.DeletedEventDao
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.entity.DeletedEventEntity
import app.peermesh.data.db.entity.ChatMessageEntity
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.debug.ActivityLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.decodeFromString

class ChatReducer(
    private val chatDao: ChatDao,
    private val deletedEventDao: DeletedEventDao,
    private val eventsDao: EventsDao,
) {
    fun apply(event: EventCore, gid: String) {
        when (event.type) {
            EventTypes.CHAT_MSG, EventTypes.CHAT_REPLY -> applyMessage(event, gid)
            EventTypes.CHAT_EDIT -> applyEdit(event, gid)
            EventTypes.CHAT_DEL -> applyDelete(event, gid)
            EventTypes.SYS_ACK -> applyAck(event, gid)
        }
    }

    private fun applyMessage(event: EventCore, gid: String) {
        val text = JsonAccess.stringValue(event.data, "text") ?: return
        if (text.length > 200) return
        if (deletedEventDao.findActive(gid, event.id, nowSeconds()) != null) return
        val replyTo = replyToId(event)
        val clock = eventClock(event)

        val existing = chatDao.findMessage(gid, event.id)
        val ttlSeconds = TtlHelper.resolveTtlSeconds(event.type, event.meta?.ttl_s)
        val expiresAt = TtlHelper.expiresAt(event.time.t, ttlSeconds)

        existing?.let { existingMessage ->
            if (!EventClockComparator.isNewer(clock, existingClock(existingMessage))) {
                if (existingMessage.last_event_id != event.id) {
                    val merged = mergeBaseMessage(existingMessage, event, text, replyTo, expiresAt)
                    chatDao.upsertMessage(merged)
                    replayPendingEdits(gid, event.id)
                    ActivityLog.add(
                        label = "Chat reducer upsert",
                        source = "reducer",
                        message = reducerLogMessage(
                            kind = "message_merge",
                            appliedEventId = event.id,
                            targetMessageId = event.id,
                            row = merged,
                            extra = "status=${merged.status} delivery=${merged.delivery_status}",
                        ),
                        eventId = merged.last_event_id,
                        gid = gid,
                    )
                }
                return
            }
        }

        val message = ChatMessageEntity(
            message_id = event.id,
            gid = gid,
            text = text,
            status = ReducerConstants.STATUS_ACTIVE,
            delivery_status = DeliveryStatus.SENT,
            created_t = event.time.t,
            updated_t = event.time.t,
            edited_at = null,
            actor_did = event.actor.did,
            reply_to = replyTo,
            expires_at = expiresAt,
            last_event_id = event.id,
            last_event_t = event.time.t,
            last_event_lt = event.time.lt ?: 0L,
            last_event_actor = event.actor.did,
            received_ack_count = 0,
            received_group_ack_count = 0,
            received_ack_by = "[]",
            received_group_ack_by = "[]",
            seen_by = "{}",
        )
        chatDao.upsertMessage(message)
        replayPendingEdits(gid, event.id)
        ActivityLog.add(
            label = "Chat reducer upsert",
            source = "reducer",
            message = reducerLogMessage(
                kind = "message",
                appliedEventId = event.id,
                targetMessageId = event.id,
                row = message,
                extra = "status=${message.status} delivery=${message.delivery_status}",
            ),
            eventId = message.last_event_id,
            gid = gid,
        )
    }

    private fun applyEdit(event: EventCore, gid: String) {
        val targetId = targetId(event) ?: return
        val existing = chatDao.findMessage(gid, targetId)
        if (existing == null) {
            val provisionalCreatedT = targetCreatedT(event) ?: event.time.t
            val text = JsonAccess.stringValue(event.data, "text") ?: return
            if (text.length > 200) return
            val provisional = ChatMessageEntity(
                gid = gid,
                message_id = targetId,
                text = text,
                status = ReducerConstants.STATUS_ACTIVE,
                delivery_status = DeliveryStatus.SENT,
                created_t = provisionalCreatedT,
                updated_t = event.time.t,
                edited_at = event.time.t,
                actor_did = event.actor.did,
                reply_to = null,
                expires_at = null,
                last_event_id = event.id,
                last_event_t = event.time.t,
                last_event_lt = event.time.lt ?: 0L,
                last_event_actor = event.actor.did,
                received_ack_count = 0,
                received_group_ack_count = 0,
                received_ack_by = "[]",
                received_group_ack_by = "[]",
                seen_by = "{}",
            )
            chatDao.upsertMessage(provisional)
            ActivityLog.add(
                label = "Chat edit provisional created",
                source = "reducer",
                message = "target=$targetId created_t=$provisionalCreatedT actor=${event.actor.did}",
                eventId = event.id,
                gid = gid,
            )
            ActivityLog.add(
                label = "Chat reducer upsert",
                source = "reducer",
                message = reducerLogMessage(
                    kind = "edit_provisional",
                    appliedEventId = event.id,
                    targetMessageId = targetId,
                    row = provisional,
                    extra = "status=${provisional.status} delivery=${provisional.delivery_status}",
                ),
                eventId = provisional.last_event_id,
                gid = gid,
            )
            return
        }
        val text = JsonAccess.stringValue(event.data, "text") ?: existing.text
        if (text.length > 200) return
        if (existing.last_event_id == event.id) return
        if (existing.actor_did != event.actor.did) {
            ActivityLog.add(
                label = "Chat edit skipped",
                source = "reducer",
                message = "reason=actor_mismatch target=$targetId actor=${event.actor.did}",
                eventId = event.id,
                gid = gid,
            )
            return
        }
        if (existing.status == ReducerConstants.STATUS_DELETED) return
        val clock = eventClock(event)
        if (!EventClockComparator.isNewer(clock, existingClock(existing))) return

        val updated = existing.copy(
            text = text,
            updated_t = event.time.t,
            edited_at = event.time.t,
            actor_did = event.actor.did,
            last_event_id = event.id,
            last_event_t = event.time.t,
            last_event_lt = event.time.lt ?: 0L,
            last_event_actor = event.actor.did,
        )
        chatDao.upsertMessage(updated)
        ActivityLog.add(
            label = "Chat edit applied",
            source = "reducer",
            message = "target=$targetId actor=${event.actor.did}",
            eventId = event.id,
            gid = gid,
        )
        ActivityLog.add(
            label = "Chat reducer upsert",
            source = "reducer",
            message = reducerLogMessage(
                kind = "edit",
                appliedEventId = event.id,
                targetMessageId = targetId,
                row = updated,
                extra = "status=${updated.status} delivery=${updated.delivery_status}",
            ),
            eventId = updated.last_event_id,
            gid = gid,
        )
    }

    private fun applyDelete(event: EventCore, gid: String) {
        val targetId = targetId(event) ?: return
        val existing = chatDao.findMessage(gid, targetId)
        val targetEvent = eventsDao.findById(gid, targetId)
        val existingDelete = deletedEventDao.find(gid, targetId)
        if (existing == null && targetEvent == null && existingDelete == null) return
        val clock = eventClock(event)
        val existingClock = when {
            existing != null -> existingClock(existing)
            existingDelete != null -> deletedClock(existingDelete)
            else -> null
        }
        if (!EventClockComparator.isNewer(clock, existingClock)) return

        val targetActor = existing?.actor_did ?: targetEvent?.actor_did ?: existingDelete?.actor_did ?: return
        if (targetActor != event.actor.did) return

        val targetCreatedT = existing?.created_t ?: targetEvent?.created_t ?: existingDelete?.target_created_t
        val targetExpiresAt = existing?.expires_at ?: targetEvent?.let(::eventExpiresAt)
        val expiresAt = maxOf(
            computeDeleteTombstoneExpiry(event.time.t, targetExpiresAt),
            existingDelete?.expires_at ?: 0L,
        )
        deletedEventDao.upsert(
            DeletedEventEntity(
                gid = gid,
                target_event_id = targetId,
                delete_event_id = event.id,
                actor_did = event.actor.did,
                deleted_at = event.time.t,
                expires_at = expiresAt,
                target_created_t = targetCreatedT,
                delete_created_t = event.time.t,
                last_event_t = event.time.t,
                last_event_lt = event.time.lt ?: 0L,
                last_event_actor = event.actor.did,
            )
        )

        existing?.let {
            val updated = it.copy(
                text = DELETED_MESSAGE_TEXT,
                status = ReducerConstants.STATUS_DELETED,
                updated_t = event.time.t,
                edited_at = it.edited_at,
                last_event_id = event.id,
                last_event_t = event.time.t,
                last_event_lt = event.time.lt ?: 0L,
                last_event_actor = event.actor.did,
            )
            chatDao.upsertMessage(updated)
        }
        eventsDao.deleteByEventId(gid, targetId)
        eventsDao.deleteByTypesAndTarget(gid, targetId, listOf(EventTypes.CHAT_EDIT))
        ActivityLog.add(
            label = "Chat reducer upsert",
            source = "reducer",
            message = reducerLogMessage(
                kind = "delete",
                appliedEventId = event.id,
                targetMessageId = targetId,
                row = chatDao.findMessage(gid, targetId),
                extra = "expires_at=$expiresAt",
            ),
            eventId = event.id,
            gid = gid,
        )
    }

    private fun applyAck(event: EventCore, gid: String) {
        val ackType = JsonAccess.stringValue(event.data, "ack_type") ?: return
        val targetIds = ackTargetIds(event)
        if (targetIds.isEmpty()) return
        val actorDid = event.actor.did
        var appliedCount = 0
        when (ackType) {
            "received" -> targetIds.forEach { targetId ->
                val existing = chatDao.findMessage(gid, targetId) ?: return@forEach
                val ackBy = parseStringList(existing.received_ack_by)
                if (!ackBy.contains(actorDid)) {
                    ackBy.add(actorDid)
                }
                val groupAckBy = parseStringList(existing.received_group_ack_by)
                if (!groupAckBy.contains(actorDid)) {
                    groupAckBy.add(actorDid)
                }
                val updated = existing.copy(
                    received_ack_count = ackBy.size,
                    received_group_ack_count = groupAckBy.size,
                    received_ack_by = toJsonArray(ackBy),
                    received_group_ack_by = toJsonArray(groupAckBy),
                    delivery_status = maxDeliveryStatus(existing.delivery_status, DeliveryStatus.DELIVERED),
                    updated_t = event.time.t,
                    last_event_id = event.id,
                    last_event_t = event.time.t,
                    last_event_lt = event.time.lt ?: 0L,
                    last_event_actor = event.actor.did,
                )
                chatDao.upsertMessage(updated)
                ActivityLog.add(
                    label = "Chat reducer upsert",
                    source = "reducer",
                    message = reducerLogMessage(
                        kind = "ack_received",
                        appliedEventId = event.id,
                        targetMessageId = targetId,
                        row = updated,
                        extra = "status=${updated.status} delivery=${updated.delivery_status}",
                    ),
                    eventId = updated.last_event_id,
                    gid = gid,
                )
                appliedCount += 1
            }
            "seen" -> targetIds.forEach { targetId ->
                val existing = chatDao.findMessage(gid, targetId) ?: return@forEach
                val seenMap = parseSeenMap(existing.seen_by)
                val ts = event.time.t * 1000L
                seenMap[actorDid] = ts
                val updated = existing.copy(
                    seen_by = toJsonObject(seenMap),
                    delivery_status = DeliveryStatus.SEEN,
                    updated_t = event.time.t,
                    last_event_id = event.id,
                    last_event_t = event.time.t,
                    last_event_lt = event.time.lt ?: 0L,
                    last_event_actor = event.actor.did,
                )
                chatDao.upsertMessage(updated)
                ActivityLog.add(
                    label = "Chat reducer upsert",
                    source = "reducer",
                    message = reducerLogMessage(
                        kind = "ack_seen",
                        appliedEventId = event.id,
                        targetMessageId = targetId,
                        row = updated,
                        extra = "status=${updated.status} delivery=${updated.delivery_status}",
                    ),
                    eventId = updated.last_event_id,
                    gid = gid,
                )
                appliedCount += 1
            }
        }
        if (appliedCount > 0) {
            ActivityLog.add(
                label = if (ackType == "seen") "ACK applied seen" else "ACK applied delivered",
                source = "mesh",
                message = "targets=$appliedCount ack=${event.id} type=$ackType",
                eventId = event.id,
                gid = gid,
            )
        }
    }

    private fun targetId(event: EventCore): String? {
        return event.ref.target ?: JsonAccess.stringValue(event.data, "target_id")
    }

    private fun replyToId(event: EventCore): String? {
        return event.ref.parent ?: JsonAccess.stringValue(event.data, "reply_to")
    }

    private fun targetCreatedT(event: EventCore): Long? {
        return JsonAccess.longValue(event.data, "tct") ?: JsonAccess.longValue(event.data, "target_created_t")
    }

    private fun ackTargetIds(event: EventCore): List<String> {
        val fromArray = JsonAccess.stringListValue(event.data, "target_ids")
        if (fromArray.isNotEmpty()) return fromArray
        return targetId(event)?.let { listOf(it) } ?: emptyList()
    }

    private fun eventClock(event: EventCore): EventClock {
        return EventClock(
            t = event.time.t,
            lt = event.time.lt ?: 0L,
            actorDid = event.actor.did,
        )
    }

    private fun existingClock(existing: ChatMessageEntity): EventClock {
        return EventClock(
            t = existing.last_event_t,
            lt = existing.last_event_lt,
            actorDid = existing.last_event_actor,
        )
    }

    private fun deletedClock(existing: DeletedEventEntity): EventClock {
        return EventClock(
            t = existing.last_event_t,
            lt = existing.last_event_lt,
            actorDid = existing.last_event_actor,
        )
    }

    private fun eventExpiresAt(event: EventEntity): Long? {
        val ttlSeconds = TtlHelper.resolveTtlSeconds(event.type, event.ttl_s)
        return TtlHelper.expiresAt(event.created_t, ttlSeconds)
    }

    private fun computeDeleteTombstoneExpiry(
        nowSeconds: Long,
        targetExpiresAt: Long?,
    ): Long {
        val fromTarget = (targetExpiresAt ?: nowSeconds) + DELETE_TOMBSTONE_SAFETY_WINDOW_SECONDS
        val fromNow = nowSeconds + DELETE_TOMBSTONE_MIN_RETENTION_SECONDS
        return maxOf(fromTarget, fromNow)
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun reducerLogMessage(
        kind: String,
        appliedEventId: String,
        targetMessageId: String?,
        row: ChatMessageEntity?,
        extra: String? = null,
    ): String {
        return buildString {
            append("kind=")
            append(kind)
            append(" applied_event_id=")
            append(appliedEventId)
            append(" target_message_id=")
            append(targetMessageId ?: "none")
            append(" row_message_id=")
            append(row?.message_id ?: "none")
            append(" row_last_event_id=")
            append(row?.last_event_id ?: "none")
            if (!extra.isNullOrBlank()) {
                append(" ")
                append(extra)
            }
        }
    }

    private fun mergeBaseMessage(
        existing: ChatMessageEntity,
        event: EventCore,
        text: String,
        replyTo: String?,
        expiresAt: Long?,
    ): ChatMessageEntity {
        if (existing.actor_did != event.actor.did) {
            return existing.copy(
                text = text,
                status = ReducerConstants.STATUS_ACTIVE,
                created_t = event.time.t,
                updated_t = event.time.t,
                edited_at = null,
                actor_did = event.actor.did,
                reply_to = replyTo,
                expires_at = expiresAt,
                last_event_id = event.id,
                last_event_t = event.time.t,
                last_event_lt = event.time.lt ?: 0L,
                last_event_actor = event.actor.did,
            )
        }
        val incomingClock = eventClock(event)
        val shouldReplaceContent = EventClockComparator.isNewer(incomingClock, existingClock(existing))
        return existing.copy(
            text = if (shouldReplaceContent) text else existing.text,
            status = ReducerConstants.STATUS_ACTIVE,
            created_t = existing.created_t.takeIf { it > 0L } ?: event.time.t,
            updated_t = maxOf(existing.updated_t, event.time.t),
            actor_did = event.actor.did,
            reply_to = replyTo ?: existing.reply_to,
            expires_at = expiresAt ?: existing.expires_at,
            last_event_id = if (shouldReplaceContent) event.id else existing.last_event_id,
            last_event_t = if (shouldReplaceContent) event.time.t else existing.last_event_t,
            last_event_lt = if (shouldReplaceContent) (event.time.lt ?: 0L) else existing.last_event_lt,
            last_event_actor = if (shouldReplaceContent) event.actor.did else existing.last_event_actor,
        )
    }

    private fun replayPendingEdits(gid: String, messageId: String) {
        val pendingEdits = eventsDao.listByTypeAndTarget(gid, EventTypes.CHAT_EDIT, messageId)
        pendingEdits.forEach { event ->
            val core = runCatching {
                CanonicalJson.json.decodeFromString<EventCore>(event.payload_json)
            }.getOrNull() ?: return@forEach
            applyEdit(core, gid)
        }
    }

    private fun parseStringList(raw: String?): MutableList<String> {
        if (raw.isNullOrBlank()) return mutableListOf()
        return try {
            val element = Json.decodeFromString(JsonElement.serializer(), raw)
            val array = element as? kotlinx.serialization.json.JsonArray ?: return mutableListOf()
            array.mapNotNull { it as? JsonPrimitive }
                .mapNotNull { if (it.isString) it.content else null }
                .toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun toJsonArray(values: List<String>): String {
        val array = buildJsonArray {
            values.forEach { add(JsonPrimitive(it)) }
        }
        return Json.encodeToString(JsonElement.serializer(), array)
    }

    private fun parseSeenMap(raw: String?): MutableMap<String, Long> {
        if (raw.isNullOrBlank()) return mutableMapOf()
        return try {
            val element = Json.decodeFromString(JsonElement.serializer(), raw)
            val obj = element as? JsonObject ?: return mutableMapOf()
            obj.mapNotNull { (key, value) ->
                val primitive = value as? JsonPrimitive ?: return@mapNotNull null
                val ts = if (primitive.isString) primitive.content.toLongOrNull() else primitive.toString().toLongOrNull()
                ts?.let { key to it }
            }.toMap().toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun toJsonObject(values: Map<String, Long>): String {
        val obj = buildJsonObject {
            values.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
        return Json.encodeToString(JsonElement.serializer(), obj)
    }

    private fun maxDeliveryStatus(current: String?, next: String): String {
        val currentRank = deliveryRank(current)
        val nextRank = deliveryRank(next)
        return if (nextRank >= currentRank) next else (current ?: next)
    }

    private fun deliveryRank(value: String?): Int {
        return when (value) {
            DeliveryStatus.SEEN -> 3
            DeliveryStatus.DELIVERED -> 2
            DeliveryStatus.SENT -> 1
            else -> 0
        }
    }

}

private const val DELETE_TOMBSTONE_SAFETY_WINDOW_SECONDS = 6 * 60 * 60L
private const val DELETE_TOMBSTONE_MIN_RETENTION_SECONDS = 24 * 60 * 60L
private const val DELETED_MESSAGE_TEXT = "Deleted"
