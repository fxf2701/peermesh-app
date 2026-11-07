package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves one target can retain independent legacy and dedicated histories without semantic merging.
 *
 * Exercises the production RoomMapEventProcessor and DAO read paths to prove that legacy and
 * dedicated histories remain independently persisted and aggregated.
 */
@RunWith(AndroidJUnit4::class)
class LegacyNewHistoryCoexistenceM2D3InstrumentationTest {
    @Test
    fun legacyContributionAndDedicatedConfirmationHistoriesCoexist() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("m")) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.contribution("m")) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.confirm("c", "m", "peer2")) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            assertNotNull(h.db.mapItemVoteDao().find("g", "m", "peer"))
            assertEquals("applied", h.db.mapItemActionDao().findByActionId("g", "c")!!.materialization_state)
        }
    }

    @Test
    fun legacyContributionNoteAndCoordinationNoteRemainSeparateHistories() {
        M2D3RoomHarness().use { h -> h.process(h.marker("c", kind = "camp")); h.process(h.contribution("c", note = "legacy note", kind = "camp")); h.process(h.coordinationNote("n", "c")); assertEquals("legacy note", h.db.mapItemVoteDao().find("g", "c", "peer")!!.note); assertEquals("coordination note", h.db.mapItemActionDao().findByActionId("g", "n")!!.action_note) }
    }

    @Test
    fun legacyContributionConfidenceAndSituationConfirmationRemainSeparate() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); h.process(h.contribution("m", confidence = 0)); h.process(h.confirm("c", "m", "peer2")); assertEquals(0, h.db.mapItemVoteDao().find("g", "m", "peer")!!.confidence); assertEquals("firsthand", h.db.mapItemActionDao().findByActionId("g", "c")!!.information_source) }
    }

    @Test
    fun legacyContributionsDoNotIncreaseConfirmationOrAcknowledgementCounts() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); h.process(h.contribution("m")); assertEquals(0, h.db.mapItemActionDao().countConfirmationActors("g", "marker", "m")); assertEquals(0, h.db.mapItemActionDao().countAcknowledgementActors("g", "marker", "m")) }
    }

    @Test
    fun acknowledgementsDoNotIncreaseSituationConfirmationCount() {
        M2D3RoomHarness().use { h -> h.process(h.marker("c", kind = "camp")); h.process(h.ack("a", "c")); assertEquals(0, h.db.mapItemActionDao().countConfirmationActors("g", "marker", "c")); assertEquals(1, h.db.mapItemActionDao().countAcknowledgementActors("g", "marker", "c")) }
    }

    @Test
    fun confirmationsDoNotIncreaseCoordinationAcknowledgementCount() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m")); h.process(h.confirm("c", "m")); assertEquals(1, h.db.mapItemActionDao().countConfirmationActors("g", "marker", "m")); assertEquals(0, h.db.mapItemActionDao().countAcknowledgementActors("g", "marker", "m")) }
    }

    @Test
    fun creatorLegacyConfidenceAndCreatorInformationSourceRemainIndependent() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m", confidence = 2)); h.process(h.confirm("c", "m")); val s = h.db.mapItemDomainStateDao().find("g", "marker", "m")!!; assertNull(s.creator_information_source); assertEquals(2, h.db.mapDao().findMarker("g", "m")!!.confidence) }
    }

    @Test
    fun legacyRemovalStateAndSituationLifecycleRemainIndependent() {
        M2D3RoomHarness().use { h -> h.process(h.marker("m", removalRequestedT = 90)); h.process(h.closeStatus("s", "m")); assertEquals(90L, h.db.mapDao().findMarker("g", "m")!!.removal_requested_t); assertEquals("no_longer_active", h.db.mapItemDomainStateDao().find("g", "marker", "m")!!.situation_lifecycle) }
    }

    @Test
    fun coexistenceDoesNotBackfillOrRewriteEitherHistory() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("m")) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.contribution("m")) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            val vote = h.db.mapItemVoteDao().find("g", "m", "peer")
            assertTrue(h.process(h.confirm("c", "m", "peer2")) is app.peermesh.data.map.processing.MapEventProcessingResult.Accepted)
            assertEquals(vote, h.db.mapItemVoteDao().find("g", "m", "peer"))
            assertEquals(1, h.db.mapItemActionDao().findAppliedHistory("g", "marker", "m", null, null, null, null, 10).size)
        }
    }
}
