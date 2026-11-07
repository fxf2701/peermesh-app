package app.peermesh.data.relay

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.id.EventIdFactory
import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventMeta
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.relay.RelayIngestRequest
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.ingest.EventSources
import app.peermesh.transport.relay.RelayHttpClient
import app.peermesh.transport.relay.RelayIngestResult
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPushManagerCursorTest {
    @Test
    fun pushCursorByRowIdDoesNotSkipLaterChatWithSmallerEventIdInSameSecond() {
        val ackEventId = generatedEventId(1)
        val chatEventId = generatedEventId(2)
        val events = listOf(
            event(id = 10L, eventId = ackEventId, type = EventTypes.SYS_ACK, insertedAt = 100L),
            event(id = 11L, eventId = chatEventId, type = EventTypes.CHAT_MSG, insertedAt = 100L),
        )
        val eventsDao = FakeEventsDao(events)
        val ingestedEventIds = mutableListOf<String>()
        val stateStore = InMemoryRelayPushStateStore().apply {
            save(
                relayUrl = RELAY_URL,
                state = RelayPushState(
                    lastPushRowId = 10L,
                    lastPushAt = 100L,
                    lastPushEventId = ackEventId,
                )
            )
        }
        val manager = RelayPushManager(
            relayUrl = RELAY_URL,
            eventsDao = eventsDao,
            chatDao = null,
            outboundBatchDao = null,
            relayClient = RelayHttpClient(baseUrl = RELAY_URL),
            mapper = RelayEventMapper(groupKeyLoader = { ByteArray(32) { 7 } }),
            stateStore = stateStore,
            ingestTransport = { request ->
                ingestedEventIds += request.events.map { it.eid }
                RelayIngestResult(
                    success = true,
                    statusCode = 200,
                    accepted = request.events.size,
                    rejected = 0,
                    errorMessage = null,
                    errorCode = null,
                    rejectedReasons = emptyList(),
                )
            },
        )

        val report = manager.pushNewEvents()

        assertTrue(report.success)
        assertEquals(1, report.attempted)
        assertEquals(1, ingestedEventIds.size)
        assertEquals(11L, stateStore.load(RELAY_URL)?.lastPushRowId)
    }

    @Test
    fun meshOriginatedChatIsBridgedButRelayAndDtnSourcesAreIgnored() {
        val events = listOf(
            event(id = 1L, eventId = generatedEventId(1), type = EventTypes.CHAT_MSG, insertedAt = 100L, source = EventSources.RELAY),
            event(id = 2L, eventId = generatedEventId(2), type = EventTypes.CHAT_MSG, insertedAt = 101L, source = EventSources.DTN),
            event(id = 3L, eventId = generatedEventId(3), type = EventTypes.CHAT_MSG, insertedAt = 102L, source = EventSources.MESH),
        )
        val eventsDao = FakeEventsDao(events)
        val ingestedEventIds = mutableListOf<String>()
        val stateStore = InMemoryRelayPushStateStore()
        val manager = RelayPushManager(
            relayUrl = RELAY_URL,
            eventsDao = eventsDao,
            chatDao = null,
            outboundBatchDao = null,
            relayClient = RelayHttpClient(baseUrl = RELAY_URL),
            mapper = RelayEventMapper(groupKeyLoader = { ByteArray(32) { 7 } }),
            stateStore = stateStore,
            ingestTransport = { request ->
                ingestedEventIds += request.events.map { it.eid }
                RelayIngestResult(
                    success = true,
                    statusCode = 200,
                    accepted = request.events.size,
                    rejected = 0,
                    errorMessage = null,
                    errorCode = null,
                    rejectedReasons = emptyList(),
                )
            },
        )

        val report = manager.pushNewEvents()

        assertTrue(report.success)
        assertEquals(1, report.attempted)
        assertEquals(1, ingestedEventIds.size)
        assertEquals(3L, stateStore.load(RELAY_URL)?.lastPushRowId)
    }

    @Test
    fun nonBridgeableBleEventAdvancesCursorWithoutRepeatedAttempts() {
        val events = listOf(
            event(id = 20L, eventId = generatedEventId(20), type = EventTypes.SYS_GROUP, insertedAt = 200L, source = EventSources.MESH),
        )
        val eventsDao = FakeEventsDao(events)
        val stateStore = InMemoryRelayPushStateStore()
        val manager = RelayPushManager(
            relayUrl = RELAY_URL,
            eventsDao = eventsDao,
            chatDao = null,
            outboundBatchDao = null,
            relayClient = RelayHttpClient(baseUrl = RELAY_URL),
            mapper = RelayEventMapper(groupKeyLoader = { ByteArray(32) { 7 } }),
            stateStore = stateStore,
            ingestTransport = { request ->
                RelayIngestResult(
                    success = true,
                    statusCode = 200,
                    accepted = request.events.size,
                    rejected = 0,
                    errorMessage = null,
                    errorCode = null,
                    rejectedReasons = emptyList(),
                )
            },
        )

        val firstReport = manager.pushNewEvents()
        val secondReport = manager.pushNewEvents()

        assertTrue(firstReport.success)
        assertEquals(0, firstReport.attempted)
        assertEquals(20L, stateStore.load(RELAY_URL)?.lastPushRowId)
        assertTrue(secondReport.success)
        assertEquals(0, secondReport.attempted)
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
        ): List<EventEntity> {
            return rows
                .filter { it.source !in sources }
                .filter { it.invalid_at == null }
                .filter { it.id > afterRowId }
                .sortedBy { it.id }
                .take(limit)
        }

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

        override fun deleteInvalidBefore(cutoff: Long) = Unit

        override fun exists(gid: String, eventId: String): Int = error("unused")

        override fun findById(gid: String, eventId: String): EventEntity? = error("unused")

        override fun listActiveActorIdsSince(gid: String, sinceCreatedT: Long): List<String> = error("unused")

        override fun deleteByEventId(gid: String, eventId: String): Int = error("unused")

        override fun listByTypeAndTarget(gid: String, type: String, targetId: String): List<EventEntity> = error("unused")

        override fun countByType(gid: String, type: String): Int = error("unused")

        override fun deleteByTypesAndTarget(gid: String, targetId: String, types: List<String>): Int = error("unused")

        override fun deleteByGid(gid: String): Int = error("unused")
    }

    private fun event(
        id: Long,
        eventId: String,
        type: String,
        insertedAt: Long,
        source: String = EventSources.MESH,
    ): EventEntity {
        val core = EventCore(
            ev = 1,
            id = eventId,
            type = type,
            actor = Actor(did = "actor"),
            time = EventTime(t = 100L, lt = id),
            ref = EventRef(root = null, parent = null, target = null),
            meta = EventMeta(kid = "kid-1"),
        )
        return EventEntity(
            id = id,
            gid = "g",
            event_id = eventId,
            type = type,
            source = source,
            created_t = 100L,
            time_lt = id,
            actor_did = "actor",
            ref_root = null,
            ref_parent = null,
            ref_target = null,
            ttl_s = null,
            payload_json = CanonicalJson.json.encodeToString(EventCore.serializer(), core),
            inserted_at = insertedAt,
        )
    }

    companion object {
        private const val RELAY_URL = "https://relay.example"

        private fun generatedEventId(seed: Int): String {
            return EventIdFactory.generate(
                gid = "g",
                deviceId = "device-$seed",
                ts = 100L,
                nonce = ByteArray(16) { seed.toByte() },
            )
        }
    }
}
