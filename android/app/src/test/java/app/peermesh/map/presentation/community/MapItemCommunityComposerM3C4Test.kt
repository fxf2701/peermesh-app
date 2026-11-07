package app.peermesh.map.presentation.community

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapCoordinatesPresentation
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.detail.MarkerCanonicalPresentation
import app.peermesh.map.application.participation.MapCoordinationNotePreviewProjectionResult
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.participation.ProjectMapCoordinationNotePreview
import app.peermesh.map.application.participation.ProjectMapCoordinationParticipation
import app.peermesh.map.application.timeline.MapItemActivityActor
import app.peermesh.map.application.timeline.MapItemActivityEntry
import app.peermesh.map.application.timeline.MapItemActivityType
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult
import app.peermesh.map.application.verification.MapVerificationFreshnessProjectionResult
import app.peermesh.map.application.verification.ProjectMapItemVerification
import app.peermesh.map.application.verification.ProjectMapVerificationFreshness
import app.peermesh.map.presentation.detail.DomainEnrichmentState
import app.peermesh.map.presentation.detail.MapItemDetailShellContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemCommunityComposerM3C4Test {
    private var detailSeen: Any? = null
    private var entriesSeen: List<MapItemActivityEntry>? = null
    private var previewProjectorCalls = 0
    private val verification = MapItemVerificationProjectionResult.CoordinationNotApplicable
    private val freshness = MapVerificationFreshnessProjectionResult.CoordinationNotApplicable
    private val participation = MapCoordinationParticipationProjectionResult.NotApplicableToSituation
    private val preview = MapCoordinationNotePreviewProjectionResult.NoPreviewInSuppliedHistory
    private val composer = DefaultMapItemCommunityProjectionComposer(
        ProjectMapItemVerification { detailSeen = it; verification },
        ProjectMapVerificationFreshness { assertSame(verification, it); freshness },
        ProjectMapCoordinationParticipation { assertSame(detailSeen, it); participation },
        ProjectMapCoordinationNotePreview { identity, entries ->
            assertEquals(IDENTITY, identity)
            previewProjectorCalls += 1
            entriesSeen = entries
            preview
        },
    )

    @Test fun retainedHistoryInvokesPreviewProjectorWithExactEntriesAndPreservesProvenance() {
        val result = composer.project(input(MapItemCommunityTimelineInput.RetainedAfterFailure(listOf(ENTRY))))
            as MapItemCommunityProjectionSnapshot.Projected
        assertSame(verification, result.verification)
        assertSame(freshness, result.freshness)
        assertSame(participation, result.participation)
        assertSame(preview, (result.timeline as MapItemCommunityTimelineProjection.RetainedAfterFailure).notePreview)
        assertEquals(listOf(ENTRY), entriesSeen)
        assertEquals(1, previewProjectorCalls)
    }

    @Test fun availableHistoryInvokesPreviewProjectorWithExactEntries() {
        val entries = listOf(ENTRY)
        val result = composer.project(input(MapItemCommunityTimelineInput.Available(entries)))
            as MapItemCommunityProjectionSnapshot.Projected

        assertSame(preview, (result.timeline as MapItemCommunityTimelineProjection.Available).notePreview)
        assertSame(entries, entriesSeen)
        assertEquals(1, previewProjectorCalls)
    }

    @Test fun notReadyAndUnavailableDoNotInvokePreviewProjector() {
        val notReady = composer.project(input(MapItemCommunityTimelineInput.NotReady))
            as MapItemCommunityProjectionSnapshot.Projected
        val unavailable = composer.project(input(MapItemCommunityTimelineInput.Unavailable))
            as MapItemCommunityProjectionSnapshot.Projected

        assertTrue(notReady.timeline is MapItemCommunityTimelineProjection.NotReady)
        assertTrue(unavailable.timeline is MapItemCommunityTimelineProjection.Unavailable)
        assertEquals(0, previewProjectorCalls)
        assertNull(entriesSeen)
    }

    @Test fun latestInputsAreUsedWithoutRetainingAStaleMixedResult() {
        val first = composer.project(input(MapItemCommunityTimelineInput.Available(listOf(ENTRY))))
            as MapItemCommunityProjectionSnapshot.Projected
        val replacement = ENTRY.copy(entryId = "replacement")
        val second = composer.project(input(MapItemCommunityTimelineInput.RetainedAfterFailure(listOf(replacement))))
            as MapItemCommunityProjectionSnapshot.Projected
        assertEquals(listOf(replacement), entriesSeen)
        assertTrue(first.timeline is MapItemCommunityTimelineProjection.Available)
        assertTrue(second.timeline is MapItemCommunityTimelineProjection.RetainedAfterFailure)
    }

    @Test fun detailOnlyProjectionAcceptsEmptyTimelineAndLoadingIsPending() {
        assertTrue(composer.project(input(MapItemCommunityTimelineInput.Available(emptyList()))) is MapItemCommunityProjectionSnapshot.Projected)
        assertTrue(composer.project(input(MapItemCommunityTimelineInput.NotReady)) is MapItemCommunityProjectionSnapshot.Projected)
        assertTrue(composer.project(input(MapItemCommunityTimelineInput.Unavailable)) is MapItemCommunityProjectionSnapshot.Projected)
        val pending = composer.project(input(MapItemCommunityTimelineInput.Available(emptyList())).copy(
            detailContent = MapItemDetailShellContent.InitialLoading,
        ))
        assertEquals(MapItemCommunityProjectionSnapshot.PendingCurrentDetail, pending)
    }

    private fun input(timeline: MapItemCommunityTimelineInput) = MapItemCommunityProjectionInput(
        IDENTITY,
        MapItemDetailShellContent.Loaded(DETAIL, DomainEnrichmentState.AVAILABLE),
        timeline,
    )

    companion object {
        private val IDENTITY = MapItemIdentity("group", MapItemType.MARKER, "item")
        private val CANONICAL = MarkerCanonicalPresentation(
            IDENTITY, "coordination", "Item", null, MapItemCanonicalAvailability.ACTIVE,
            null, MapCoordinatesPresentation(10.0, 106.0), null,
        )
        private val DETAIL = MapItemDetail(IDENTITY, CANONICAL, null)
        private val ENTRY = MapItemActivityEntry(
            "entry", IDENTITY, MapItemActivityType.COORDINATION_NOTE_ADDED,
            MapItemActivityActor("actor", "Actor"), 10, null, "Note", null, null,
        )
    }
}
