package app.peermesh.map.presentation.timeline

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.timeline.MapItemActivityEntry

/**
 * Presentation-owned timeline state for one exact map-item identity.
 *
 * Content, refresh, and append are intentionally orthogonal so historical entries remain visible
 * during refresh and append work or failure.
 */
data class MapItemTimelineState(
    val identity: MapItemIdentity,
    val content: MapItemTimelineContent = MapItemTimelineContent.NotLoaded,
    val refresh: MapItemTimelineRefreshState = MapItemTimelineRefreshState.Idle,
    val append: MapItemTimelineAppendState = MapItemTimelineAppendState.Idle,
)

sealed interface MapItemTimelineContent {
    data object NotLoaded : MapItemTimelineContent
    data object InitialLoading : MapItemTimelineContent

    data class Available(
        val entries: List<MapItemActivityEntry>,
        val endReached: Boolean,
    ) : MapItemTimelineContent

    data class InitialFailure(val failure: MapItemTimelineFailure) : MapItemTimelineContent
}

sealed interface MapItemTimelineRefreshState {
    data object Idle : MapItemTimelineRefreshState
    data object Refreshing : MapItemTimelineRefreshState
    data class Failed(val failure: MapItemTimelineFailure) : MapItemTimelineRefreshState
}

sealed interface MapItemTimelineAppendState {
    data object Idle : MapItemTimelineAppendState
    data object Loading : MapItemTimelineAppendState
    data class Failed(val failure: MapItemTimelineFailure) : MapItemTimelineAppendState
}

enum class MapItemTimelineFailure {
    INVALID_IDENTITY,
    INVALID_PERSISTED_HISTORY,
    INTERNAL_READ_FAILURE,
    INVALID_MERGE,
}
