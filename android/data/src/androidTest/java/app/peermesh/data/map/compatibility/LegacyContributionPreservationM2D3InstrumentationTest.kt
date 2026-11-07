package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves legacy votes/notes remain legacy history and are not converted to dedicated actions.
 *
 */
@RunWith(AndroidJUnit4::class)
class LegacyContributionPreservationM2D3InstrumentationTest {
    @Test
    fun legacyMarkerContributionsRemainUnchangedAfterIngest() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); h.process(h.contribution("m")); val v = h.db.mapItemVoteDao().find("g", "m", "peer")!!; assertEquals("legacy contribution", v.note); assertEquals(2, v.confidence) }
    }

    @Test
    fun legacyAreaContributionsRemainUnchangedAfterIngest() {
        M2D3RoomHarness().use { h -> h.process(h.area("a")); h.process(h.contribution("a", area = true)); assertEquals("legacy contribution", h.db.mapItemVoteDao().find("g", "a", "peer")!!.note) }
    }

    @Test
    fun dedicatedActionsDoNotRewriteLegacyContributionRows() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); h.process(h.contribution("m")); val before = h.db.mapItemVoteDao().find("g", "m", "peer"); h.process(h.confirm("confirm", "m", "peer2")); assertEquals(before, h.db.mapItemVoteDao().find("g", "m", "peer")) }
    }

    @Test
    fun legacyContributionsDoNotIncreaseDedicatedConfirmationCounts() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); h.process(h.contribution("m")); assertEquals(0, h.db.mapItemActionDao().countConfirmationActors("g", "marker", "m")); h.process(h.confirm("confirm", "m", "peer2")); assertEquals(1, h.db.mapItemActionDao().countConfirmationActors("g", "marker", "m")) }
    }

}
