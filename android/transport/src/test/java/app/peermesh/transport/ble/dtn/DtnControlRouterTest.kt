package app.peermesh.transport.ble.dtn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtnControlRouterTest {
    @Test
    fun foregroundDeferredResponseRetainsTheActiveSessionTaskWithoutSendFailure() {
        val diagnostics = ArrayList<DtnDiagnosticEvent>()
        val engine = DtnSyncEngine(localPeerId = "local", store = FakeStore(), clockSeconds = { 2_000 })
        val router = DtnControlRouter(engine, DtnDiagnosticsSink { diagnostics.add(it) })
        val have = DtnControlPacket(
            type = DtnControlTypes.INV_HAVE,
            peerId = "remote",
            gid = "g",
            sessionId = "s",
            ids = listOf("event-b"),
            ts = 1_000,
        )

        router.route(
            blob = DtnControlCodec.encode(have),
            isDeferred = { true },
            sendResponse = {
                engine.deferActiveSessionTask("remote", "g", "s")
                false
            },
        )

        assertEquals(1, engine.foregroundDeferredSessionTasks().size)
        assertTrue(diagnostics.any { it.direction == "deferred" })
        assertTrue(diagnostics.none { it.direction == "send_failed" })
    }

    @Test
    fun deferredTaskClaimIsExactAndPreventsDuplicateWake() {
        val engine = deferredEngine(peerId = "remote", sessionId = "s")
        val task = engine.foregroundDeferredSessionTasks().single()

        assertEquals(DtnSyncEngine.DeferredResumeClaimResult.CLAIMED, engine.claimForegroundDeferredTask(task))
        assertEquals(DtnSyncEngine.DeferredResumeClaimResult.RESUME_IN_PROGRESS, engine.claimForegroundDeferredTask(task))
        assertTrue(engine.releaseForegroundDeferredTaskClaim(task))
        assertEquals(DtnSyncEngine.DeferredResumeClaimResult.CLAIMED, engine.claimForegroundDeferredTask(task))
        assertTrue(engine.completeForegroundDeferredTaskQueued(task))
        assertTrue(engine.foregroundDeferredSessionTasks().isEmpty())
    }

    @Test
    fun staleTaskTokenCannotClearCurrentDeferredTask() {
        val engine = deferredEngine(peerId = "remote", sessionId = "s")
        val task = engine.foregroundDeferredSessionTasks().single()
        val stale = task.copy(taskKey = "stale-task")

        assertEquals(DtnSyncEngine.DeferredResumeClaimResult.TASK_CHANGED, engine.claimForegroundDeferredTask(stale))
        assertFalse(engine.completeForegroundDeferredTaskQueued(stale))
        assertEquals(1, engine.foregroundDeferredSessionTasks().size)
    }

    @Test
    fun logicalInvalidationRemovesOnlyMatchingPeerSession() {
        val engine = deferredEngine(peerId = "peer-a", sessionId = "session-a")
        createDeferredTask(engine, peerId = "peer-b", sessionId = "session-b")

        engine.onPeerDisconnected(peerId = "peer-a")

        val remaining = engine.foregroundDeferredSessionTasks()
        assertEquals(listOf("peer-b"), remaining.map { it.peerId })
    }

    private fun deferredEngine(peerId: String, sessionId: String): DtnSyncEngine =
        DtnSyncEngine(localPeerId = "local", store = FakeStore(), clockSeconds = { 2_000 }).also {
            createDeferredTask(it, peerId, sessionId)
        }

    private fun createDeferredTask(engine: DtnSyncEngine, peerId: String, sessionId: String) {
        val have = DtnControlPacket(type = DtnControlTypes.INV_HAVE, peerId = peerId, gid = "g", sessionId = sessionId, ids = listOf("missing"), ts = 1_000)
        engine.onControlPacket(have)
        assertTrue(engine.deferActiveSessionTask(peerId, "g", sessionId))
    }
    @Test
    fun routeReturnsNotDtnForNormalEnvelope() {
        val router = DtnControlRouter(
            DtnSyncEngine(localPeerId = "local", store = FakeStore())
        )

        val result = router.route("""{"v":1,"gid":"g","mid":"m","ts":1,"enc":{"ct":"x"}}""".toByteArray()) {
            error("should not send")
        }

        assertEquals(DtnControlRouteResult.NotDtn, result)
    }

    @Test
    fun routeHandlesControlPacketAndSendsResponses() {
        val diagnostics = ArrayList<DtnDiagnosticEvent>()
        val router = DtnControlRouter(
            DtnSyncEngine(
                localPeerId = "local",
                store = FakeStore(existing = setOf("event-a")),
                clockSeconds = { 2_000 },
            ),
            diagnostics = DtnDiagnosticsSink { diagnostics.add(it) },
        )
        val sent = ArrayList<DtnControlPacket>()
        val have = DtnControlPacket(
            type = DtnControlTypes.INV_HAVE,
            peerId = "remote",
            gid = "g",
            sessionId = "s",
            ids = listOf("event-a", "event-b"),
            ts = 1_000,
        )

        val result = router.route(DtnControlCodec.encode(have)) { responseBytes ->
            DtnControlCodec.decode(responseBytes)?.let { sent.add(it) }
            true
        }

        assertTrue(result is DtnControlRouteResult.Handled)
        result as DtnControlRouteResult.Handled
        assertEquals(DtnControlTypes.INV_HAVE, result.packetType)
        assertEquals(1, result.responseCount)
        assertEquals(1, result.sentCount)
        assertEquals(DtnControlTypes.INV_WANT, sent.single().type)
        assertEquals(listOf("event-b"), sent.single().ids)
        assertEquals(listOf("DTN inv_have recv", "DTN inv_want sent", "DTN sync summary"), diagnostics.map { it.label })
        assertEquals(listOf("recv", "sent", "route"), diagnostics.map { it.direction })
        assertEquals(listOf("event-a", "event-b"), diagnostics[0].idSample)
        assertEquals(listOf("event-b"), diagnostics[1].idSample)
    }


    @Test
    fun tieredHaveWithAllIdsPresentReturnsEmptyWantAndEmitsLifecycleDiagnostics() {
        val diagnostics = ArrayList<DtnDiagnosticEvent>()
        val router = DtnControlRouter(
            DtnSyncEngine(
                localPeerId = "local",
                store = FakeStore(existing = setOf("event-a")),
                diagnostics = DtnDiagnosticsSink { diagnostics.add(it) },
                clockSeconds = { 2_000 },
            ),
            diagnostics = DtnDiagnosticsSink { diagnostics.add(it) },
        )
        val sent = ArrayList<DtnControlPacket>()
        val have = DtnControlPacket(
            type = DtnControlTypes.INV_HAVE,
            peerId = "remote",
            gid = "g",
            sessionId = "s",
            inventoryTier = DtnInventoryTier.NORMAL.wireValue,
            ids = listOf("event-a"),
            ts = 1_000,
        )

        val result = router.route(DtnControlCodec.encode(have)) { responseBytes ->
            DtnControlCodec.decode(responseBytes)?.let(sent::add)
            true
        }

        assertTrue(result is DtnControlRouteResult.Handled)
        assertEquals(1, sent.size)
        assertEquals(DtnControlTypes.INV_WANT, sent.single().type)
        assertTrue(sent.single().ids.isEmpty())
        assertEquals(
            listOf(
                "DTN inv_have recv",
                "DTN onHave enter",
                "DTN onHave compare start",
                "DTN onHave compare complete",
                "DTN onHave outcome",
                "DTN inv_want sent",
                "DTN sync summary",
            ),
            diagnostics.map { it.label },
        )
        assertTrue(diagnostics.first { it.label == "DTN onHave outcome" }.reason.orEmpty().contains("want=empty"))
    }

    @Test
    fun inventoryLookupFailureIsObservableAtEngineAndRouterBoundaries() {
        val diagnostics = ArrayList<DtnDiagnosticEvent>()
        val router = DtnControlRouter(
            DtnSyncEngine(
                localPeerId = "local",
                store = FakeStore(existingFailure = IllegalStateException("inventory lookup failed")),
                diagnostics = DtnDiagnosticsSink { diagnostics.add(it) },
                clockSeconds = { 2_000 },
            ),
            diagnostics = DtnDiagnosticsSink { diagnostics.add(it) },
        )
        val have = DtnControlPacket(
            type = DtnControlTypes.INV_HAVE,
            peerId = "remote",
            gid = "g",
            sessionId = "s",
            inventoryTier = DtnInventoryTier.NORMAL.wireValue,
            ids = listOf("event-a"),
            ts = 1_000,
        )

        var thrown: Throwable? = null
        try {
            router.route(DtnControlCodec.encode(have)) { true }
        } catch (failure: Throwable) {
            thrown = failure
        }

        assertTrue(thrown is IllegalStateException)
        assertEquals(
            listOf(
                "DTN inv_have recv",
                "DTN onHave enter",
                "DTN onHave compare start",
                "DTN onHave compare failed",
                "DTN control handle failed",
            ),
            diagnostics.map { it.label },
        )
        assertTrue(diagnostics.last().reason.orEmpty().contains("inventory lookup failed"))
        assertTrue(diagnostics.none { it.label == "DTN inv_want sent" })
    }

    @Test
    fun routeHelloSendsInventoryHave() {
        val router = DtnControlRouter(
            DtnSyncEngine(
                localPeerId = "local",
                store = FakeStore(
                    inventory = listOf(
                        DtnInventoryItem(
                            gid = "g",
                            eventId = "event-a",
                            type = "CHAT.MSG",
                            createdAtSeconds = 1_000,
                            insertedAtSeconds = 1_000,
                        )
                    )
                ),
                clockSeconds = { 2_000 },
            )
        )
        val sent = ArrayList<DtnControlPacket>()
        val hello = DtnControlPacket(
            type = DtnControlTypes.HELLO,
            peerId = "remote",
            gid = "g",
            sessionId = "s",
            ts = 1_000,
        )

        val result = router.route(DtnControlCodec.encode(hello)) { responseBytes ->
            DtnControlCodec.decode(responseBytes)?.let { sent.add(it) }
            true
        }

        assertTrue(result is DtnControlRouteResult.Handled)
        assertEquals(DtnControlTypes.INV_HAVE, sent.single().type)
        assertEquals(listOf("event-a"), sent.single().ids)
    }

    private class FakeStore(
        private val existing: Set<String> = emptySet(),
        private val inventory: List<DtnInventoryItem> = emptyList(),
        private val existingFailure: RuntimeException? = null,
    ) : DtnInventoryStore {
        override fun listInventory(gid: String, limit: Int): List<DtnInventoryItem> {
            return inventory.filter { it.gid == gid }.take(limit)
        }

        override fun existingEventIds(gid: String, eventIds: Collection<String>): Set<String> {
            existingFailure?.let { throw it }
            return eventIds.filter { it in existing }.toSet()
        }

        override fun loadData(gid: String, eventIds: Collection<String>, limit: Int): List<DtnDataItem> = emptyList()
    }
}
