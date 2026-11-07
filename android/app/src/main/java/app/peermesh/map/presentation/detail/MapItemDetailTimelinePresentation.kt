package app.peermesh.map.presentation.detail

import app.peermesh.map.application.timeline.MapItemActivityEntry
import app.peermesh.map.presentation.timeline.MapItemTimelineAppendState
import app.peermesh.map.presentation.timeline.MapItemTimelineContent
import app.peermesh.map.presentation.timeline.MapItemTimelineRefreshState
import app.peermesh.map.presentation.timeline.MapItemTimelineState

internal enum class MapItemTimelineTargetMode {
    SUPPRESSED,
    CURRENT_TARGET_AVAILABLE,
    RETAINED_HISTORY,
}

internal enum class MapItemTimelineStatus {
    NONE,
    LOADING,
    EMPTY,
    REFRESHING,
    INITIAL_FAILURE,
    REFRESH_FAILURE,
    APPEND_FAILURE,
    END_REACHED,
}

internal enum class MapItemTimelineRetryOperation {
    START_TIMELINE,
    LOAD_INITIAL,
    REFRESH_FIRST_PAGE,
}

/** Typed command selected only for the user-requested refresh gesture. */
internal enum class MapItemTimelineManualRefreshOperation {
    LOAD_INITIAL,
    REFRESH_FIRST_PAGE,
    NONE,
}

internal enum class MapItemTimelineOlderAction {
    HIDDEN,
    LOAD,
    LOADING,
    RETRY,
}

/** Immutable Android-view instructions; no paging or domain state is owned here. */
internal data class MapItemTimelinePresentation(
    val visible: Boolean,
    val retainedHistory: Boolean,
    val entries: List<MapItemActivityEntry>,
    val status: MapItemTimelineStatus,
    val retryOperation: MapItemTimelineRetryOperation?,
    val olderAction: MapItemTimelineOlderAction,
) {
    val loadOlderEnabled: Boolean
        get() = olderAction == MapItemTimelineOlderAction.LOAD ||
            olderAction == MapItemTimelineOlderAction.RETRY
}

/**
 * Narrow presentation coordination for independent detail and timeline state owners.
 *
 * It records only snapshots already accepted while the current canonical target was available.
 * On a later unavailable transition that snapshot is frozen; late paging publications therefore
 * cannot alter retained history.
 */
internal class MapItemDetailTimelineCoordinator {
    private var targetMode = MapItemTimelineTargetMode.SUPPRESSED
    private var timelineState: MapItemTimelineState? = null
    private var latestAcceptedEntries: List<MapItemActivityEntry> = emptyList()
    private var retainedEntries: List<MapItemActivityEntry>? = null
    private var timelineStartupFailed = false
    private var handledAccepted: MapItemDetailShellFeedback.Accepted? = null

    fun onDetailContent(content: MapItemDetailShellContent): MapItemTimelinePresentation {
        val previousMode = targetMode
        targetMode = when (content) {
            is MapItemDetailShellContent.Loaded,
            is MapItemDetailShellContent.InvalidDomainEnrichment,
            -> MapItemTimelineTargetMode.CURRENT_TARGET_AVAILABLE

            MapItemDetailShellContent.TargetUnavailable -> if (
                previousMode == MapItemTimelineTargetMode.CURRENT_TARGET_AVAILABLE ||
                previousMode == MapItemTimelineTargetMode.RETAINED_HISTORY
            ) {
                MapItemTimelineTargetMode.RETAINED_HISTORY
            } else {
                MapItemTimelineTargetMode.SUPPRESSED
            }

            MapItemDetailShellContent.InitialLoading,
            MapItemDetailShellContent.ReadFailure,
            MapItemDetailShellContent.InvalidItemData,
            -> previousMode
        }
        if (previousMode == MapItemTimelineTargetMode.CURRENT_TARGET_AVAILABLE &&
            targetMode == MapItemTimelineTargetMode.RETAINED_HISTORY
        ) {
            retainedEntries = latestAcceptedEntries.toList()
        } else if (previousMode == MapItemTimelineTargetMode.RETAINED_HISTORY &&
            targetMode == MapItemTimelineTargetMode.CURRENT_TARGET_AVAILABLE
        ) {
            retainedEntries = null
            acceptCurrentTimelineEntries()
        } else if (targetMode == MapItemTimelineTargetMode.CURRENT_TARGET_AVAILABLE) {
            acceptCurrentTimelineEntries()
        }
        return presentation()
    }

    fun onTimelineState(state: MapItemTimelineState): MapItemTimelinePresentation {
        timelineState = state
        timelineStartupFailed = false
        if (targetMode == MapItemTimelineTargetMode.CURRENT_TARGET_AVAILABLE) {
            acceptCurrentTimelineEntries()
        }
        return presentation()
    }

    fun onTimelineStartupFailure(): MapItemTimelinePresentation {
        timelineState = null
        timelineStartupFailed = true
        return presentation()
    }

    fun manualRefreshOperation(): MapItemTimelineManualRefreshOperation {
        if (targetMode != MapItemTimelineTargetMode.CURRENT_TARGET_AVAILABLE) {
            return MapItemTimelineManualRefreshOperation.NONE
        }
        return when (timelineState?.content) {
            MapItemTimelineContent.NotLoaded,
            is MapItemTimelineContent.InitialFailure,
            -> MapItemTimelineManualRefreshOperation.LOAD_INITIAL
            MapItemTimelineContent.InitialLoading,
            null,
            -> MapItemTimelineManualRefreshOperation.NONE
            is MapItemTimelineContent.Available -> if (timelineState?.refresh is MapItemTimelineRefreshState.Refreshing) {
                MapItemTimelineManualRefreshOperation.NONE
            } else {
                MapItemTimelineManualRefreshOperation.REFRESH_FIRST_PAGE
            }
        }
    }

    fun dispatchManualRefresh(
        refreshDetail: () -> Unit,
        loadTimelineInitial: () -> Unit,
        refreshTimelineFirstPage: () -> Unit,
    ) {
        val timelineOperation = manualRefreshOperation()
        refreshDetail()
        when (timelineOperation) {
            MapItemTimelineManualRefreshOperation.LOAD_INITIAL -> loadTimelineInitial()
            MapItemTimelineManualRefreshOperation.REFRESH_FIRST_PAGE -> refreshTimelineFirstPage()
            MapItemTimelineManualRefreshOperation.NONE -> Unit
        }
    }

    fun shouldRefreshForAcceptedFeedback(feedback: MapItemDetailShellFeedback): Boolean {
        if (feedback !is MapItemDetailShellFeedback.Accepted || feedback === handledAccepted) return false
        handledAccepted = feedback
        return targetMode == MapItemTimelineTargetMode.CURRENT_TARGET_AVAILABLE
    }

    private fun acceptCurrentTimelineEntries() {
        val available = timelineState?.content as? MapItemTimelineContent.Available ?: return
        latestAcceptedEntries = available.entries.toList()
    }

    private fun presentation(): MapItemTimelinePresentation {
        if (targetMode == MapItemTimelineTargetMode.SUPPRESSED) return hiddenPresentation()
        if (targetMode == MapItemTimelineTargetMode.RETAINED_HISTORY) {
            val frozen = retainedEntries.orEmpty()
            return MapItemTimelinePresentation(
                visible = true,
                retainedHistory = true,
                entries = frozen,
                status = if (frozen.isEmpty()) MapItemTimelineStatus.EMPTY else MapItemTimelineStatus.NONE,
                retryOperation = null,
                olderAction = MapItemTimelineOlderAction.HIDDEN,
            )
        }
        if (timelineStartupFailed) {
            return MapItemTimelinePresentation(
                visible = true,
                retainedHistory = false,
                entries = emptyList(),
                status = MapItemTimelineStatus.INITIAL_FAILURE,
                retryOperation = MapItemTimelineRetryOperation.START_TIMELINE,
                olderAction = MapItemTimelineOlderAction.HIDDEN,
            )
        }
        val state = timelineState ?: return hiddenPresentation()
        return currentTargetPresentation(state)
    }

    private fun currentTargetPresentation(state: MapItemTimelineState): MapItemTimelinePresentation {
        val content = state.content
        val entries = (content as? MapItemTimelineContent.Available)?.entries.orEmpty().toList()
        val status = when {
            content is MapItemTimelineContent.NotLoaded ||
                content is MapItemTimelineContent.InitialLoading -> MapItemTimelineStatus.LOADING
            content is MapItemTimelineContent.InitialFailure -> MapItemTimelineStatus.INITIAL_FAILURE
            state.refresh is MapItemTimelineRefreshState.Failed -> MapItemTimelineStatus.REFRESH_FAILURE
            state.refresh is MapItemTimelineRefreshState.Refreshing -> MapItemTimelineStatus.REFRESHING
            content is MapItemTimelineContent.Available &&
                state.append is MapItemTimelineAppendState.Failed -> MapItemTimelineStatus.APPEND_FAILURE
            content is MapItemTimelineContent.Available && content.entries.isEmpty() -> MapItemTimelineStatus.EMPTY
            content is MapItemTimelineContent.Available && content.endReached -> MapItemTimelineStatus.END_REACHED
            else -> MapItemTimelineStatus.NONE
        }
        val retryOperation = when (status) {
            MapItemTimelineStatus.INITIAL_FAILURE -> MapItemTimelineRetryOperation.LOAD_INITIAL
            MapItemTimelineStatus.REFRESH_FAILURE -> MapItemTimelineRetryOperation.REFRESH_FIRST_PAGE
            else -> null
        }
        val olderAction = when {
            content !is MapItemTimelineContent.Available || content.endReached -> MapItemTimelineOlderAction.HIDDEN
            state.append is MapItemTimelineAppendState.Loading -> MapItemTimelineOlderAction.LOADING
            state.append is MapItemTimelineAppendState.Failed -> MapItemTimelineOlderAction.RETRY
            else -> MapItemTimelineOlderAction.LOAD
        }
        return MapItemTimelinePresentation(true, false, entries, status, retryOperation, olderAction)
    }

    private fun hiddenPresentation() = MapItemTimelinePresentation(
        visible = false,
        retainedHistory = false,
        entries = emptyList(),
        status = MapItemTimelineStatus.NONE,
        retryOperation = null,
        olderAction = MapItemTimelineOlderAction.HIDDEN,
    )
}

/** Starts independent read models in order while containing only ordinary timeline startup failures. */
internal object MapItemDetailTimelineStartup {
    fun start(
        startDetail: () -> Unit,
        startTimeline: () -> Unit,
        onTimelineFailure: () -> Unit,
    ) {
        startDetail()
        try {
            startTimeline()
        } catch (_: Exception) {
            onTimelineFailure()
        }
    }
}
