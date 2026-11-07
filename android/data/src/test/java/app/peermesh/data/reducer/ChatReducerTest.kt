package app.peermesh.data.reducer

import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.dao.ChatDao
import app.peermesh.data.db.dao.DeletedEventDao
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.entity.ChatMessageEntity
import app.peermesh.data.db.entity.DeletedEventEntity
import app.peermesh.data.db.entity.EventEntity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ChatReducerTest {
    @Test
    fun apply_persistsReplyToForChatReply() {
        val dao = FakeChatDao()
        val deletedEventDao = Mockito.mock(DeletedEventDao::class.java)
        Mockito.`when`(deletedEventDao.findActive(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(null)
        val eventsDao = Mockito.mock(EventsDao::class.java)
        val reducer = ChatReducer(dao, deletedEventDao, eventsDao)
        val event = EventCore(
            ev = 1,
            id = "reply-event",
            type = EventTypes.CHAT_REPLY,
            actor = Actor(did = "actor-1"),
            time = EventTime(t = 1700000000L, lt = 7L),
            ref = EventRef(root = null, parent = "parent-message", target = null),
            meta = null,
            data = buildJsonObject {
                put("text", JsonPrimitive("reply body"))
            },
        )

        reducer.apply(event, "gid-1")

        val stored = dao.findMessage("gid-1", "reply-event")
        assertNotNull(stored)
        assertEquals("parent-message", stored?.reply_to)
        assertEquals("reply body", stored?.text)
    }

    @Test
    fun apply_redactsOwnMessageForChatDelete() {
        val dao = FakeChatDao()
        val original = ChatMessageEntity(
            gid = "gid-1",
            message_id = "message-1",
            text = "secret text",
            status = ReducerConstants.STATUS_ACTIVE,
            delivery_status = DeliveryStatus.SENT,
            created_t = 100L,
            updated_t = 100L,
            edited_at = null,
            actor_did = "actor-1",
            reply_to = null,
            expires_at = 200L,
            last_event_id = "message-1",
            last_event_t = 100L,
            last_event_lt = 1L,
            last_event_actor = "actor-1",
            received_ack_count = 0,
            received_group_ack_count = 0,
            received_ack_by = "[]",
            received_group_ack_by = "[]",
            seen_by = "{}",
        )
        dao.upsertMessage(original)
        val deletedEventDao = FakeDeletedEventDao()
        val eventsDao = FakeEventsDao(
            rows = listOf(
                EventEntity(
                gid = "gid-1",
                event_id = "message-1",
                type = EventTypes.CHAT_MSG,
                source = "mesh",
                created_t = 100L,
                time_lt = 1L,
                actor_did = "actor-1",
                ref_root = null,
                ref_parent = null,
                ref_target = null,
                ttl_s = 300L,
                payload_json = "{}",
                inserted_at = 100L,
                )
            )
        )
        val reducer = ChatReducer(dao, deletedEventDao, eventsDao)
        val deleteEvent = EventCore(
            ev = 1,
            id = "delete-event",
            type = EventTypes.CHAT_DEL,
            actor = Actor(did = "actor-1"),
            time = EventTime(t = 150L, lt = 2L),
            ref = EventRef(root = null, parent = null, target = "message-1"),
            meta = null,
            data = buildJsonObject {
                put("target_id", JsonPrimitive("message-1"))
            },
        )

        reducer.apply(deleteEvent, "gid-1")

        val stored = dao.findMessage("gid-1", "message-1")
        assertNotNull(stored)
        assertEquals("Deleted", stored?.text)
        assertEquals(ReducerConstants.STATUS_DELETED, stored?.status)
        assertNotNull(deletedEventDao.find("gid-1", "message-1"))
        assertEquals(listOf("gid-1" to "message-1"), eventsDao.deletedEventIds)
    }

    @Test
    fun apply_rejectsDeleteForOtherUsersMessage() {
        val dao = FakeChatDao()
        dao.upsertMessage(
            ChatMessageEntity(
                gid = "gid-1",
                message_id = "message-1",
                text = "secret text",
                status = ReducerConstants.STATUS_ACTIVE,
                delivery_status = DeliveryStatus.SENT,
                created_t = 100L,
                updated_t = 100L,
                edited_at = null,
                actor_did = "actor-1",
                reply_to = null,
                expires_at = 200L,
                last_event_id = "message-1",
                last_event_t = 100L,
                last_event_lt = 1L,
                last_event_actor = "actor-1",
                received_ack_count = 0,
                received_group_ack_count = 0,
                received_ack_by = "[]",
                received_group_ack_by = "[]",
                seen_by = "{}",
        )
        )
        val deletedEventDao = FakeDeletedEventDao()
        val eventsDao = FakeEventsDao()
        val reducer = ChatReducer(dao, deletedEventDao, eventsDao)
        val deleteEvent = EventCore(
            ev = 1,
            id = "delete-event",
            type = EventTypes.CHAT_DEL,
            actor = Actor(did = "actor-2"),
            time = EventTime(t = 150L, lt = 2L),
            ref = EventRef(root = null, parent = null, target = "message-1"),
            meta = null,
            data = buildJsonObject {
                put("target_id", JsonPrimitive("message-1"))
            },
        )

        reducer.apply(deleteEvent, "gid-1")

        assertEquals("secret text", dao.findMessage("gid-1", "message-1")?.text)
        assertNull(deletedEventDao.find("gid-1", "message-1"))
    }

    @Test
    fun apply_storesTombstoneWhenTargetMessageMissingButEventExists() {
        val dao = FakeChatDao()
        val deletedEventDao = FakeDeletedEventDao()
        val eventsDao = FakeEventsDao(
            rows = listOf(
                EventEntity(
                    gid = "gid-1",
                    event_id = "message-1",
                    type = EventTypes.CHAT_MSG,
                    source = "mesh",
                    created_t = 100L,
                    time_lt = 1L,
                    actor_did = "actor-1",
                    ref_root = null,
                    ref_parent = null,
                    ref_target = null,
                    ttl_s = 300L,
                    payload_json = "{}",
                    inserted_at = 100L,
                )
            )
        )
        val reducer = ChatReducer(dao, deletedEventDao, eventsDao)
        val deleteEvent = EventCore(
            ev = 1,
            id = "delete-event",
            type = EventTypes.CHAT_DEL,
            actor = Actor(did = "actor-1"),
            time = EventTime(t = 150L, lt = 2L),
            ref = EventRef(root = null, parent = null, target = "message-1"),
            meta = null,
            data = buildJsonObject {
                put("target_id", JsonPrimitive("message-1"))
            },
        )

        reducer.apply(deleteEvent, "gid-1")

        val tombstone = deletedEventDao.find("gid-1", "message-1")
        assertNotNull(tombstone)
        assertEquals("delete-event", tombstone?.delete_event_id)
        assertNull(dao.findMessage("gid-1", "message-1"))
        assertEquals(listOf("gid-1" to "message-1"), eventsDao.deletedEventIds)
    }

    @Test
    fun apply_deletesAssociatedChatEditEventsWhenMessageDeleted() {
        val dao = FakeChatDao()
        dao.upsertMessage(
            ChatMessageEntity(
                gid = "gid-1",
                message_id = "message-1",
                text = "secret text",
                status = ReducerConstants.STATUS_ACTIVE,
                delivery_status = DeliveryStatus.SENT,
                created_t = 100L,
                updated_t = 100L,
                edited_at = null,
                actor_did = "actor-1",
                reply_to = null,
                expires_at = 200L,
                last_event_id = "message-1",
                last_event_t = 100L,
                last_event_lt = 1L,
                last_event_actor = "actor-1",
                received_ack_count = 0,
                received_group_ack_count = 0,
                received_ack_by = "[]",
                received_group_ack_by = "[]",
                seen_by = "{}",
            )
        )
        val deletedEventDao = FakeDeletedEventDao()
        val eventsDao = FakeEventsDao(
            rows = listOf(
                EventEntity(
                    gid = "gid-1",
                    event_id = "message-1",
                    type = EventTypes.CHAT_MSG,
                    source = "mesh",
                    created_t = 100L,
                    time_lt = 1L,
                    actor_did = "actor-1",
                    ref_root = null,
                    ref_parent = null,
                    ref_target = null,
                    ttl_s = 300L,
                    payload_json = "{}",
                    inserted_at = 100L,
                ),
                EventEntity(
                    gid = "gid-1",
                    event_id = "edit-1",
                    type = EventTypes.CHAT_EDIT,
                    source = "mesh",
                    created_t = 120L,
                    time_lt = 2L,
                    actor_did = "actor-1",
                    ref_root = null,
                    ref_parent = null,
                    ref_target = "message-1",
                    ttl_s = 300L,
                    payload_json = "{}",
                    inserted_at = 120L,
                )
            )
        )
        val reducer = ChatReducer(dao, deletedEventDao, eventsDao)
        val deleteEvent = EventCore(
            ev = 1,
            id = "delete-event",
            type = EventTypes.CHAT_DEL,
            actor = Actor(did = "actor-1"),
            time = EventTime(t = 150L, lt = 2L),
            ref = EventRef(root = null, parent = null, target = "message-1"),
            meta = null,
            data = buildJsonObject {
                put("target_id", JsonPrimitive("message-1"))
            },
        )

        reducer.apply(deleteEvent, "gid-1")

        assertEquals(1, eventsDao.deletedByTypesAndTargetCalls.size)
        assertEquals("message-1", eventsDao.deletedByTypesAndTargetCalls.single().second)
        assertTrue(EventTypes.CHAT_EDIT in eventsDao.deletedByTypesAndTargetCalls.single().third)
    }

    @Test
    fun apply_rejectsEditForOtherUsersMessage() {
        val dao = FakeChatDao()
        dao.upsertMessage(
            ChatMessageEntity(
                gid = "gid-1",
                message_id = "message-1",
                text = "original",
                status = ReducerConstants.STATUS_ACTIVE,
                delivery_status = DeliveryStatus.SENT,
                created_t = 100L,
                updated_t = 100L,
                edited_at = null,
                actor_did = "actor-1",
                reply_to = null,
                expires_at = 200L,
                last_event_id = "message-1",
                last_event_t = 100L,
                last_event_lt = 1L,
                last_event_actor = "actor-1",
                received_ack_count = 0,
                received_group_ack_count = 0,
                received_ack_by = "[]",
                received_group_ack_by = "[]",
                seen_by = "{}",
            )
        )
        val reducer = ChatReducer(dao, FakeDeletedEventDao(), FakeEventsDao())
        val editEvent = EventCore(
            ev = 1,
            id = "edit-1",
            type = EventTypes.CHAT_EDIT,
            actor = Actor(did = "actor-2"),
            time = EventTime(t = 110L, lt = 2L),
            ref = EventRef(root = null, parent = null, target = "message-1"),
            meta = null,
            data = buildJsonObject {
                put("text", JsonPrimitive("tampered"))
                put("tct", JsonPrimitive(100L))
            },
        )

        reducer.apply(editEvent, "gid-1")

        val stored = dao.findMessage("gid-1", "message-1")
        assertEquals("original", stored?.text)
        assertNull(stored?.edited_at)
    }

    @Test
    fun apply_createsProvisionalEditWhenTargetMessageMissing() {
        val dao = FakeChatDao()
        val reducer = ChatReducer(dao, FakeDeletedEventDao(), FakeEventsDao())
        val editEvent = EventCore(
            ev = 1,
            id = "edit-1",
            type = EventTypes.CHAT_EDIT,
            actor = Actor(did = "actor-1"),
            time = EventTime(t = 110L, lt = 2L),
            ref = EventRef(root = null, parent = null, target = "missing-message"),
            meta = null,
            data = buildJsonObject {
                put("text", JsonPrimitive("edited"))
                put("tct", JsonPrimitive(90L))
            },
        )

        reducer.apply(editEvent, "gid-1")

        val stored = dao.findMessage("gid-1", "missing-message")
        assertNotNull(stored)
        assertEquals("edited", stored?.text)
        assertEquals(90L, stored?.created_t)
        assertEquals(110L, stored?.edited_at)
        assertEquals("edit-1", stored?.last_event_id)
    }

    @Test
    fun apply_replaysPendingEditsWhenBaseMessageArrivesLater() {
        val dao = FakeChatDao()
        val editPayload = """
            {"ev":1,"id":"edit-1","type":"CHAT.EDIT","actor":{"did":"actor-1"},"time":{"t":120,"lt":2},"ref":{"root":null,"parent":null,"target":"message-1"},"meta":null,"data":{"text":"edited text","tct":100}}
        """.trimIndent()
        val eventsDao = FakeEventsDao(
            rows = listOf(
                EventEntity(
                    gid = "gid-1",
                    event_id = "edit-1",
                    type = EventTypes.CHAT_EDIT,
                    source = "mesh",
                    created_t = 120L,
                    time_lt = 2L,
                    actor_did = "actor-1",
                    ref_root = null,
                    ref_parent = null,
                    ref_target = "message-1",
                    ttl_s = null,
                    payload_json = editPayload,
                    inserted_at = 120L,
                )
            )
        )
        val reducer = ChatReducer(dao, FakeDeletedEventDao(), eventsDao)
        val messageEvent = EventCore(
            ev = 1,
            id = "message-1",
            type = EventTypes.CHAT_MSG,
            actor = Actor(did = "actor-1"),
            time = EventTime(t = 100L, lt = 1L),
            ref = EventRef(root = null, parent = null, target = null),
            meta = null,
            data = buildJsonObject {
                put("text", JsonPrimitive("original text"))
            },
        )

        reducer.apply(messageEvent, "gid-1")

        val stored = dao.findMessage("gid-1", "message-1")
        assertEquals("edited text", stored?.text)
        assertEquals(120L, stored?.edited_at)
        assertEquals("edit-1", stored?.last_event_id)
    }

    @Test
    fun apply_mergesBaseMessageIntoExistingProvisionalEditedMessage() {
        val dao = FakeChatDao()
        dao.upsertMessage(
            ChatMessageEntity(
                gid = "gid-1",
                message_id = "message-1",
                text = "edited text",
                status = ReducerConstants.STATUS_ACTIVE,
                delivery_status = DeliveryStatus.SENT,
                created_t = 100L,
                updated_t = 120L,
                edited_at = 120L,
                actor_did = "actor-1",
                reply_to = null,
                expires_at = null,
                last_event_id = "edit-1",
                last_event_t = 120L,
                last_event_lt = 2L,
                last_event_actor = "actor-1",
                received_ack_count = 0,
                received_group_ack_count = 0,
                received_ack_by = "[]",
                received_group_ack_by = "[]",
                seen_by = "{}",
            )
        )
        val reducer = ChatReducer(dao, FakeDeletedEventDao(), FakeEventsDao())
        val messageEvent = EventCore(
            ev = 1,
            id = "message-1",
            type = EventTypes.CHAT_REPLY,
            actor = Actor(did = "actor-1"),
            time = EventTime(t = 100L, lt = 1L),
            ref = EventRef(root = null, parent = "parent-1", target = null),
            meta = null,
            data = buildJsonObject {
                put("text", JsonPrimitive("original text"))
            },
        )

        reducer.apply(messageEvent, "gid-1")

        val stored = dao.findMessage("gid-1", "message-1")
        assertEquals("edited text", stored?.text)
        assertEquals("parent-1", stored?.reply_to)
        assertEquals(120L, stored?.edited_at)
        assertEquals("edit-1", stored?.last_event_id)
        assertEquals(100L, stored?.created_t)
    }
}

private class FakeEventsDao(
    rows: List<EventEntity> = emptyList(),
) : EventsDao {
    private val events = linkedMapOf<Pair<String, String>, EventEntity>().apply {
        rows.forEach { put(it.gid to it.event_id, it) }
    }
    val deletedEventIds = mutableListOf<Pair<String, String>>()
    val deletedByTypesAndTargetCalls = mutableListOf<Triple<String, String, List<String>>>()

    override fun insert(event: EventEntity): Long = error("unused")
    override fun listPendingMapActionRootEventIds(gid: String, limit: Int): List<String> = emptyList()

    override fun loadSince(since: Long, sinceEventId: String, limit: Int): List<EventEntity> = error("unused")

    override fun loadSinceExcludingSource(
        since: Long,
        sinceEventId: String,
        limit: Int,
        source: String,
    ): List<EventEntity> = error("unused")

    override fun loadAfterRowIdExcludingSources(
        afterRowId: Long,
        limit: Int,
        sources: List<String>,
    ): List<EventEntity> = error("unused")

    override fun listRecentEventIdsForDtn(
        gid: String,
        since: Long,
        sinceEventId: String,
        limit: Int,
    ): List<String> = error("unused")

    override fun loadByEventIdsForDtn(gid: String, eventIds: List<String>): List<EventEntity> = error("unused")

    override fun loadDtnCursorWindow(
        gid: String,
        lastInsertedAt: Long,
        lastEventId: String,
        limit: Int,
    ): List<EventEntity> = error("unused")

    override fun markInvalid(gid: String, eventId: String, invalidAt: Long, reason: String) = error("unused")

    override fun deleteInvalidBefore(cutoff: Long) = error("unused")

    override fun exists(gid: String, eventId: String): Int = error("unused")

    override fun findById(gid: String, eventId: String): EventEntity? = events[gid to eventId]

    override fun listActiveActorIdsSince(gid: String, sinceCreatedT: Long): List<String> = error("unused")

    override fun deleteByEventId(gid: String, eventId: String): Int {
        deletedEventIds += gid to eventId
        return if (events.remove(gid to eventId) != null) 1 else 0
    }

    override fun listByTypeAndTarget(gid: String, type: String, targetId: String): List<EventEntity> {
        return events.values.filter { it.gid == gid && it.type == type && it.ref_target == targetId }
    }

    override fun countByType(gid: String, type: String): Int {
        return events.values.count { it.gid == gid && it.type == type && it.invalid_at == null }
    }

    override fun deleteByTypesAndTarget(gid: String, targetId: String, types: List<String>): Int {
        deletedByTypesAndTargetCalls += Triple(gid, targetId, types)
        val keys = events
            .filterValues { it.ref_target == targetId && types.contains(it.type) }
            .keys
            .toList()
        keys.forEach { events.remove(it) }
        return keys.size
    }

    override fun deleteByGid(gid: String): Int = error("unused")
}

private class FakeChatDao : ChatDao {
    private val messages = linkedMapOf<Pair<String, String>, ChatMessageEntity>()

    override fun upsertMessage(message: ChatMessageEntity) {
        messages[message.gid to message.message_id] = message
    }

    override fun findMessage(gid: String, messageId: String): ChatMessageEntity? {
        return messages[gid to messageId]
    }

    override fun findMessageByEventId(gid: String, eventId: String): ChatMessageEntity? {
        return messages[gid to eventId]
    }

    override fun updateDeliveryStatusIfNotSeen(
        gid: String,
        messageIds: List<String>,
        status: String,
        seenStatus: String,
    ) {
        messageIds.forEach { messageId ->
            val existing = messages[gid to messageId] ?: return@forEach
            if (existing.delivery_status != seenStatus) {
                messages[gid to messageId] = existing.copy(delivery_status = status)
            }
        }
    }

    override fun listMessages(gid: String, limit: Int): List<ChatMessageEntity> {
        return messages.values.filter { it.gid == gid }.takeLast(limit)
    }

    override fun listMessagesBefore(
        gid: String,
        beforeCreatedT: Long,
        beforeMessageId: String,
        limit: Int,
    ): List<ChatMessageEntity> {
        return messages.values
            .filter { message ->
                message.gid == gid &&
                    (message.created_t < beforeCreatedT ||
                        (message.created_t == beforeCreatedT && message.message_id < beforeMessageId))
            }
            .takeLast(limit)
    }

    override fun deleteByGid(gid: String): Int {
        val keys = messages.keys.filter { it.first == gid }
        keys.forEach { messages.remove(it) }
        return keys.size
    }
}

private class FakeDeletedEventDao : DeletedEventDao {
    private val entries = linkedMapOf<Pair<String, String>, DeletedEventEntity>()

    override fun upsert(entity: DeletedEventEntity) {
        entries[entity.gid to entity.target_event_id] = entity
    }

    override fun find(gid: String, targetEventId: String): DeletedEventEntity? {
        return entries[gid to targetEventId]
    }

    override fun findActive(gid: String, targetEventId: String, nowSeconds: Long): DeletedEventEntity? {
        val entity = find(gid, targetEventId) ?: return null
        return entity.takeIf { it.expires_at > nowSeconds }
    }

    override fun deleteExpired(nowSeconds: Long): Int {
        val keys = entries.filterValues { it.expires_at <= nowSeconds }.keys
        keys.forEach { entries.remove(it) }
        return keys.size
    }

    override fun deleteByGid(gid: String): Int {
        val keys = entries.keys.filter { it.first == gid }
        keys.forEach { entries.remove(it) }
        return keys.size
    }
}
