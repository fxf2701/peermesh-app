package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves legacy removal requests/cancellations remain independent from Situation lifecycle.
 *
 */
@RunWith(AndroidJUnit4::class)
class LegacyRemovalPreservationM2D3InstrumentationTest {
    @Test
    fun legacyRemovalRequestAndCancellationRemainUnchanged() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m", removalRequestedT = 90, removalCancelT = 95)); val row = h.db.mapDao().findMarker("g", "m")!!; assertEquals(90L, row.removal_requested_t); assertEquals(95L, row.removal_cancel_t) }
    }

    @Test
    fun legacyRemovalStateIsNotConvertedIntoNoLongerActive() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m", removalRequestedT = 90)); assertEquals("active", h.db.mapItemDomainStateDao().find("g", "marker", "m")!!.situation_lifecycle) }
    }

    @Test
    fun dedicatedLifecycleActionDoesNotRewriteLegacyRemovalHistory() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m", removalRequestedT = 90, removalCancelT = 95)); val before = h.db.mapDao().findMarker("g", "m")!!; h.process(h.closeStatus("close", "m")); val after = h.db.mapDao().findMarker("g", "m")!!; assertEquals(before.removal_requested_t, after.removal_requested_t); assertEquals(before.removal_cancel_t, after.removal_cancel_t) }
    }

}
