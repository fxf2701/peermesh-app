package app.peermesh.map.presentation.timeline

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.timeline.DefaultMapItemActivityMergePolicy
import app.peermesh.map.application.timeline.LoadMapItemActivity
import app.peermesh.map.application.timeline.MapItemActivityActor
import app.peermesh.map.application.timeline.MapItemActivityCursor
import app.peermesh.map.application.timeline.MapItemActivityEntry
import app.peermesh.map.application.timeline.MapItemActivityLoadResult
import app.peermesh.map.application.timeline.MapItemActivityMergePolicy
import app.peermesh.map.application.timeline.MapItemActivityMergeResult
import app.peermesh.map.application.timeline.MapItemActivityPage
import app.peermesh.map.application.timeline.MapItemActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMapItemTimelineControllerM3B2Test {
    @Test fun initialSuccessPublishesLoadingThenAvailableAndPreservesOpaqueCursor() {
        val fixture = fixture(loaded(page(listOf(entry("new"), entry("old")), CursorA)))

        fixture.controller.loadInitial()
        fixture.execution.drainDeliveries()
        assertTrue(fixture.states.single().content is MapItemTimelineContent.InitialLoading)
        fixture.execution.runWork()
        fixture.execution.drainDeliveries()

        assertEquals(listOf("new", "old"), fixture.availableIds())
        assertFalse(fixture.available().endReached)
        fixture.loader.enqueue(loaded(page(emptyList(), null)))
        fixture.controller.loadOlder()
        fixture.execution.drainDeliveries()
        fixture.execution.runWork()
        assertSame(CursorA, fixture.loader.calls.last().after)
        assertEquals(PAGE_SIZE, fixture.loader.calls.last().limit)
    }

    @Test fun initialFailureEmptyPageAndConflictingPageHaveDistinctDeterministicStates() {
        val failed = fixture(MapItemActivityLoadResult.InvalidPersistedHistory)
        failed.completeInitial()
        assertEquals(
            MapItemTimelineContent.InitialFailure(MapItemTimelineFailure.INVALID_PERSISTED_HISTORY),
            failed.controller.currentState.content,
        )

        val empty = fixture(loaded(page(emptyList(), null)))
        empty.completeInitial()
        assertTrue(empty.available().entries.isEmpty())
        assertTrue(empty.available().endReached)

        val original = entry("same")
        val conflict = original.copy(note = "conflict")
        val invalid = fixture(loaded(page(listOf(original, conflict), CursorA)))
        invalid.completeInitial()
        assertEquals(
            MapItemTimelineContent.InitialFailure(MapItemTimelineFailure.INVALID_MERGE),
            invalid.controller.currentState.content,
        )
    }

    @Test fun duplicateInitialAndAppendRequestsAreIgnoredAndEndPreventsFurtherReads() {
        val fixture = fixture(loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.controller.loadInitial()
        fixture.controller.loadInitial()
        assertEquals(1, fixture.execution.workCount)
        fixture.execution.drainDeliveries()
        fixture.execution.runWork()
        fixture.execution.drainDeliveries()

        fixture.loader.enqueue(loaded(page(emptyList(), null)))
        fixture.controller.loadOlder()
        fixture.controller.loadOlder()
        assertEquals(1, fixture.execution.workCount)
        fixture.execution.drainDeliveries()
        fixture.execution.runWork()
        fixture.execution.drainDeliveries()
        assertTrue(fixture.available().endReached)

        fixture.controller.loadOlder()
        assertEquals(2, fixture.loader.calls.size)
        assertEquals(0, fixture.execution.workCount)
    }

    @Test fun appendUsesRawPageForEndDetectionEvenWhenEveryEntryIsDuplicate() {
        val a = entry("a")
        val b = entry("b")
        val fixture = fixture(loaded(page(listOf(a, b), CursorA)))
        fixture.completeInitial()
        fixture.loader.enqueue(loaded(page(listOf(a, b), CursorB)))

        fixture.completeAppend()

        assertEquals(listOf("a", "b"), fixture.availableIds())
        assertFalse(fixture.available().endReached)
        fixture.loader.enqueue(loaded(page(emptyList(), null)))
        fixture.controller.loadOlder()
        fixture.execution.drainDeliveries()
        fixture.execution.runWork()
        assertSame(CursorB, fixture.loader.calls.last().after)
    }

    @Test fun appendFailureAndConflictPreserveEntriesCursorAndAllowRetry() {
        val fixture = fixture(loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        fixture.loader.enqueue(MapItemActivityLoadResult.InternalReadFailure)
        fixture.completeAppend()
        assertEquals(listOf("a", "b"), fixture.availableIds())
        assertEquals(
            MapItemTimelineAppendState.Failed(MapItemTimelineFailure.INTERNAL_READ_FAILURE),
            fixture.controller.currentState.append,
        )

        fixture.loader.enqueue(loaded(page(listOf(entry("a").copy(note = "conflict"), entry("c")), CursorB)))
        fixture.completeAppend()
        assertEquals(listOf("a", "b"), fixture.availableIds())
        assertEquals(
            MapItemTimelineAppendState.Failed(MapItemTimelineFailure.INVALID_MERGE),
            fixture.controller.currentState.append,
        )

        fixture.loader.enqueue(loaded(page(listOf(entry("c")), null)))
        fixture.completeAppend()
        assertEquals(listOf("a", "b", "c"), fixture.availableIds())
        assertEquals(MapItemTimelineAppendState.Idle, fixture.controller.currentState.append)
        assertTrue(fixture.available().endReached)
        assertSame(CursorA, fixture.loader.calls[1].after)
        assertSame(CursorA, fixture.loader.calls[2].after)
        assertSame(CursorA, fixture.loader.calls[3].after)
    }

    @Test fun refreshAlwaysUsesNullAndRebuildsCursorBeforeAnyOlderPageWasLoaded() {
        val fixture = fixture(loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        fixture.loader.enqueue(loaded(page(listOf(entry("new"), entry("a")), CursorB)))

        fixture.completeRefresh()

        assertEquals(listOf("new", "a", "b"), fixture.availableIds())
        assertEquals(null, fixture.loader.calls[1].after)
        fixture.loader.enqueue(loaded(page(emptyList(), null)))
        fixture.controller.loadOlder()
        fixture.execution.drainDeliveries()
        fixture.execution.runWork()
        assertSame(CursorB, fixture.loader.calls.last().after)
    }

    @Test fun refreshAfterAppendPreservesOldestCursorAndOlderEntries() {
        val fixture = fixture(loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        fixture.loader.enqueue(loaded(page(listOf(entry("c"), entry("d")), CursorB)))
        fixture.completeAppend()
        fixture.loader.enqueue(loaded(page(listOf(entry("new"), entry("a")), CursorHead)))

        fixture.completeRefresh()

        assertEquals(listOf("new", "a", "b", "c", "d"), fixture.availableIds())
        fixture.loader.enqueue(loaded(page(emptyList(), null)))
        fixture.controller.loadOlder()
        fixture.execution.drainDeliveries()
        fixture.execution.runWork()
        assertSame(CursorB, fixture.loader.calls.last().after)
    }

    @Test fun refreshDoesNotClearIndependentAppendFailureOnPreservedOlderChain() {
        val fixture = fixture(loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        fixture.loader.enqueue(loaded(page(listOf(entry("c"), entry("d")), CursorB)))
        fixture.completeAppend()
        fixture.loader.enqueue(MapItemActivityLoadResult.InternalReadFailure)
        fixture.completeAppend()
        val appendFailure = fixture.controller.currentState.append
        fixture.loader.enqueue(loaded(page(listOf(entry("new"), entry("a")), CursorHead)))

        fixture.completeRefresh()

        assertEquals(appendFailure, fixture.controller.currentState.append)
        assertEquals(listOf("new", "a", "b", "c", "d"), fixture.availableIds())
    }

    @Test fun refreshFailureAndConflictPreserveVisibleContentAndPagingBoundary() {
        val fixture = fixture(loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        fixture.loader.enqueue(MapItemActivityLoadResult.InternalReadFailure)
        fixture.completeRefresh()
        assertEquals(listOf("a", "b"), fixture.availableIds())
        assertEquals(
            MapItemTimelineRefreshState.Failed(MapItemTimelineFailure.INTERNAL_READ_FAILURE),
            fixture.controller.currentState.refresh,
        )

        fixture.loader.enqueue(loaded(page(listOf(entry("a").copy(note = "conflict"), entry("x")), CursorB)))
        fixture.completeRefresh()
        assertEquals(listOf("a", "b"), fixture.availableIds())
        assertEquals(
            MapItemTimelineRefreshState.Failed(MapItemTimelineFailure.INVALID_MERGE),
            fixture.controller.currentState.refresh,
        )

        fixture.loader.enqueue(loaded(page(emptyList(), null)))
        fixture.controller.loadOlder()
        fixture.execution.drainDeliveries()
        fixture.execution.runWork()
        assertSame(CursorA, fixture.loader.calls.last().after)
    }

    @Test fun newerRefreshOwnsHeadAndStaleRefreshCannotPublish() {
        val fixture = fixture(loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        fixture.loader.enqueue(loaded(page(listOf(entry("stale"), entry("a")), CursorB)))
        fixture.loader.enqueue(loaded(page(listOf(entry("latest"), entry("a")), CursorHead)))
        fixture.controller.refreshFirstPage()
        fixture.controller.refreshFirstPage()
        fixture.execution.drainDeliveries()

        fixture.execution.runWork(0, ignoreCancellation = true)
        fixture.execution.drainDeliveries()
        assertEquals(listOf("a", "b"), fixture.availableIds())
        fixture.execution.runWork(0)
        fixture.execution.drainDeliveries()
        assertEquals(listOf("latest", "a", "b"), fixture.availableIds())
    }

    @Test fun refreshInvalidatesAppendFromOldFirstPageChainEvenWhenCancellationFails() {
        val fixture = fixture(loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        fixture.loader.enqueue(loaded(page(listOf(entry("new"), entry("a")), CursorB)))
        fixture.loader.enqueue(loaded(page(listOf(entry("stale-old"), entry("older")), CursorHead)))
        fixture.controller.loadOlder()
        fixture.controller.refreshFirstPage()
        fixture.execution.drainDeliveries()

        fixture.execution.runWork(1)
        fixture.execution.drainDeliveries()
        assertEquals(listOf("new", "a", "b"), fixture.availableIds())
        fixture.execution.runWork(0, ignoreCancellation = true)
        fixture.execution.drainDeliveries()
        assertEquals(listOf("new", "a", "b"), fixture.availableIds())
    }

    @Test fun appendStartedBeforeRefreshRemainsValidAfterOlderChainExistsAndKeepsRefreshedHead() {
        val fixture = fixture(loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        fixture.loader.enqueue(loaded(page(listOf(entry("c"), entry("d")), CursorB)))
        fixture.completeAppend()

        fixture.loader.enqueue(loaded(page(listOf(entry("new"), entry("a")), CursorHead)))
        fixture.loader.enqueue(loaded(page(listOf(entry("e"), entry("f")), CursorC)))
        fixture.controller.loadOlder()
        fixture.controller.refreshFirstPage()
        fixture.execution.drainDeliveries()
        fixture.execution.runWork(1)
        fixture.execution.drainDeliveries()
        assertTrue(fixture.controller.currentState.append is MapItemTimelineAppendState.Loading)
        fixture.execution.runWork(0)
        fixture.execution.drainDeliveries()

        assertEquals(listOf("new", "a", "b", "c", "d", "e", "f"), fixture.availableIds())
        assertSame(CursorB, fixture.loader.calls[3].after)
    }

    @Test fun closeIsIdempotentCancelsTasksAndSuppressesAllLatePublication() {
        val fixture = fixture(loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.controller.loadInitial()
        fixture.execution.drainDeliveries()
        val publishedBeforeClose = fixture.states.size
        fixture.controller.close()
        fixture.controller.close()
        assertTrue(fixture.execution.tasks.single().cancelled)
        fixture.execution.runWork(ignoreCancellation = true)
        fixture.execution.drainDeliveries()
        assertEquals(publishedBeforeClose, fixture.states.size)
    }

    @Test fun ordinaryExecutionFailureMapsToInitialFailureWhileFatalErrorPropagates() {
        val executionFailure = fixture(loaded(page(emptyList(), null)))
        executionFailure.execution.throwOnExecute = IllegalStateException("rejected")
        executionFailure.controller.loadInitial()
        executionFailure.execution.drainDeliveries()
        assertEquals(
            MapItemTimelineContent.InitialFailure(MapItemTimelineFailure.INTERNAL_READ_FAILURE),
            executionFailure.controller.currentState.content,
        )

        val fatal = AssertionError("fatal")
        val loader = FakeLoader().apply { enqueue { throw fatal } }
        val controller = controller(loader, ImmediateExecution(), mutableListOf())
        val thrown = assertThrows(AssertionError::class.java) { controller.loadInitial() }
        assertSame(fatal, thrown)
    }

    @Test fun initialMergeExceptionBecomesInternalFailureWithoutPartialContentAndFatalMergeErrorPropagates() {
        val policy = ControlledMergePolicy().apply { olderFailure = IllegalStateException("merge unavailable") }
        val failed = fixtureWith(policy, loaded(page(listOf(entry("a"), entry("b")), CursorA)))

        failed.completeInitial()

        assertEquals(
            MapItemTimelineContent.InitialFailure(MapItemTimelineFailure.INTERNAL_READ_FAILURE),
            failed.controller.currentState.content,
        )
        assertFalse(failed.states.any { it.content is MapItemTimelineContent.Available })

        val fatal = AssertionError("fatal merge")
        val fatalPolicy = ControlledMergePolicy().apply { olderFailure = fatal }
        val fatalController = controller(
            loader = FakeLoader().apply { enqueue(loaded(page(listOf(entry("a"), entry("b")), CursorA))) },
            execution = ImmediateExecution(),
            states = mutableListOf(),
            mergePolicy = fatalPolicy,
        )
        assertSame(fatal, assertThrows(AssertionError::class.java) { fatalController.loadInitial() })
    }

    @Test fun refreshMergeExceptionPreservesPagingStateAndCanRetry() {
        val policy = ControlledMergePolicy()
        val fixture = fixtureWith(policy, loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        policy.refreshedFailure = IllegalStateException("refresh merge unavailable")
        fixture.loader.enqueue(loaded(page(listOf(entry("new"), entry("a")), CursorB)))

        fixture.completeRefresh()

        assertEquals(listOf("a", "b"), fixture.availableIds())
        assertFalse(fixture.available().endReached)
        assertEquals(
            MapItemTimelineRefreshState.Failed(MapItemTimelineFailure.INTERNAL_READ_FAILURE),
            fixture.controller.currentState.refresh,
        )
        policy.refreshedFailure = null
        fixture.loader.enqueue(loaded(page(listOf(entry("new"), entry("a")), CursorB)))
        fixture.completeRefresh()
        assertEquals(listOf("new", "a", "b"), fixture.availableIds())
        assertEquals(MapItemTimelineRefreshState.Idle, fixture.controller.currentState.refresh)
    }

    @Test fun appendMergeExceptionPreservesPagingStateAndRetryUsesPriorCursor() {
        val policy = ControlledMergePolicy()
        val fixture = fixtureWith(policy, loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        policy.olderFailure = IllegalStateException("append merge unavailable")
        fixture.loader.enqueue(loaded(page(listOf(entry("c"), entry("d")), CursorB)))

        fixture.completeAppend()

        assertEquals(listOf("a", "b"), fixture.availableIds())
        assertFalse(fixture.available().endReached)
        assertEquals(
            MapItemTimelineAppendState.Failed(MapItemTimelineFailure.INTERNAL_READ_FAILURE),
            fixture.controller.currentState.append,
        )
        policy.olderFailure = null
        fixture.loader.enqueue(loaded(page(listOf(entry("c"), entry("d")), CursorB)))
        fixture.completeAppend()
        assertSame(CursorA, fixture.loader.calls[1].after)
        assertSame(CursorA, fixture.loader.calls[2].after)
        assertEquals(listOf("a", "b", "c", "d"), fixture.availableIds())
    }

    @Test fun returnedRealMergeConflictStillMapsToInvalidMerge() {
        val policy = ControlledMergePolicy().apply {
            refreshedResult = MapItemActivityMergeResult.ConflictingEntry("actual-id")
        }
        val fixture = fixtureWith(policy, loaded(page(listOf(entry("a"), entry("b")), CursorA)))
        fixture.completeInitial()
        fixture.loader.enqueue(loaded(page(listOf(entry("new"), entry("a")), CursorB)))

        fixture.completeRefresh()

        assertEquals(
            MapItemTimelineRefreshState.Failed(MapItemTimelineFailure.INVALID_MERGE),
            fixture.controller.currentState.refresh,
        )
        assertEquals(listOf("a", "b"), fixture.availableIds())
    }

    private fun fixture(vararg results: MapItemActivityLoadResult): Fixture {
        return fixtureWith(DefaultMapItemActivityMergePolicy(), *results)
    }

    private fun fixtureWith(
        mergePolicy: MapItemActivityMergePolicy,
        vararg results: MapItemActivityLoadResult,
    ): Fixture {
        val loader = FakeLoader().apply { results.forEach(::enqueue) }
        val execution = ControlledExecution()
        val states = mutableListOf<MapItemTimelineState>()
        return Fixture(loader, execution, states, controller(loader, execution, states, mergePolicy))
    }

    private fun controller(
        loader: LoadMapItemActivity,
        execution: MapItemTimelineExecution,
        states: MutableList<MapItemTimelineState>,
        mergePolicy: MapItemActivityMergePolicy = DefaultMapItemActivityMergePolicy(),
    ) = DefaultMapItemTimelineController(
        identity = IDENTITY,
        pageSize = PAGE_SIZE,
        loadMapItemActivity = loader,
        mergePolicy = mergePolicy,
        execution = execution,
        observer = MapItemTimelineStateObserver(states::add),
    )

    private data class Fixture(
        val loader: FakeLoader,
        val execution: ControlledExecution,
        val states: MutableList<MapItemTimelineState>,
        val controller: DefaultMapItemTimelineController,
    ) {
        fun completeInitial() {
            controller.loadInitial()
            execution.drainDeliveries()
            execution.runWork()
            execution.drainDeliveries()
        }

        fun completeRefresh() {
            controller.refreshFirstPage()
            execution.drainDeliveries()
            execution.runWork()
            execution.drainDeliveries()
        }

        fun completeAppend() {
            controller.loadOlder()
            execution.drainDeliveries()
            execution.runWork()
            execution.drainDeliveries()
        }

        fun available() = controller.currentState.content as MapItemTimelineContent.Available
        fun availableIds() = available().entries.map { it.entryId }
    }

    private class FakeLoader : LoadMapItemActivity {
        val calls = mutableListOf<Call>()
        private val outcomes = ArrayDeque<() -> MapItemActivityLoadResult>()

        fun enqueue(result: MapItemActivityLoadResult) = enqueue { result }
        fun enqueue(outcome: () -> MapItemActivityLoadResult) {
            outcomes.addLast(outcome)
        }

        override fun load(
            identity: MapItemIdentity,
            after: MapItemActivityCursor?,
            limit: Int,
        ): MapItemActivityLoadResult {
            calls += Call(identity, after, limit)
            return outcomes.removeFirst().invoke()
        }
    }

    private class ControlledMergePolicy : MapItemActivityMergePolicy {
        private val delegate = DefaultMapItemActivityMergePolicy()
        var refreshedFailure: Throwable? = null
        var olderFailure: Throwable? = null
        var refreshedResult: MapItemActivityMergeResult? = null

        override fun mergeRefreshedHead(
            existing: List<MapItemActivityEntry>,
            refreshedPage: List<MapItemActivityEntry>,
        ): MapItemActivityMergeResult {
            refreshedFailure?.let { throw it }
            return refreshedResult ?: delegate.mergeRefreshedHead(existing, refreshedPage)
        }

        override fun mergeOlderPage(
            existing: List<MapItemActivityEntry>,
            olderPage: List<MapItemActivityEntry>,
        ): MapItemActivityMergeResult {
            olderFailure?.let { throw it }
            return delegate.mergeOlderPage(existing, olderPage)
        }
    }

    private data class Call(
        val identity: MapItemIdentity,
        val after: MapItemActivityCursor?,
        val limit: Int,
    )

    private class ControlledExecution : MapItemTimelineExecution {
        val tasks = mutableListOf<Task>()
        private val work = mutableListOf<Task>()
        private val deliveries = ArrayDeque<() -> Unit>()
        var throwOnExecute: Exception? = null
        val workCount: Int get() = work.size

        override fun execute(block: () -> Unit): MapItemTimelineTask {
            throwOnExecute?.let { throw it }
            val task = Task(block)
            tasks += task
            work += task
            return task
        }

        override fun deliver(block: () -> Unit) {
            deliveries.addLast(block)
        }

        fun runWork(index: Int = 0, ignoreCancellation: Boolean = false) {
            val task = work.removeAt(index)
            if (!task.cancelled || ignoreCancellation) task.block()
        }

        fun drainDeliveries() {
            while (deliveries.isNotEmpty()) deliveries.removeFirst().invoke()
        }

        class Task(val block: () -> Unit) : MapItemTimelineTask {
            var cancelled = false
            override fun cancel() {
                cancelled = true
            }
        }
    }

    private class ImmediateExecution : MapItemTimelineExecution {
        override fun execute(block: () -> Unit): MapItemTimelineTask {
            block()
            return MapItemTimelineTask {}
        }

        override fun deliver(block: () -> Unit) = block()
    }

    private fun loaded(page: MapItemActivityPage) = MapItemActivityLoadResult.Loaded(page)

    private fun page(
        entries: List<MapItemActivityEntry>,
        cursor: MapItemActivityCursor?,
    ) = MapItemActivityPage(entries, cursor, PAGE_SIZE)

    private fun entry(id: String) = MapItemActivityEntry(
        entryId = id,
        itemIdentity = IDENTITY,
        type = MapItemActivityType.SITUATION_CONFIRMED,
        actor = MapItemActivityActor("did:$id", "did:$id"),
        actionTimestampSeconds = id.hashCode().toLong().let { if (it > 0) it else 1L },
        informationSource = null,
        note = null,
        updateDisposition = null,
        targetLifecycle = null,
    )

    private data object CursorA : MapItemActivityCursor
    private data object CursorB : MapItemActivityCursor
    private data object CursorC : MapItemActivityCursor
    private data object CursorHead : MapItemActivityCursor

    private companion object {
        const val PAGE_SIZE = 2
        val IDENTITY = MapItemIdentity("group", MapItemType.MARKER, "item")
    }
}
