package app.peermesh.data.relay

import app.peermesh.core.factory.EventFactory
import app.peermesh.core.relay.RelayEvent
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.transport.relay.RelayHttpClient
import app.peermesh.transport.relay.RelayIngestResult
import app.peermesh.security.crypto.RelayPayloadCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPushManagerM2D2ContractTest {
    @Test
    fun localAndMeshAcceptedCanonicalAndDedicatedEventsUseExistingRelayPolicy() {
        val rows = listOf(
            M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.markerAdd(), 1, M2D2RelayMapFixtures.LOCAL),
            M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.confirmation(), 2, M2D2RelayMapFixtures.MESH),
            M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.areaAdd(), 3, M2D2RelayMapFixtures.MESH),
        )
        val harness = Harness(rows)

        val report = harness.manager.pushNewEvents()

        assertTrue(report.success)
        assertEquals(rows.map { it.event_id }.toSet(), harness.sentCoreIds().toSet())
        assertEquals(3, report.attempted)
    }

    @Test
    fun relayAndDtnOriginEventsAreNotPushedBackToRelay() {
        val local = M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.markerAdd("local"), 1, M2D2RelayMapFixtures.LOCAL)
        val relay = M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.markerAdd("relay"), 2, M2D2RelayMapFixtures.RELAY)
        val dtn = M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.confirmation("dtn"), 3, M2D2RelayMapFixtures.DTN)
        val harness = Harness(listOf(local, relay, dtn))

        harness.manager.pushNewEvents()

        assertEquals(listOf(local.event_id), harness.sentCoreIds())
    }

    @Test
    fun rejectedRolledBackExactAndConflictingDuplicatesAreNotNewPushCandidates() {
        val accepted = M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.markerAdd(), 1)
        val rejectedConflict = M2D2RelayMapFixtures.entity(
            M2D2RelayMapFixtures.conflicting(M2D2RelayMapFixtures.markerAdd()),
            rowId = 2,
            invalidAt = 1_700_000_100L,
        )
        val harness = Harness(listOf(accepted, rejectedConflict))

        harness.manager.pushNewEvents()

        assertEquals(listOf(accepted.event_id), harness.sentCoreIds())
        assertFalse(harness.dao.returnedRows.any { it.invalid_at != null })
    }

    @Test
    fun pushFailureDoesNotAdvanceSemanticStateOrMutateCommittedEvents() {
        val committed = M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.markerUpdate(), 7)
        val harness = Harness(listOf(committed), failCalls = 1)

        val report = harness.manager.pushNewEvents()

        assertFalse(report.success)
        assertEquals(committed, harness.dao.rows.single())
        assertEquals(0L, harness.state.load(RELAY_URL)?.lastPushRowId ?: 0L)
    }

    @Test
    fun retryAfterFailurePreservesIdentityAndIsIdempotent() {
        val committed = M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.situationUpdate(), 9)
        val harness = Harness(listOf(committed), failCalls = 1)

        assertFalse(harness.manager.pushNewEvents().success)
        assertTrue(harness.manager.pushNewEvents().success)

        val attempts = harness.sent.flatten()
        assertEquals(2, attempts.size)
        assertEquals(attempts[0].eid, attempts[1].eid)
        assertEquals(listOf(committed.event_id, committed.event_id), harness.sentCoreIds())
        assertEquals(9L, harness.state.load(RELAY_URL)?.lastPushRowId)
    }

    @Test
    fun existingNonMapRelayPriorityAndEligibilityRemainUnchanged() {
        val map = M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.markerAdd(), 1, insertedAt = 100)
        val dedicatedMap = M2D2RelayMapFixtures.entity(M2D2RelayMapFixtures.confirmation(), 2, insertedAt = 150)
        val chatCore = EventFactory.chatMessage("chat", "did:peer:owner", 100, 1, "help", ttlSeconds = 3600)
            .let { with(M2D2RelayMapFixtures) { it.withRelayKey() } }
        val chat = M2D2RelayMapFixtures.entity(chatCore, 3, insertedAt = 200)
        val unsupportedMesh = M2D2RelayMapFixtures.entity(
            EventFactory.sysGroup("group", "did:peer:owner", 100, 2, "Group")
                .let { with(M2D2RelayMapFixtures) { it.withRelayKey() } },
            4,
            M2D2RelayMapFixtures.MESH,
        )
        val harness = Harness(listOf(map, dedicatedMap, chat, unsupportedMesh))

        harness.manager.pushNewEvents()

        assertEquals(listOf("chat", map.event_id, dedicatedMap.event_id), harness.sentCoreIds())
        assertFalse(harness.sentCoreIds().any { it == unsupportedMesh.event_id })
        assertEquals(EventTypes.CHAT_MSG, chat.type)
    }

    private class Harness(
        rows: List<EventEntity>,
        private var failCalls: Int = 0,
    ) {
        val dao = FakeEventsDao(rows)
        val state = InMemoryRelayPushStateStore()
        val sent = mutableListOf<List<RelayEvent>>()
        val manager = RelayPushManager(
            relayUrl = RELAY_URL,
            eventsDao = dao,
            relayClient = RelayHttpClient(RELAY_URL),
            mapper = RelayEventMapper(groupKeyLoader = { M2D2RelayMapFixtures.groupKey }),
            stateStore = state,
            ingestTransport = { request ->
                sent += request.events
                if (failCalls-- > 0) result(success = false, accepted = 0) else result(true, request.events.size)
            },
        )

        fun sentCoreIds(): List<String> = sent.flatten().map { relay ->
            RelayPayloadCrypto.verifyAndDecrypt(relay, M2D2RelayMapFixtures.groupKey)!!.core.id
        }
    }

    private class FakeEventsDao(val rows: List<EventEntity>) : EventsDao {
        var returnedRows = emptyList<EventEntity>()
        override fun listPendingMapActionRootEventIds(gid: String, limit: Int): List<String> = emptyList()
        override fun loadAfterRowIdExcludingSources(afterRowId: Long, limit: Int, sources: List<String>): List<EventEntity> =
            rows.filter { it.id > afterRowId && it.source !in sources && it.invalid_at == null }
                .sortedBy { it.id }.take(limit).also { returnedRows = it }
        override fun insert(event: EventEntity): Long = error("unused")
        override fun loadSince(since: Long, sinceEventId: String, limit: Int) = error("unused")
        override fun loadSinceExcludingSource(since: Long, sinceEventId: String, limit: Int, source: String) = error("unused")
        override fun listRecentEventIdsForDtn(gid: String, since: Long, sinceEventId: String, limit: Int) = error("unused")
        override fun loadByEventIdsForDtn(gid: String, eventIds: List<String>) = error("unused")
        override fun loadDtnCursorWindow(gid: String, lastInsertedAt: Long, lastEventId: String, limit: Int) = error("unused")
        override fun markInvalid(gid: String, eventId: String, invalidAt: Long, reason: String) = Unit
        override fun deleteInvalidBefore(cutoff: Long) = Unit
        override fun exists(gid: String, eventId: String) = error("unused")
        override fun findById(gid: String, eventId: String) = error("unused")
        override fun listActiveActorIdsSince(gid: String, sinceCreatedT: Long) = error("unused")
        override fun deleteByEventId(gid: String, eventId: String) = error("unused")
        override fun listByTypeAndTarget(gid: String, type: String, targetId: String) = error("unused")
        override fun countByType(gid: String, type: String) = error("unused")
        override fun deleteByTypesAndTarget(gid: String, targetId: String, types: List<String>) = error("unused")
        override fun deleteByGid(gid: String) = error("unused")
    }

    companion object {
        private const val RELAY_URL = "https://relay.example"
        private fun result(success: Boolean, accepted: Int) = RelayIngestResult(
            success, if (success) 200 else 503, accepted, 0,
            if (success) null else "offline", null, emptyList(),
        )
    }
}
