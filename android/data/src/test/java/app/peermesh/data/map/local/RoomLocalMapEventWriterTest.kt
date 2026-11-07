package app.peermesh.data.map.local

import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.types.EventTypes
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.data.map.processing.MapEventProcessor
import app.peermesh.data.map.processing.MapEventRejectionReason
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLocalMapEventWriterTest {
    @Test
    fun `write logs committed local map event with its event id`() {
        ActivityLog.setEnabled(true)
        ActivityLog.setAllowedLabels(listOf("Local map event committed"))
        ActivityLog.startCapture(emptyList())
        try {
            val core = core()
            val writer = RoomLocalMapEventWriter(MapEventProcessor {
                MapEventProcessingResult.Accepted(it.core.id)
            })

            writer.write(LocalMapWriteRequest("group", core, "did:local", 456L))

            val entry = ActivityLog.snapshot().firstOrNull { it.label == "Local map event committed" }
            assertNotNull(entry)
            assertEquals("local", entry?.source)
            assertEquals("event-1", entry?.eventId)
            assertEquals("group", entry?.gid)
            assertEquals("type=MAP.MARKER.ADD target=marker-1", entry?.message)
        } finally {
            ActivityLog.stopCapture()
            ActivityLog.setEnabled(false)
        }
    }

    @Test
    fun `write preserves exact event and local processing inputs and invokes once`() {
        val core = core()
        var calls = 0
        lateinit var captured: MapEventProcessingRequest
        val writer = RoomLocalMapEventWriter(MapEventProcessor {
            calls++
            captured = it
            MapEventProcessingResult.Accepted(it.core.id)
        })

        writer.write(LocalMapWriteRequest("group", core, "did:local", 456L))

        assertEquals(1, calls)
        assertEquals("group", captured.gid)
        assertSame(core, captured.core)
        assertEquals("local", captured.source)
        assertEquals("did:local", captured.localActorDid)
        assertEquals(456L, captured.insertedAtSeconds)
    }

    @Test
    fun `write maps every processor outcome one to one`() {
        val failure = IllegalStateException("boom")
        val cases = listOf(
            MapEventProcessingResult.Accepted("e") to LocalMapWriteOutcome.AcceptedNew("e"),
            MapEventProcessingResult.AcceptedNoChange("e") to LocalMapWriteOutcome.AcceptedNoChange("e"),
            MapEventProcessingResult.ExactDuplicate("e") to LocalMapWriteOutcome.ExactDuplicate("e"),
            MapEventProcessingResult.ConflictingDuplicate("e") to LocalMapWriteOutcome.ConflictingDuplicate("e"),
            MapEventProcessingResult.Rejected("e", MapEventRejectionReason.OWNER_REQUIRED) to
                LocalMapWriteOutcome.SemanticRejected("e", MapEventRejectionReason.OWNER_REQUIRED),
            MapEventProcessingResult.InvalidPersistedState("e", "bad row") to
                LocalMapWriteOutcome.InvalidPersistedState("e", "bad row"),
            MapEventProcessingResult.PersistenceFailure("e", failure) to
                LocalMapWriteOutcome.PersistenceFailure("e", failure),
        )

        cases.forEach { (processorResult, expected) ->
            val actual = RoomLocalMapEventWriter(MapEventProcessor { processorResult })
                .write(LocalMapWriteRequest("g", core(), "actor", 1L))
            assertEquals(expected, actual)
        }
        assertTrue(LocalMapWriteOutcome.AcceptedNew("e").isNewlyAcceptedForTransport)
        assertFalse(LocalMapWriteOutcome.AcceptedNoChange("e").isNewlyAcceptedForTransport)
        assertFalse(LocalMapWriteOutcome.ExactDuplicate("e").isNewlyAcceptedForTransport)
        assertFalse(LocalMapWriteOutcome.ConflictingDuplicate("e").isNewlyAcceptedForTransport)
    }

    private fun core() = EventCore(
        ev = 1,
        id = "event-1",
        type = EventTypes.MAP_MARKER_ADD,
        actor = Actor("actor"),
        time = EventTime(123L, 7L),
        ref = EventRef(target = "marker-1"),
        data = buildJsonObject { put("marker_id", "marker-1") },
    )
}
