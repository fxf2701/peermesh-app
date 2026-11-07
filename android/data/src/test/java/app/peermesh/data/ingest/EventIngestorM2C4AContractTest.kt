package app.peermesh.data.ingest

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
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.data.map.processing.MapEventRejectionReason
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EventIngestorM2C4AContractTest {
    @After fun cleanGlobals() {
        ActivityLog.setEnabled(false)
    }

    @Test fun remoteMapEvent_routesOnceWithOriginalWireInputs_andNeverUsesLegacyInsertTransaction() {
        val fixture = fixture()
        val original = mapCore(
            id = "map-add",
            actor = "remote-owner",
            timestamp = 9_999_999_999L,
            lamport = 47L,
        )
        var calls = 0
        lateinit var captured: MapEventProcessingRequest
        val ingestor = EventIngestor(
            db = fixture.db,
            localActorProvider = { "local-did" },
            mapEventProcessor = {
                calls += 1
                captured = it
                MapEventProcessingResult.Accepted(it.core.id)
            },
        )

        assertTrue(ingestor.ingest(envelope(original.id), original, EventSources.RELAY))

        assertEquals(1, calls)
        assertEquals("g", captured.gid)
        assertSame(original, captured.core)
        assertEquals(9_999_999_999L, captured.core.time.t)
        assertEquals(47L, captured.core.time.lt)
        assertEquals("remote-owner", captured.core.actor.did)
        assertEquals("map-add", captured.core.id)
        assertEquals("local-did", captured.localActorDid)
        assertEquals(EventSources.RELAY, captured.source)
        assertTrue(captured.insertedAtSeconds > 0)
        Mockito.verifyNoInteractions(fixture.eventsDao)
    }

    @Test fun acceptedAndAcceptedNoChange_areNewCommittedEvents_markDedupeAndNotify() {
        listOf<MapEventProcessingResult>(
            MapEventProcessingResult.Accepted("accepted"),
            MapEventProcessingResult.AcceptedNoChange("accepted-no-change"),
        ).forEach { processorResult ->
            val fixture = fixture()
            val core = mapCore(processorResult.eventId())
            val notified = CountDownLatch(1)
            val listener = object : IngestNotifier.Listener {
                override fun onEventInserted(gid: String, eventId: String) = notified.countDown()
            }
            IngestNotifier.add(listener)
            try {
                val ingestor = EventIngestor(fixture.db, mapEventProcessor = { processorResult })
                assertTrue(ingestor.ingest(envelope(core.id), core))
                assertTrue(notified.await(2, TimeUnit.SECONDS))
                assertEquals(core.id, fixture.dedupeDao.marks.single().event_id)
                Mockito.verifyNoInteractions(fixture.eventsDao)
            } finally {
                IngestNotifier.remove(listener)
            }
        }
    }

    @Test fun deferredMapEvent_marksDedupeAndRequestsCanonicalRepair() {
        val fixture = fixture()
        val core = mapCore("confirm-with-missing-add", type = EventTypes.MAP_ITEM_CONFIRM)
        val notified = CountDownLatch(1)
        val listener = object : IngestNotifier.Listener {
            override fun onEventInserted(gid: String, eventId: String) = Unit

            override fun onCanonicalMapRepairRequested(gid: String, rootEventId: String) {
                if (gid == "g" && rootEventId == "missing-add") notified.countDown()
            }
        }
        IngestNotifier.add(listener)
        try {
            val ingestor = EventIngestor(
                fixture.db,
                mapEventProcessor = {
                    MapEventProcessingResult.AcceptedDeferred(it.core.id, "missing-add")
                },
            )
            assertTrue(ingestor.ingest(envelope(core.id), core))
            assertTrue(notified.await(2, TimeUnit.SECONDS))
            assertEquals(core.id, fixture.dedupeDao.marks.single().event_id)
            Mockito.verifyNoInteractions(fixture.eventsDao)
        } finally {
            IngestNotifier.remove(listener)
        }
    }

    @Test fun exactAndConflictingDuplicates_areFalse_unmarked_andDistinctlyLogged() {
        ActivityLog.setEnabled(true)
        ActivityLog.startCapture(emptyList())
        val notified = CountDownLatch(1)
        val listener = object : IngestNotifier.Listener {
            override fun onEventInserted(gid: String, eventId: String) = notified.countDown()
        }
        IngestNotifier.add(listener)
        val outcomes = listOf(
            MapEventProcessingResult.ExactDuplicate("exact") to "DB event duplicate drop",
            MapEventProcessingResult.ConflictingDuplicate("conflict") to "DB event conflicting duplicate",
        )
        try {
            outcomes.forEach { (result, expectedLabel) ->
                val fixture = fixture()
                val core = mapCore(result.eventId())
                assertFalse(EventIngestor(fixture.db, mapEventProcessor = { result }).ingest(envelope(core.id), core))
                assertTrue(fixture.dedupeDao.marks.isEmpty())
                assertTrue(ActivityLog.snapshot().any { it.label == expectedLabel && it.eventId == core.id })
            }
            assertFalse(notified.await(200, TimeUnit.MILLISECONDS))
        } finally {
            IngestNotifier.remove(listener)
        }
    }

    @Test fun rejection_invalidState_andPersistenceFailure_areFalse_unmarked_andDistinct() {
        ActivityLog.setEnabled(true)
        ActivityLog.startCapture(emptyList())
        val notified = CountDownLatch(1)
        val listener = object : IngestNotifier.Listener {
            override fun onEventInserted(gid: String, eventId: String) = notified.countDown()
        }
        IngestNotifier.add(listener)
        val outcomes = listOf(
            MapEventProcessingResult.Rejected("rejected", MapEventRejectionReason.OWNER_REQUIRED) to "Map event semantic rejection",
            MapEventProcessingResult.InvalidPersistedState("invalid", "broken projection") to "Map event invalid persisted state",
            MapEventProcessingResult.PersistenceFailure("failed", IllegalStateException("disk")) to "Map event persistence failure",
        )
        try {
            outcomes.forEach { (result, expectedLabel) ->
                val fixture = fixture()
                val core = mapCore(result.eventId())
                assertFalse(EventIngestor(fixture.db, mapEventProcessor = { result }).ingest(envelope(core.id), core))
                assertTrue(fixture.dedupeDao.marks.isEmpty())
                assertTrue(ActivityLog.snapshot().any { it.label == expectedLabel && it.eventId == core.id })
            }
            assertFalse(notified.await(200, TimeUnit.MILLISECONDS))
        } finally {
            IngestNotifier.remove(listener)
        }
        assertTrue(ActivityLog.snapshot().any {
            it.label == "Map event semantic rejection" && it.message?.contains("reason=owner_required") == true
        })
    }

    @Test fun dedicatedRemoteAction_usesTheSameSharedProcessor() {
        val fixture = fixture()
        val core = mapCore("action", type = EventTypes.MAP_ITEM_CONFIRM)
        var routedType: String? = null
        val accepted = EventIngestor(fixture.db, mapEventProcessor = {
            routedType = it.core.type
            MapEventProcessingResult.Accepted(it.core.id)
        }).ingest(envelope(core.id), core)
        assertTrue(accepted)
        assertEquals(EventTypes.MAP_ITEM_CONFIRM, routedType)
    }

    @Test fun everyCanonicalMapOperation_usesTheSharedProcessor() {
        listOf(
            EventTypes.MAP_MARKER_ADD,
            EventTypes.MAP_MARKER_UPD,
            EventTypes.MAP_MARKER_DEL,
            EventTypes.MAP_AREA_ADD,
            EventTypes.MAP_AREA_UPD,
            EventTypes.MAP_AREA_DEL,
        ).forEachIndexed { index, type ->
            val fixture = fixture()
            val core = mapCore("canonical-$index", type = type)
            var calls = 0
            assertTrue(EventIngestor(fixture.db, mapEventProcessor = {
                calls += 1
                MapEventProcessingResult.Accepted(it.core.id)
            }).ingest(envelope(core.id), core))
            assertEquals(1, calls)
            Mockito.verifyNoInteractions(fixture.eventsDao)
        }
    }

    @Test fun mapper_isPureAndExhaustiveForAllProcessorVariants() {
        val failure = IllegalStateException("failure")
        assertTrue(DefaultRemoteMapEventResultMapper.map(MapEventProcessingResult.Accepted("a")) is RemoteMapIngestOutcome.Accepted)
        assertTrue(DefaultRemoteMapEventResultMapper.map(MapEventProcessingResult.AcceptedNoChange("b")) is RemoteMapIngestOutcome.AcceptedNoChange)
        assertTrue(DefaultRemoteMapEventResultMapper.map(MapEventProcessingResult.AcceptedDeferred("deferred", "root")) is RemoteMapIngestOutcome.AcceptedDeferred)
        assertTrue(DefaultRemoteMapEventResultMapper.map(MapEventProcessingResult.ExactDuplicate("c")) is RemoteMapIngestOutcome.ExactDuplicate)
        assertTrue(DefaultRemoteMapEventResultMapper.map(MapEventProcessingResult.ConflictingDuplicate("d")) is RemoteMapIngestOutcome.ConflictingDuplicate)
        assertTrue(DefaultRemoteMapEventResultMapper.map(MapEventProcessingResult.Rejected("e", MapEventRejectionReason.OWNER_REQUIRED)) is RemoteMapIngestOutcome.Rejected)
        assertTrue(DefaultRemoteMapEventResultMapper.map(MapEventProcessingResult.InvalidPersistedState("f", "detail")) is RemoteMapIngestOutcome.InvalidPersistedState)
        val mappedFailure = DefaultRemoteMapEventResultMapper.map(MapEventProcessingResult.PersistenceFailure("g", failure))
        assertSame(failure, (mappedFailure as RemoteMapIngestOutcome.PersistenceFailure).cause)
    }

    private data class Fixture(
        val db: AppDatabase,
        val eventsDao: EventsDao,
        val dedupeDao: TrackingDedupeDao,
    )

    private fun fixture(): Fixture {
        val db = Mockito.mock(AppDatabase::class.java)
        val eventsDao = Mockito.mock(EventsDao::class.java)
        val dedupeDao = TrackingDedupeDao()
        Mockito.`when`(db.eventsDao()).thenReturn(eventsDao)
        Mockito.`when`(db.eventDedupeDao()).thenReturn(dedupeDao)
        Mockito.`when`(db.chatDao()).thenReturn(Mockito.mock(ChatDao::class.java))
        Mockito.`when`(db.deletedEventDao()).thenReturn(Mockito.mock(DeletedEventDao::class.java))
        Mockito.`when`(db.groupDao()).thenReturn(Mockito.mock(GroupDao::class.java))
        Mockito.`when`(db.userProfileDao()).thenReturn(Mockito.mock(UserProfileDao::class.java))
        Mockito.`when`(db.removedMemberDao()).thenReturn(Mockito.mock(RemovedMemberDao::class.java))
        Mockito.`when`(db.liveTrailDao()).thenReturn(Mockito.mock(LiveTrailDao::class.java))
        return Fixture(db, eventsDao, dedupeDao)
    }

    private class TrackingDedupeDao : EventDedupeDao {
        val marks = mutableListOf<EventDedupeEntity>()
        override fun upsert(entity: EventDedupeEntity) { marks += entity }
        override fun countActive(gid: String, eventId: String, nowSeconds: Long) = 0
        override fun deleteExpired(nowSeconds: Long) = 0
        override fun deleteByGid(gid: String) = 0
    }

    private fun mapCore(
        id: String,
        actor: String = "owner",
        timestamp: Long = 100L,
        lamport: Long = 3L,
        type: String = EventTypes.MAP_MARKER_ADD,
    ) = EventCore(
        ev = 1,
        id = id,
        type = type,
        actor = Actor(actor),
        time = EventTime(timestamp, lamport),
        ref = EventRef(target = "marker-1"),
        meta = null,
        data = buildJsonObject { put("marker_id", "marker-1") },
    )

    private fun envelope(id: String) = EventEnvelope(
        v = 1,
        gid = "g",
        mid = id,
        ts = 100L,
        ttl = 60,
        enc = EncPayload("ciphertext"),
        sig = null,
    )

    private fun MapEventProcessingResult.eventId(): String = when (this) {
        is MapEventProcessingResult.Accepted -> eventId
        is MapEventProcessingResult.AcceptedNoChange -> eventId
        is MapEventProcessingResult.AcceptedDeferred -> eventId
        is MapEventProcessingResult.ExactDuplicate -> eventId
        is MapEventProcessingResult.ConflictingDuplicate -> eventId
        is MapEventProcessingResult.Rejected -> requireNotNull(eventId)
        is MapEventProcessingResult.InvalidPersistedState -> eventId
        is MapEventProcessingResult.PersistenceFailure -> requireNotNull(eventId)
    }
}
