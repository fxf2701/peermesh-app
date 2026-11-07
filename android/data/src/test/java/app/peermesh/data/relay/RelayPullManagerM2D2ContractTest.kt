package app.peermesh.data.relay

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.relay.RelayEvent
import app.peermesh.core.relay.RelayFetchResponse
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
import app.peermesh.data.ingest.EnvelopeReceiverImpl
import app.peermesh.data.ingest.EventIngestor
import app.peermesh.data.ingest.EventSources
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.security.crypto.SecureKeyStore
import app.peermesh.transport.relay.RelayHttpClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class RelayPullManagerM2D2ContractTest {
    private lateinit var server: LoopbackRelayServer
    private var response = RelayFetchResponse()
    private val requestQueries = mutableListOf<String?>()

    @Before
    fun startServer() {
        server = LoopbackRelayServer(
            responseBody = { CanonicalJson.json.encodeToString(RelayFetchResponse.serializer(), response) },
            onTarget = { target -> requestQueries += target.substringAfter('?', "").ifBlank { null } },
        )
    }

    @After
    fun stopServer() = server.close()

    @Test
    fun relayPullUsesRelaySourceAndPreservesMapIdentity() {
        val originals = listOf(M2D2RelayMapFixtures.markerAdd(), M2D2RelayMapFixtures.confirmation())
        response = RelayFetchResponse(events = originals.mapIndexed(::mapped), next_cursor = "page-2")
        val captured = mutableListOf<MapEventProcessingRequest>()
        val manager = manager { request ->
            captured += request
            MapEventProcessingResult.Accepted(request.core.id)
        }

        val report = manager.pullByCursor(M2D2RelayMapFixtures.GID)

        assertTrue(report.success)
        assertEquals(2, report.accepted)
        assertEquals(originals, captured.map { it.core })
        assertTrue(captured.all { it.source == EventSources.RELAY })
        assertTrue(captured.all { it.gid == M2D2RelayMapFixtures.GID })
    }

    @Test
    fun retryingTheSameRelayPageIsIdempotentForMapEvents() {
        val core = M2D2RelayMapFixtures.confirmation()
        response = RelayFetchResponse(events = listOf(mapped(0, core)), next_cursor = "same-page")
        var calls = 0
        val seen = mutableListOf<MapEventProcessingRequest>()
        val manager = manager { request ->
            seen += request
            if (calls++ == 0) MapEventProcessingResult.Accepted(request.core.id)
            else MapEventProcessingResult.ExactDuplicate(request.core.id)
        }

        assertEquals(1, manager.pullByCursor(M2D2RelayMapFixtures.GID).accepted)
        assertEquals(0, manager.pullByCursor(M2D2RelayMapFixtures.GID).accepted)

        assertEquals(listOf(core.id, core.id), seen.map { it.core.id })
        assertEquals(seen[0].core, seen[1].core)
        assertTrue(requestQueries.last()!!.contains("cursor=same-page"))
    }

    @Test
    fun oneMalformedRelayItemDoesNotSuppressValidSiblings() {
        val first = M2D2RelayMapFixtures.markerAdd("first")
        val last = M2D2RelayMapFixtures.situationUpdate("last")
        val malformed = RelayEvent(
            eid = "malformed",
            gid = M2D2RelayMapFixtures.GID,
            ts = first.time.t,
            exp = first.time.t + 60,
            body = buildJsonObject { put("not", "an encrypted relay body") },
            sig = "invalid",
        )
        response = RelayFetchResponse(
            events = listOf(mapped(0, first), malformed, mapped(2, last)),
            next_cursor = "after-malformed",
        )
        val acceptedIds = mutableListOf<String>()
        val manager = manager { request ->
            acceptedIds += request.core.id
            MapEventProcessingResult.Accepted(request.core.id)
        }

        val report = manager.pullByCursor(M2D2RelayMapFixtures.GID)

        assertTrue(report.success)
        assertEquals(3, report.received)
        assertEquals(2, report.accepted)
        assertEquals(listOf("first", "last"), acceptedIds)
    }

    @Test
    fun cursorAndHttpOrderNeverBecomeSemanticOrderingInputs() {
        val laterInHttp = M2D2RelayMapFixtures.markerUpdate("low-lamport", timestamp = 1_700_000_010L)
            .copy(time = M2D2RelayMapFixtures.markerUpdate().time.copy(t = 1_700_000_010L, lt = 3))
        val firstInHttp = M2D2RelayMapFixtures.markerUpdate("high-lamport", timestamp = 1_700_000_010L)
            .copy(time = M2D2RelayMapFixtures.markerUpdate().time.copy(t = 1_700_000_010L, lt = 99))
        response = RelayFetchResponse(events = listOf(mapped(0, firstInHttp), mapped(1, laterInHttp)), next_cursor = "opaque-999")
        val captured = mutableListOf<MapEventProcessingRequest>()
        val manager = manager { request ->
            captured += request
            MapEventProcessingResult.Accepted(request.core.id)
        }

        manager.pullByCursor(M2D2RelayMapFixtures.GID, limit = 17)

        assertEquals(listOf(firstInHttp, laterInHttp), captured.map { it.core })
        assertEquals(listOf(99L, 3L), captured.map { it.core.time.lt })
        assertTrue(requestQueries.single()!!.contains("limit=17"))
        assertFalse(captured.any { it.core.id.contains("opaque-999") })
    }

    private fun mapped(index: Int, core: app.peermesh.core.model.EventCore): RelayEvent =
        RelayEventMapper(groupKeyLoader = { M2D2RelayMapFixtures.groupKey })
            .toRelayEvent(M2D2RelayMapFixtures.entity(core, index + 1L))!!

    private fun manager(
        processor: (MapEventProcessingRequest) -> MapEventProcessingResult,
    ): RelayPullManager {
        val db = Mockito.mock(AppDatabase::class.java)
        Mockito.`when`(db.eventsDao()).thenReturn(Mockito.mock(EventsDao::class.java))
        Mockito.`when`(db.eventDedupeDao()).thenReturn(EmptyDedupeDao())
        Mockito.`when`(db.chatDao()).thenReturn(Mockito.mock(ChatDao::class.java))
        Mockito.`when`(db.deletedEventDao()).thenReturn(Mockito.mock(DeletedEventDao::class.java))
        Mockito.`when`(db.groupDao()).thenReturn(Mockito.mock(GroupDao::class.java))
        Mockito.`when`(db.userProfileDao()).thenReturn(Mockito.mock(UserProfileDao::class.java))
        Mockito.`when`(db.removedMemberDao()).thenReturn(Mockito.mock(RemovedMemberDao::class.java))
        Mockito.`when`(db.liveTrailDao()).thenReturn(Mockito.mock(LiveTrailDao::class.java))
        val keyStore = Mockito.mock(SecureKeyStore::class.java)
        Mockito.`when`(keyStore.loadGroupKey(M2D2RelayMapFixtures.GID, M2D2RelayMapFixtures.KID))
            .thenReturn(M2D2RelayMapFixtures.groupKey)
        Mockito.`when`(keyStore.loadGroupKey(M2D2RelayMapFixtures.GID, null))
            .thenReturn(M2D2RelayMapFixtures.groupKey)
        val receiver = EnvelopeReceiverImpl(EventIngestor(db, mapEventProcessor = processor), keyStore)
        return RelayPullManager(
            relayUrl = "http://127.0.0.1:${server.port}",
            relayClient = RelayHttpClient("http://127.0.0.1:${server.port}"),
            receiver = receiver,
            stateStore = InMemoryRelayPullStateStore(),
        )
    }

    private class EmptyDedupeDao : EventDedupeDao {
        override fun upsert(entity: EventDedupeEntity) = Unit
        override fun countActive(gid: String, eventId: String, nowSeconds: Long) = 0
        override fun deleteExpired(nowSeconds: Long) = 0
        override fun deleteByGid(gid: String) = 0
    }

    private class LoopbackRelayServer(
        private val responseBody: () -> String,
        private val onTarget: (String) -> Unit,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val port: Int = socket.localPort
        private val thread = Thread {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                    val requestLine = reader.readLine().orEmpty()
                    generateSequence { reader.readLine() }.takeWhile { line -> line.isNotEmpty() }.toList()
                    requestLine.split(' ').getOrNull(1)?.let(onTarget)
                    val body = responseBody().toByteArray()
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply {
                        write(headers.toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            running.set(false)
            socket.close()
            thread.join(1_000)
        }
    }
}
