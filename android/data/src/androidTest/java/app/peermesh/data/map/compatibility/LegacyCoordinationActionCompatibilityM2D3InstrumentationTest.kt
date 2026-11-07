package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.data.map.processing.MapEventProcessingResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves production handling for canonical Coordination markers and areas.
 */
@RunWith(AndroidJUnit4::class)
class LegacyCoordinationActionCompatibilityM2D3InstrumentationTest {
    @Test
    fun recognizedLegacyCoordinationMarkerAcceptsAcknowledgeAndNote() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("c", kind = "camp")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.ack("ack", "c")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.coordinationNote("note", "c")) is MapEventProcessingResult.Accepted)

            val actions = h.db.mapItemActionDao().findAppliedHistory("g", "marker", "c", null, null, null, null, 10)
            assertEquals(setOf("acknowledge", "add_note"), actions.map { it.action_type }.toSet())
            assertNull(h.db.mapItemVoteDao().find("g", "c", "peer"))
        }
    }

    @Test
    fun currentCatalogDeclaresSupportedCoordinationAreaKinds() {
        val coordinationKinds = MapItemKindCatalog.canonicalKinds.filter {
            MapItemKindCatalog.categoryFor(it)?.wireValue == "coordination"
        }
        assertTrue(coordinationKinds.isNotEmpty())
        assertEquals(
            setOf("assembly_zone", "trail_sector", "event_zone", "rescue_staging"),
            coordinationKinds.filter(MapItemKindCatalog::supportsArea).toSet(),
        )
    }

    @Test
    fun canonicalCoordinationAreaAcceptsAcknowledgeAndNote() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.area("coord-area", kind = "assembly_zone")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.ack("ack", "coord-area", area = true)) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.coordinationNote("note", "coord-area", area = true)) is MapEventProcessingResult.Accepted)

            val actions = h.db.mapItemActionDao().findAppliedHistory("g", "area", "coord-area", null, null, null, null, 10)
            assertEquals(setOf("acknowledge", "add_note"), actions.map { it.action_type }.toSet())
        }
    }

    @Test
    fun coordinationActionDoesNotCreateLegacyVoteOrContribution() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("c", kind = "camp")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.ack("ack", "c")) is MapEventProcessingResult.Accepted)
            assertNull(h.db.mapItemVoteDao().find("g", "c", "peer"))
        }
    }

    @Test
    fun situationOnlyActionIsRejectedForLegacyCoordinationTarget() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("c", kind = "camp")) is MapEventProcessingResult.Accepted)
            val result = h.process(h.confirm("bad", "c"))
            assertTrue(result is MapEventProcessingResult.Rejected)
            assertNull(h.db.mapItemActionDao().findByActionId("g", "bad"))
        }
    }

}
