package app.peermesh.data.dtn

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.transport.DtnTransportPolicy
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDtnInventoryStoreTest {
    @Test
    fun listInventoryMapsEventRowsToDtnItems() {
        val store = RoomDtnInventoryStore(
            eventsDao = FakeEventsDao(
                rows = listOf(
                    entity("g", "event-a", EventTypes.CHAT_MSG, createdAt = 100, insertedAt = 110),
                    entity("g", "event-b", EventTypes.MAP_MARKER_ADD, createdAt = 200, insertedAt = 210),
                )
            ),
            groupKeyLoader = { null },
        )

        val inventory = store.listInventory("g", limit = 10)

        assertEquals(listOf("event-a", "event-b"), inventory.map { it.eventId })
        assertEquals(listOf(DtnTransportPolicy.PRIORITY_CHAT, DtnTransportPolicy.PRIORITY_MAP), inventory.map { it.priority })
        assertEquals(listOf(100L, 200L), inventory.map { it.createdAtSeconds })
        assertEquals(listOf(110L, 210L), inventory.map { it.insertedAtSeconds })
    }

    @Test
    fun existingEventIdsReturnsOnlyLocalMatches() {
        val store = RoomDtnInventoryStore(
            eventsDao = FakeEventsDao(
                rows = listOf(
                    entity("g", "event-a", EventTypes.CHAT_MSG),
                    entity("g", "event-b", EventTypes.CHAT_MSG),
                    entity("other", "event-c", EventTypes.CHAT_MSG),
                )
            ),
            groupKeyLoader = { null },
        )

        val existing = store.existingEventIds("g", listOf("event-a", "event-c", "missing"))

        assertEquals(setOf("event-a"), existing)
    }

    @Test
    fun loadDataServesExactDurableEventOutsideCurrentInventoryWindow() {
        val oldRoot = entity(
            gid = "g",
            eventId = "canonical-add-root",
            type = EventTypes.MAP_MARKER_ADD,
            createdAt = 100,
            insertedAt = 100,
        )
        val newerEvent = entity(
            gid = "g",
            eventId = "newer-event",
            type = EventTypes.CHAT_MSG,
            createdAt = 500,
            insertedAt = 500,
        )
        val groupKey = ByteArray(32) { (it + 1).toByte() }
        val store = RoomDtnInventoryStore(
            eventsDao = FakeEventsDao(rows = listOf(oldRoot, newerEvent)),
            groupKeyLoader = { gid -> groupKey.takeIf { gid == "g" } },
        )

        // A forward-only inventory cursor has already moved beyond the ADD, so the ADD is not
        // discoverable from the current inventory window. Exact-ID repair must still load it.
        val currentWindow = store.listInventoryAfter(
            gid = "g",
            lastInsertedAt = 400,
            lastEventId = "",
            limit = 10,
        )
        assertEquals(listOf("newer-event"), currentWindow.map { it.eventId })

        val data = store.loadData(
            gid = "g",
            eventIds = listOf("canonical-add-root"),
            limit = 10,
        )

        assertEquals(1, data.size)
        assertEquals("canonical-add-root", data.single().eventId)
        assertTrue(data.single().envelope.isNotBlank())
    }

    @Test
    fun loadDataPreservesRequestedOrderAndSkipsMissingKeys() {
        val store = RoomDtnInventoryStore(
            eventsDao = FakeEventsDao(
                rows = listOf(
                    entity("g", "event-a", EventTypes.CHAT_MSG),
                    entity("g", "event-b", EventTypes.CHAT_MSG),
                )
            ),
            groupKeyLoader = { null },
        )

        val data = store.loadData("g", listOf("event-b", "missing", "event-a"), limit = 10)

        assertTrue(data.isEmpty())
    }

    private class FakeEventsDao(
        private val rows: List<EventEntity>,
    ) : EventsDao {
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

        override fun loadByEventIdsForDtn(gid: String, eventIds: List<String>): List<EventEntity> {
            val requested = eventIds.toSet()
            return rows.filter { it.gid == gid && it.event_id in requested }
        }

        override fun loadDtnCursorWindow(
            gid: String,
            lastInsertedAt: Long,
            lastEventId: String,
            limit: Int,
        ): List<EventEntity> {
            return rows
                .filter { it.gid == gid }
                .filter { it.inserted_at > lastInsertedAt || (it.inserted_at == lastInsertedAt && it.event_id > lastEventId) }
                .sortedWith(compareBy<EventEntity> { it.inserted_at }.thenBy { it.event_id })
                .take(limit)
        }

        override fun markInvalid(gid: String, eventId: String, invalidAt: Long, reason: String) = error("unused")

        override fun deleteInvalidBefore(cutoff: Long): Unit = error("unused")

        override fun exists(gid: String, eventId: String): Int = error("unused")

        override fun findById(gid: String, eventId: String): EventEntity? = error("unused")

        override fun listActiveActorIdsSince(gid: String, sinceCreatedT: Long): List<String> = error("unused")

        override fun deleteByEventId(gid: String, eventId: String): Int = error("unused")

        override fun listByTypeAndTarget(gid: String, type: String, targetId: String): List<EventEntity> = error("unused")

        override fun countByType(gid: String, type: String): Int = error("unused")

        override fun deleteByTypesAndTarget(gid: String, targetId: String, types: List<String>): Int = error("unused")

        override fun deleteByGid(gid: String): Int = error("unused")
    }

    private fun entity(
        gid: String,
        eventId: String,
        type: String,
        createdAt: Long = 100,
        insertedAt: Long = 110,
    ): EventEntity {
        val core = EventCore(
            ev = 1,
            id = eventId,
            type = type,
            actor = Actor(did = "actor"),
            time = EventTime(t = createdAt, lt = 1),
            ref = EventRef(root = null, parent = null, target = null),
        )
        return EventEntity(
            gid = gid,
            event_id = eventId,
            type = type,
            source = "mesh",
            created_t = createdAt,
            time_lt = 1,
            actor_did = "actor",
            ref_root = null,
            ref_parent = null,
            ref_target = null,
            ttl_s = null,
            payload_json = CanonicalJson.json.encodeToString(EventCore.serializer(), core),
            inserted_at = insertedAt,
        )
    }
}
