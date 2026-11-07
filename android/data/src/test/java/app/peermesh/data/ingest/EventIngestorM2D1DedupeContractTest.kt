package app.peermesh.data.ingest

import app.peermesh.core.cache.ReplayCache
import app.peermesh.core.model.Actor
import app.peermesh.core.model.EncPayload
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.dao.ChatDao
import app.peermesh.data.db.dao.DeletedEventDao
import app.peermesh.data.db.dao.EventDedupeDao
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.dao.GroupDao
import app.peermesh.data.db.dao.LiveTrailDao
import app.peermesh.data.db.dao.RemovedMemberDao
import app.peermesh.data.db.dao.UserProfileDao
import app.peermesh.data.db.entity.EventDedupeEntity
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.data.map.processing.MapEventRejectionReason
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class EventIngestorM2D1DedupeContractTest {
    @Test
    fun mapEventBypassesVolatileReplayCache() {
        val fixture = fixture()
        val cache = ReplayCache(86_400)
        cache.seen("g:map", nowSeconds())
        var processorCalls = 0
        val ingestor = EventIngestor(fixture.db, replayCache = cache, mapEventProcessor = {
            processorCalls += 1
            MapEventProcessingResult.Accepted(it.core.id)
        })

        assertTrue(ingestor.ingest(envelope("map"), mapCore("map")))
        assertEquals(1, processorCalls)
        assertEquals(0, fixture.dedupe.countCalls)
    }

    @Test
    fun mapEventBypassesIdOnlyDurableDedupeFastPath() {
        val fixture = fixture(activeDedupeIds = setOf("map"))
        var processorCalls = 0
        val ingestor = EventIngestor(fixture.db, mapEventProcessor = {
            processorCalls += 1
            MapEventProcessingResult.AcceptedNoChange(it.core.id)
        })

        assertTrue(ingestor.ingest(envelope("map"), mapCore("map")))
        assertEquals(1, processorCalls)
        assertEquals(0, fixture.dedupe.countCalls)
    }

    @Test
    fun exactMapDuplicateIsReportedByPersistentMapProcessor() {
        val fixture = fixture()
        var processorCalls = 0
        val ingestor = EventIngestor(fixture.db, mapEventProcessor = {
            processorCalls += 1
            MapEventProcessingResult.ExactDuplicate(it.core.id)
        })

        assertFalse(ingestor.ingest(envelope("exact"), mapCore("exact")))
        assertEquals(1, processorCalls)
        assertTrue(fixture.dedupe.marks.isEmpty())
    }

    @Test
    fun conflictingMapDuplicateIsReportedByPersistentMapProcessor() {
        val fixture = fixture(activeDedupeIds = setOf("conflict"))
        var processorCalls = 0
        val ingestor = EventIngestor(fixture.db, mapEventProcessor = {
            processorCalls += 1
            MapEventProcessingResult.ConflictingDuplicate(it.core.id)
        })

        assertFalse(ingestor.ingest(envelope("conflict"), mapCore("conflict")))
        assertEquals(1, processorCalls)
        assertEquals(0, fixture.dedupe.countCalls)
        assertTrue(fixture.dedupe.marks.isEmpty())
    }

    @Test
    fun rejectedMapEventIsNotMarkedAsAcceptedReplayCacheState() {
        val fixture = fixture()
        var processorCalls = 0
        val ingestor = EventIngestor(fixture.db, mapEventProcessor = {
            processorCalls += 1
            if (processorCalls == 1) {
                MapEventProcessingResult.Rejected(it.core.id, MapEventRejectionReason.OWNER_REQUIRED)
            } else {
                MapEventProcessingResult.Accepted(it.core.id)
            }
        })

        assertFalse(ingestor.ingest(envelope("retry"), mapCore("retry")))
        assertTrue(ingestor.ingest(envelope("retry"), mapCore("retry")))
        assertEquals(2, processorCalls)
        assertEquals(listOf("retry"), fixture.dedupe.marks.map { it.event_id })
    }

    @Test
    fun nonMapEventStillUsesExistingHybridDedupePath() {
        val memoryFixture = fixture()
        val cache = ReplayCache(86_400)
        cache.seen("g:chat-memory", nowSeconds())
        val memoryIngestor = EventIngestor(memoryFixture.db, replayCache = cache)
        assertFalse(memoryIngestor.ingest(envelope("chat-memory"), chatCore("chat-memory")))
        assertEquals(0, memoryFixture.dedupe.countCalls)

        val durableFixture = fixture(activeDedupeIds = setOf("chat-durable"))
        val durableIngestor = EventIngestor(durableFixture.db)
        assertFalse(durableIngestor.ingest(envelope("chat-durable"), chatCore("chat-durable")))
        assertEquals(1, durableFixture.dedupe.countCalls)
        Mockito.verifyNoInteractions(memoryFixture.eventsDao, durableFixture.eventsDao)
    }

    private data class Fixture(
        val db: AppDatabase,
        val eventsDao: EventsDao,
        val dedupe: TrackingDedupeDao,
    )

    private fun fixture(activeDedupeIds: Set<String> = emptySet()): Fixture {
        val db = Mockito.mock(AppDatabase::class.java)
        val eventsDao = Mockito.mock(EventsDao::class.java)
        val dedupe = TrackingDedupeDao(activeDedupeIds)
        Mockito.`when`(db.eventsDao()).thenReturn(eventsDao)
        Mockito.`when`(db.eventDedupeDao()).thenReturn(dedupe)
        Mockito.`when`(db.chatDao()).thenReturn(Mockito.mock(ChatDao::class.java))
        Mockito.`when`(db.deletedEventDao()).thenReturn(Mockito.mock(DeletedEventDao::class.java))
        Mockito.`when`(db.groupDao()).thenReturn(Mockito.mock(GroupDao::class.java))
        Mockito.`when`(db.userProfileDao()).thenReturn(Mockito.mock(UserProfileDao::class.java))
        Mockito.`when`(db.removedMemberDao()).thenReturn(Mockito.mock(RemovedMemberDao::class.java))
        Mockito.`when`(db.liveTrailDao()).thenReturn(Mockito.mock(LiveTrailDao::class.java))
        return Fixture(db, eventsDao, dedupe)
    }

    private class TrackingDedupeDao(private val activeIds: Set<String>) : EventDedupeDao {
        var countCalls = 0
        val marks = mutableListOf<EventDedupeEntity>()
        override fun upsert(entity: EventDedupeEntity) { marks += entity }
        override fun countActive(gid: String, eventId: String, nowSeconds: Long): Int {
            countCalls += 1
            return if (eventId in activeIds) 1 else 0
        }
        override fun deleteExpired(nowSeconds: Long) = 0
        override fun deleteByGid(gid: String) = 0
    }

    private fun mapCore(id: String) = EventCore(
        ev = 1,
        id = id,
        type = EventTypes.MAP_MARKER_DEL,
        actor = Actor("did:actor"),
        time = EventTime(1_700_000_000L, 7L),
        ref = EventRef(target = "marker-1"),
        data = buildJsonObject {},
    )

    private fun chatCore(id: String) = EventCore(
        ev = 1,
        id = id,
        type = EventTypes.CHAT_MSG,
        actor = Actor("did:actor"),
        time = EventTime(nowSeconds(), 8L),
        ref = EventRef(),
        data = buildJsonObject { put("text", "hello") },
    )

    private fun envelope(id: String) = EventEnvelope(
        v = 1,
        gid = "g",
        mid = id,
        ts = 1_700_000_000L,
        ttl = 60,
        enc = EncPayload("ciphertext"),
    )

    private fun nowSeconds() = System.currentTimeMillis() / 1_000L
}
