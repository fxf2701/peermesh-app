package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.data.map.processing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves deleted legacy marker/area targets cannot regain interaction state.
 *
 */
@RunWith(AndroidJUnit4::class)
class DeletedLegacyTargetActionRejectionM2D3InstrumentationTest {
    @Test
    fun deletedLegacyMarkerRejectsDedicatedAction() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("m")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(EventFactory.mapMarkerDelete("d", "owner", 110, 2, "m")) is MapEventProcessingResult.Accepted)
            val result = h.process(h.confirm("c", "m"))
            assertEquals(MapEventRejectionReason.TARGET_DELETED, (result as MapEventProcessingResult.Rejected).reason)
            assertEquals("deleted", h.db.mapDao().findMarker("g", "m")!!.status)
            assertNull(h.db.mapItemActionDao().findByActionId("g", "c"))
        }
    }

    @Test
    fun deletedLegacyAreaRejectsDedicatedAction() {
        M2D3RoomHarness().use { h -> h.process(h.area("a")); h.process(EventFactory.mapAreaDelete("d", "owner", 110, 2, "a")); val r = h.process(h.confirmArea("c", "a")); assertEquals(MapEventRejectionReason.TARGET_DELETED, (r as MapEventProcessingResult.Rejected).reason) }
    }

    @Test
    fun rejectedActionDoesNotReactivateDeletedLegacyTarget() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); h.process(EventFactory.mapMarkerDelete("d", "owner", 110, 2, "m")); h.process(h.confirm("c", "m")); assertEquals("deleted", h.db.mapDao().findMarker("g", "m")!!.status) }
    }

}
