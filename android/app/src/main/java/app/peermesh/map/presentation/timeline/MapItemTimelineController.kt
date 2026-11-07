package app.peermesh.map.presentation.timeline

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.timeline.LoadMapItemActivity
import app.peermesh.map.application.timeline.MapItemActivityCursor
import app.peermesh.map.application.timeline.MapItemActivityEntry
import app.peermesh.map.application.timeline.MapItemActivityLoadResult
import app.peermesh.map.application.timeline.MapItemActivityMergePolicy
import app.peermesh.map.application.timeline.MapItemActivityMergeResult
import app.peermesh.map.application.timeline.MapItemActivityPage

/** Lifecycle owner for one exact map-item timeline. */
interface MapItemTimelineController {
    val currentState: MapItemTimelineState

    fun loadInitial()

    /** Always reloads the first page with after = null. */
    fun refreshFirstPage()

    /** Loads the next older page from the controller-owned opaque append cursor. */
    fun loadOlder()

    fun close()
}

fun interface MapItemTimelineStateObserver {
    fun onStateChanged(state: MapItemTimelineState)
}

/**
 * M3B2 orchestration skeleton.
 *
 * The implementation must keep visible entries and the opaque paging chain as separate ownership.
 * It must not derive continuation from visible rows, timestamps, or merged-list tails.
 */
class DefaultMapItemTimelineController(
    private val identity: MapItemIdentity,
    private val pageSize: Int,
    private val loadMapItemActivity: LoadMapItemActivity,
    private val mergePolicy: MapItemActivityMergePolicy,
    private val execution: MapItemTimelineExecution,
    private val observer: MapItemTimelineStateObserver,
) : MapItemTimelineController {
    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    private val lock = Any()
    private var state = MapItemTimelineState(identity)

    // Visible collection ownership.
    private var entries: List<MapItemActivityEntry> = emptyList()

    // Paging-chain ownership. Never derive this cursor from entries.
    private var appendCursor: MapItemActivityCursor? = null
    private var endReached: Boolean = false

    // Lifecycle/request ownership. Head and append requests must not share one generation token.
    private var closed: Boolean = false
    private var lifecycleEpoch: Long = 0L
    private var headRequestId: Long = 0L
    private var appendRequestId: Long = 0L
    private var pagingChainId: Long = 0L
    private var headTask: MapItemTimelineTask? = null
    private var appendTask: MapItemTimelineTask? = null
    private var headInFlight: Boolean = false
    private var appendInFlight: Boolean = false
    private var hasLoadedOlderPage: Boolean = false
    private var stateVersion: Long = 0L

    override val currentState: MapItemTimelineState
        get() = synchronized(lock) { state }

    override fun loadInitial() {
        val request: HeadRequest
        val publication: StatePublication
        synchronized(lock) {
            if (closed || headInFlight || state.content is MapItemTimelineContent.Available) return
            request = HeadRequest(lifecycleEpoch, ++headRequestId, HeadRequestKind.INITIAL)
            headInFlight = true
            headTask?.cancel()
            headTask = null
            state = state.copy(
                content = MapItemTimelineContent.InitialLoading,
                refresh = MapItemTimelineRefreshState.Idle,
                append = MapItemTimelineAppendState.Idle,
            )
            publication = publicationLocked()
        }
        publish(publication)
        executeHead(request)
    }

    override fun refreshFirstPage() {
        val requestAndPublication = synchronized(lock) {
            if (closed) return
            if (state.content !is MapItemTimelineContent.Available) return@synchronized null

            headTask?.cancel()
            headTask = null
            val request = HeadRequest(lifecycleEpoch, ++headRequestId, HeadRequestKind.REFRESH)
            headInFlight = true
            state = state.copy(refresh = MapItemTimelineRefreshState.Refreshing)
            request to publicationLocked()
        }
        if (requestAndPublication == null) {
            loadInitial()
            return
        }
        publish(requestAndPublication.second)
        executeHead(requestAndPublication.first)
    }

    override fun loadOlder() {
        val request: AppendRequest
        val publication: StatePublication
        synchronized(lock) {
            if (closed || appendInFlight || endReached || state.content !is MapItemTimelineContent.Available) return
            val cursor = appendCursor ?: return
            request = AppendRequest(lifecycleEpoch, ++appendRequestId, pagingChainId, cursor)
            appendInFlight = true
            state = state.copy(append = MapItemTimelineAppendState.Loading)
            publication = publicationLocked()
        }
        publish(publication)
        executeAppend(request)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            lifecycleEpoch++
            headRequestId++
            appendRequestId++
            pagingChainId++
            headInFlight = false
            appendInFlight = false
            headTask?.cancel()
            appendTask?.cancel()
            headTask = null
            appendTask = null
        }
        // Cancellation only suppresses obsolete publication; there is no rollback semantics.
    }

    private fun executeHead(request: HeadRequest) {
        val task = try {
            execution.execute {
                val outcome = loadPage(after = null)
                execution.deliver { completeHead(request, outcome) }
            }
        } catch (_: Exception) {
            execution.deliver {
                completeHead(request, PageOutcome.Failed(MapItemTimelineFailure.INTERNAL_READ_FAILURE))
            }
            return
        }
        synchronized(lock) {
            if (ownsHeadLocked(request)) headTask = task else task.cancel()
        }
    }

    private fun executeAppend(request: AppendRequest) {
        val task = try {
            execution.execute {
                val outcome = loadPage(after = request.cursor)
                execution.deliver { completeAppend(request, outcome) }
            }
        } catch (_: Exception) {
            execution.deliver {
                completeAppend(request, PageOutcome.Failed(MapItemTimelineFailure.INTERNAL_READ_FAILURE))
            }
            return
        }
        synchronized(lock) {
            if (ownsAppendLocked(request)) appendTask = task else task.cancel()
        }
    }

    private fun loadPage(after: MapItemActivityCursor?): PageOutcome = try {
        when (val result = loadMapItemActivity.load(identity, after, pageSize)) {
            is MapItemActivityLoadResult.Loaded -> PageOutcome.Loaded(result.page)
            is MapItemActivityLoadResult.InvalidIdentity -> PageOutcome.Failed(MapItemTimelineFailure.INVALID_IDENTITY)
            MapItemActivityLoadResult.InvalidPersistedHistory ->
                PageOutcome.Failed(MapItemTimelineFailure.INVALID_PERSISTED_HISTORY)
            MapItemActivityLoadResult.InternalReadFailure ->
                PageOutcome.Failed(MapItemTimelineFailure.INTERNAL_READ_FAILURE)
        }
    } catch (_: Exception) {
        PageOutcome.Failed(MapItemTimelineFailure.INTERNAL_READ_FAILURE)
    }

    private fun completeHead(request: HeadRequest, outcome: PageOutcome) {
        val next = synchronized(lock) {
            if (!ownsHeadLocked(request)) return
            headInFlight = false
            headTask = null
            when (request.kind) {
                HeadRequestKind.INITIAL -> completeInitialLocked(outcome)
                HeadRequestKind.REFRESH -> completeRefreshLocked(outcome)
            }
            publicationLocked()
        }
        notifyIfCurrent(next)
    }

    private fun completeInitialLocked(outcome: PageOutcome) {
        entries = emptyList()
        appendCursor = null
        endReached = false
        hasLoadedOlderPage = false
        appendInFlight = false
        appendTask?.cancel()
        appendTask = null
        appendRequestId++
        pagingChainId++

        state = when (outcome) {
            is PageOutcome.Failed -> state.copy(
                content = MapItemTimelineContent.InitialFailure(outcome.failure),
                refresh = MapItemTimelineRefreshState.Idle,
                append = MapItemTimelineAppendState.Idle,
            )
            is PageOutcome.Loaded -> when (val merged = mergeOlder(emptyList(), outcome.page.entries)) {
                MergeOutcome.InternalFailure -> state.copy(
                    content = MapItemTimelineContent.InitialFailure(MapItemTimelineFailure.INTERNAL_READ_FAILURE),
                    refresh = MapItemTimelineRefreshState.Idle,
                    append = MapItemTimelineAppendState.Idle,
                )
                is MergeOutcome.Conflict -> state.copy(
                    content = MapItemTimelineContent.InitialFailure(MapItemTimelineFailure.INVALID_MERGE),
                    refresh = MapItemTimelineRefreshState.Idle,
                    append = MapItemTimelineAppendState.Idle,
                )
                is MergeOutcome.Merged -> {
                    entries = merged.entries
                    appendCursor = outcome.page.nextCursor
                    endReached = isEndReached(outcome.page)
                    state.copy(
                        content = MapItemTimelineContent.Available(entries, endReached),
                        refresh = MapItemTimelineRefreshState.Idle,
                        append = MapItemTimelineAppendState.Idle,
                    )
                }
            }
        }
    }

    private fun completeRefreshLocked(outcome: PageOutcome) {
        state = when (outcome) {
            is PageOutcome.Failed -> state.copy(
                refresh = MapItemTimelineRefreshState.Failed(outcome.failure),
            )
            is PageOutcome.Loaded -> when (val merged = mergeRefreshed(entries, outcome.page.entries)) {
                MergeOutcome.InternalFailure -> state.copy(
                    refresh = MapItemTimelineRefreshState.Failed(MapItemTimelineFailure.INTERNAL_READ_FAILURE),
                )
                is MergeOutcome.Conflict -> state.copy(
                    refresh = MapItemTimelineRefreshState.Failed(MapItemTimelineFailure.INVALID_MERGE),
                )
                is MergeOutcome.Merged -> {
                    entries = merged.entries
                    val rebuiltFirstPageChain = !hasLoadedOlderPage
                    if (rebuiltFirstPageChain) {
                        pagingChainId++
                        appendRequestId++
                        appendInFlight = false
                        appendTask?.cancel()
                        appendTask = null
                        appendCursor = outcome.page.nextCursor
                        endReached = isEndReached(outcome.page)
                    }
                    state.copy(
                        content = MapItemTimelineContent.Available(entries, endReached),
                        refresh = MapItemTimelineRefreshState.Idle,
                        append = if (rebuiltFirstPageChain) MapItemTimelineAppendState.Idle else state.append,
                    )
                }
            }
        }
    }

    private fun completeAppend(request: AppendRequest, outcome: PageOutcome) {
        val next = synchronized(lock) {
            if (!ownsAppendLocked(request)) return
            appendInFlight = false
            appendTask = null
            state = when (outcome) {
                is PageOutcome.Failed -> state.copy(
                    append = MapItemTimelineAppendState.Failed(outcome.failure),
                )
                is PageOutcome.Loaded -> when (val merged = mergeOlder(entries, outcome.page.entries)) {
                    MergeOutcome.InternalFailure -> state.copy(
                        append = MapItemTimelineAppendState.Failed(MapItemTimelineFailure.INTERNAL_READ_FAILURE),
                    )
                    is MergeOutcome.Conflict -> state.copy(
                        append = MapItemTimelineAppendState.Failed(MapItemTimelineFailure.INVALID_MERGE),
                    )
                    is MergeOutcome.Merged -> {
                        entries = merged.entries
                        appendCursor = outcome.page.nextCursor
                        endReached = isEndReached(outcome.page)
                        hasLoadedOlderPage = true
                        pagingChainId++
                        state.copy(
                            content = MapItemTimelineContent.Available(entries, endReached),
                            append = MapItemTimelineAppendState.Idle,
                        )
                    }
                }
            }
            publicationLocked()
        }
        notifyIfCurrent(next)
    }

    private fun mergeRefreshed(
        existing: List<MapItemActivityEntry>,
        page: List<MapItemActivityEntry>,
    ): MergeOutcome = try {
        mergePolicy.mergeRefreshedHead(existing, page).toOutcome()
    } catch (_: Exception) {
        MergeOutcome.InternalFailure
    }

    private fun mergeOlder(
        existing: List<MapItemActivityEntry>,
        page: List<MapItemActivityEntry>,
    ): MergeOutcome = try {
        mergePolicy.mergeOlderPage(existing, page).toOutcome()
    } catch (_: Exception) {
        MergeOutcome.InternalFailure
    }

    private fun MapItemActivityMergeResult.toOutcome(): MergeOutcome = when (this) {
        is MapItemActivityMergeResult.Merged -> MergeOutcome.Merged(entries)
        is MapItemActivityMergeResult.ConflictingEntry -> MergeOutcome.Conflict(entryId)
    }

    private fun isEndReached(page: MapItemActivityPage): Boolean =
        page.entries.size < page.requestedLimit || page.nextCursor == null

    private fun ownsHeadLocked(request: HeadRequest): Boolean =
        !closed && request.lifecycleEpoch == lifecycleEpoch && request.requestId == headRequestId && headInFlight

    private fun ownsAppendLocked(request: AppendRequest): Boolean =
        !closed && request.lifecycleEpoch == lifecycleEpoch && request.requestId == appendRequestId &&
            request.pagingChainId == pagingChainId && appendInFlight

    private fun publicationLocked(): StatePublication =
        StatePublication(state, lifecycleEpoch, ++stateVersion)

    private fun publish(publication: StatePublication) {
        execution.deliver { notifyIfCurrent(publication) }
    }

    private fun notifyIfCurrent(publication: StatePublication) {
        val shouldNotify = synchronized(lock) {
            !closed && publication.lifecycleEpoch == lifecycleEpoch &&
                publication.stateVersion == stateVersion && publication.state == state
        }
        if (shouldNotify) observer.onStateChanged(publication.state)
    }

    private data class HeadRequest(
        val lifecycleEpoch: Long,
        val requestId: Long,
        val kind: HeadRequestKind,
    )

    private enum class HeadRequestKind { INITIAL, REFRESH }

    private data class AppendRequest(
        val lifecycleEpoch: Long,
        val requestId: Long,
        val pagingChainId: Long,
        val cursor: MapItemActivityCursor,
    )

    private data class StatePublication(
        val state: MapItemTimelineState,
        val lifecycleEpoch: Long,
        val stateVersion: Long,
    )

    private sealed interface PageOutcome {
        data class Loaded(val page: MapItemActivityPage) : PageOutcome
        data class Failed(val failure: MapItemTimelineFailure) : PageOutcome
    }

    private sealed interface MergeOutcome {
        data class Merged(val entries: List<MapItemActivityEntry>) : MergeOutcome
        data class Conflict(val entryId: String) : MergeOutcome
        data object InternalFailure : MergeOutcome
    }
}

/** Explicit seam for blocking one-shot reads and presentation-thread delivery. */
interface MapItemTimelineExecution {
    fun execute(block: () -> Unit): MapItemTimelineTask

    fun deliver(block: () -> Unit)
}

fun interface MapItemTimelineTask {
    fun cancel()
}
