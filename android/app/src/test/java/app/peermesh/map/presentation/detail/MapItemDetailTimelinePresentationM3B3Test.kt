package app.peermesh.map.presentation.detail

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.submission.MapInteractionDeliveryWarning
import app.peermesh.map.application.submission.MapInteractionInputFailure
import app.peermesh.map.application.submission.MapInteractionRejection
import app.peermesh.map.application.submission.MapInteractionSubmissionFailure
import app.peermesh.map.application.timeline.MapItemActivityActor
import app.peermesh.map.application.timeline.MapItemActivityEntry
import app.peermesh.map.application.timeline.MapItemActivityType
import app.peermesh.map.presentation.timeline.MapItemTimelineAppendState
import app.peermesh.map.presentation.timeline.MapItemTimelineContent
import app.peermesh.map.presentation.timeline.MapItemTimelineFailure
import app.peermesh.map.presentation.timeline.MapItemTimelineRefreshState
import app.peermesh.map.presentation.timeline.MapItemTimelineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemDetailTimelinePresentationM3B3Test {
    @Test fun manualRefreshDispatchesDetailIndependentlyAndSelectsOnlyAcceptedTimelineOperation() {
        val loaded = coordinator(available(listOf(entry("a"))))
        assertEquals(MapItemTimelineManualRefreshOperation.REFRESH_FIRST_PAGE, loaded.manualRefreshOperation())
        assertEquals(listOf("detail", "first-page"), dispatch(loaded))

        val initialFailure = coordinator(state(MapItemTimelineContent.InitialFailure(FAILURE)))
        assertEquals(MapItemTimelineManualRefreshOperation.LOAD_INITIAL, initialFailure.manualRefreshOperation())
        assertEquals(listOf("detail", "initial"), dispatch(initialFailure))

        val notLoaded = coordinator(state(MapItemTimelineContent.NotLoaded))
        assertEquals(MapItemTimelineManualRefreshOperation.LOAD_INITIAL, notLoaded.manualRefreshOperation())
        assertEquals(listOf("detail", "initial"), dispatch(notLoaded))

        val initialLoading = coordinator(state(MapItemTimelineContent.InitialLoading))
        assertEquals(MapItemTimelineManualRefreshOperation.NONE, initialLoading.manualRefreshOperation())
        assertEquals(listOf("detail"), dispatch(initialLoading))

        val retained = coordinator(available(listOf(entry("a")))).also {
            it.onDetailContent(MapItemDetailShellContent.TargetUnavailable)
        }
        assertEquals(MapItemTimelineManualRefreshOperation.NONE, retained.manualRefreshOperation())
        assertEquals(listOf("detail"), dispatch(retained))

        val suppressed = MapItemDetailTimelineCoordinator()
        assertEquals(MapItemTimelineManualRefreshOperation.NONE, suppressed.manualRefreshOperation())
        assertEquals(listOf("detail"), dispatch(suppressed))
    }

    @Test fun manualRefreshCapturesTimelineOperationBeforeSynchronousDetailCallbacks() {
        val available = coordinator(available(listOf(entry("a"))))
        assertEquals(listOf("detail", "first-page"), dispatch(available) {
            available.onDetailContent(MapItemDetailShellContent.InitialLoading)
        })

        val initialFailure = coordinator(state(MapItemTimelineContent.InitialFailure(FAILURE)))
        assertEquals(listOf("detail", "initial"), dispatch(initialFailure) {
            initialFailure.onDetailContent(MapItemDetailShellContent.InitialLoading)
        })

        val loading = coordinator(state(MapItemTimelineContent.InitialLoading))
        assertEquals(listOf("detail"), dispatch(loading) {
            loading.onTimelineState(available(listOf(entry("late"))))
        })
    }

    @Test fun detailTransientStatesPreserveTargetModeAndOnlyUnavailableCapturesRetainedHistory() {
        val suppressed = MapItemDetailTimelineCoordinator()
        assertFalse(suppressed.onDetailContent(MapItemDetailShellContent.InitialLoading).visible)
        assertFalse(suppressed.onDetailContent(MapItemDetailShellContent.ReadFailure).visible)
        assertFalse(suppressed.onDetailContent(MapItemDetailShellContent.InvalidItemData).visible)

        val current = coordinator(available(listOf(entry("a"))))
        listOf(
            MapItemDetailShellContent.InitialLoading,
            MapItemDetailShellContent.ReadFailure,
            MapItemDetailShellContent.InvalidItemData,
        ).forEach { transient ->
            val presentation = current.onDetailContent(transient)
            assertTrue(presentation.visible)
            assertFalse(presentation.retainedHistory)
            assertEquals(listOf("a"), presentation.entries.map { it.entryId })
            assertTrue(presentation.loadOlderEnabled)
        }

        val retained = coordinator(available(listOf(entry("a"))))
        retained.onDetailContent(MapItemDetailShellContent.TargetUnavailable)
        listOf(
            MapItemDetailShellContent.InitialLoading,
            MapItemDetailShellContent.ReadFailure,
            MapItemDetailShellContent.InvalidItemData,
        ).forEach { transient ->
            assertTrue(retained.onDetailContent(transient).retainedHistory)
        }
    }

    @Test fun retainedHistoryFreezesAcceptedEntriesAcrossLateInitialRefreshAndAppendStates() {
        val coordinator = coordinator(available(listOf(entry("a"), entry("b"))))
        coordinator.onDetailContent(MapItemDetailShellContent.TargetUnavailable)

        listOf(
            available(listOf(entry("c"), entry("a"), entry("b"))),
            available(listOf(entry("d"), entry("a"), entry("b"))).copy(
                refresh = MapItemTimelineRefreshState.Refreshing,
            ),
            available(listOf(entry("e"), entry("a"), entry("b"))).copy(
                append = MapItemTimelineAppendState.Loading,
            ),
        ).forEach { lateState ->
            val presentation = coordinator.onTimelineState(lateState)
            assertEquals(listOf("a", "b"), presentation.entries.map { it.entryId })
            assertTrue(presentation.retainedHistory)
            assertFalse(presentation.loadOlderEnabled)
            assertEquals(null, presentation.retryOperation)
        }
    }

    @Test fun lateInitialResultNeverBecomesRetainedHistoryWhenNoEntryWasAcceptedBeforeUnavailability() {
        val coordinator = coordinator(state(MapItemTimelineContent.InitialLoading))
        val unavailable = coordinator.onDetailContent(MapItemDetailShellContent.TargetUnavailable)
        assertTrue(unavailable.entries.isEmpty())

        val lateInitial = coordinator.onTimelineState(available(listOf(entry("late"))))
        assertTrue(lateInitial.entries.isEmpty())
        assertTrue(lateInitial.retainedHistory)
        assertEquals(MapItemTimelineStatus.EMPTY, lateInitial.status)
    }

    @Test fun availabilityReturnUnfreezesDeterministicallyFromLatestTimelineState() {
        val coordinator = coordinator(available(listOf(entry("a"))))
        coordinator.onDetailContent(MapItemDetailShellContent.TargetUnavailable)
        coordinator.onTimelineState(available(listOf(entry("new"), entry("a"))))

        val restored = coordinator.onDetailContent(availableCanonicalContent())
        assertFalse(restored.retainedHistory)
        assertEquals(listOf("new", "a"), restored.entries.map { it.entryId })
    }

    @Test fun acceptedFeedbackRefreshesOnlyForCurrentTargetAndIsAlwaysConsumed() {
        listOf(
            MapItemDetailShellFeedback.Accepted(),
            MapItemDetailShellFeedback.Accepted(MapInteractionDeliveryWarning.ENQUEUE_FAILED),
        ).forEach { accepted ->
            val coordinator = coordinator(available(emptyList()))
            assertTrue(coordinator.shouldRefreshForAcceptedFeedback(accepted))
            assertFalse(coordinator.shouldRefreshForAcceptedFeedback(accepted))
        }

        listOf(
            MapItemDetailTimelineCoordinator(),
            coordinator(available(emptyList())).also {
                it.onDetailContent(MapItemDetailShellContent.TargetUnavailable)
            },
        ).forEach { nonCurrent ->
            val accepted = MapItemDetailShellFeedback.Accepted(MapInteractionDeliveryWarning.ENQUEUE_FAILED)
            assertFalse(nonCurrent.shouldRefreshForAcceptedFeedback(accepted))
            nonCurrent.onDetailContent(availableCanonicalContent())
            assertFalse(nonCurrent.shouldRefreshForAcceptedFeedback(accepted))
        }

        val nonAccepted = listOf(
            MapItemDetailShellFeedback.AlreadyRecorded,
            MapItemDetailShellFeedback.InvalidInput(MapInteractionInputFailure.INVALID_COMMAND_SHAPE),
            MapItemDetailShellFeedback.Rejected(MapInteractionRejection.OTHER),
            MapItemDetailShellFeedback.Failed(MapInteractionSubmissionFailure.PERSISTENCE_FAILURE),
            MapItemDetailShellFeedback.InteractionUnavailable,
        )
        listOf(
            MapItemDetailTimelineCoordinator(),
            coordinator(available(emptyList())),
            coordinator(available(emptyList())).also {
                it.onDetailContent(MapItemDetailShellContent.TargetUnavailable)
            },
        ).forEach { coordinator ->
            nonAccepted.forEach { assertFalse(coordinator.shouldRefreshForAcceptedFeedback(it)) }
        }
    }

    @Test fun startupStartsDetailFirstContainsOrdinaryTimelineFailureAndPropagatesFatalError() {
        val calls = mutableListOf<String>()
        MapItemDetailTimelineStartup.start(
            startDetail = { calls += "detail" },
            startTimeline = { calls += "timeline"; throw IllegalStateException("timeline unavailable") },
            onTimelineFailure = { calls += "timeline-failed" },
        )
        assertEquals(listOf("detail", "timeline", "timeline-failed"), calls)

        val fatal = AssertionError("fatal timeline startup")
        assertThrows(AssertionError::class.java) {
            MapItemDetailTimelineStartup.start(
                startDetail = { calls += "detail-again" },
                startTimeline = { throw fatal },
                onTimelineFailure = { calls += "should-not-handle-fatal" },
            )
        }
        assertTrue(calls.contains("detail-again"))
        assertFalse(calls.contains("should-not-handle-fatal"))
    }

    private fun coordinator(state: MapItemTimelineState): MapItemDetailTimelineCoordinator =
        MapItemDetailTimelineCoordinator().also {
            it.onDetailContent(availableCanonicalContent())
            it.onTimelineState(state)
        }

    private fun dispatch(
        coordinator: MapItemDetailTimelineCoordinator,
        onRefreshDetail: () -> Unit = {},
    ): List<String> {
        val calls = mutableListOf<String>()
        coordinator.dispatchManualRefresh(
            refreshDetail = { calls += "detail"; onRefreshDetail() },
            loadTimelineInitial = { calls += "initial" },
            refreshTimelineFirstPage = { calls += "first-page" },
        )
        return calls
    }

    private fun state(content: MapItemTimelineContent) = MapItemTimelineState(IDENTITY, content)

    private fun available(
        entries: List<MapItemActivityEntry>,
        endReached: Boolean = false,
    ) = state(MapItemTimelineContent.Available(entries, endReached))

    private fun entry(id: String) = MapItemActivityEntry(
        entryId = id,
        itemIdentity = IDENTITY,
        type = MapItemActivityType.SITUATION_CONFIRMED,
        actor = MapItemActivityActor("did:actor", "did:actor"),
        actionTimestampSeconds = 100,
        informationSource = null,
        note = null,
        updateDisposition = null,
        targetLifecycle = null,
    )

    private fun availableCanonicalContent() = MapItemDetailShellContent.InvalidDomainEnrichment(
        app.peermesh.map.application.detail.MarkerCanonicalPresentation(
            identity = IDENTITY,
            label = "marker",
            normalizedKind = "situation",
            note = null,
            coordinates = app.peermesh.map.application.detail.MapCoordinatesPresentation(1.0, 2.0),
            radiusMeters = null,
            availability = app.peermesh.map.application.detail.MapItemCanonicalAvailability.ACTIVE,
            expiresAt = null,
        ),
    )

    private companion object {
        val IDENTITY = MapItemIdentity("group", MapItemType.MARKER, "item")
        val FAILURE = MapItemTimelineFailure.INTERNAL_READ_FAILURE
    }
}
