package app.peermesh.map.presentation.community

import app.peermesh.map.application.timeline.MapItemActivityEntry

/**
 * Immutable M3B presentation provenance supplied to M3C4.
 *
 * This preserves whether the supplied entry window is usable before the pure
 * note-preview projector is invoked. It has no paging, retry, or controller
 * ownership.
 */
sealed interface MapItemCommunityTimelineInput {
    data object NotReady : MapItemCommunityTimelineInput
    data class Available(val entries: List<MapItemActivityEntry>) : MapItemCommunityTimelineInput
    data object Unavailable : MapItemCommunityTimelineInput
    data class RetainedAfterFailure(val entries: List<MapItemActivityEntry>) : MapItemCommunityTimelineInput
}
