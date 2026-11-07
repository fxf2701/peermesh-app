package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.data.map.processing.MapEventRejectionReason
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Composes legacy-origin targets with the temporal policy already accepted in M2C.
 *
 */
@RunWith(AndroidJUnit4::class)
class LegacyTemporalWindowCompatibilityM2D3InstrumentationTest {
    @Test
    fun finiteLegacyTtlDerivedWindowUsesExistingInteractionPolicy() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("m", ttl = 1000)) is MapEventProcessingResult.Accepted)
            val state = h.db.mapItemDomainStateDao().find("g", "marker", "m")!!
            assertEquals("legacy_ttl_derived", state.temporal_origin)
            val cutoff = state.interaction_until!!
            val result = h.process(h.confirm("late", "m", t = cutoff), cutoff)
            assertEquals(MapEventRejectionReason.TARGET_EXPIRED, (result as MapEventProcessingResult.Rejected).reason)
            assertNull(h.db.mapItemActionDao().findByActionId("g", "late"))
        }
    }

    @Test
    fun noExpiryLegacyTargetRetainsExistingNoExpiryBehavior() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("m")) is MapEventProcessingResult.Accepted)
            val state = h.db.mapItemDomainStateDao().find("g", "marker", "m")!!
            assertNull(state.expires_at)
            assertNull(state.interaction_until)
            assertTrue(h.process(h.confirm("c", "m", t = 10_000), 10_000) is MapEventProcessingResult.Accepted)
            assertEquals("applied", h.db.mapItemActionDao().findByActionId("g", "c")!!.materialization_state)
        }
    }

    @Test
    fun ownerEditExpiryAndInteractionWindowsRemainIndependent() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m", ttl = 2000)); val s = h.db.mapItemDomainStateDao().find("g", "marker", "m")!!; assertNotEquals(s.owner_edit_until, s.interaction_until); assertEquals(s.expires_at, s.interaction_until) }
    }

}
