package app.peermesh.map.presentation.community

import app.peermesh.map.application.participation.MapCoordinationNotePreviewProjectionResult
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult
import app.peermesh.map.application.verification.MapVerificationFreshnessProjectionResult

/**
 * Immutable M3C4 projection bundle for Android presentation.
 *
 * This is derived state only. It owns no lifecycle, refresh, persistence, paging,
 * submission, semantic acceptance, or optimistic domain state.
 */
sealed interface MapItemCommunityProjectionSnapshot {
    data object PendingCurrentDetail : MapItemCommunityProjectionSnapshot

    data class Projected(
        val verification: MapItemVerificationProjectionResult,
        val freshness: MapVerificationFreshnessProjectionResult,
        val participation: MapCoordinationParticipationProjectionResult,
        val timeline: MapItemCommunityTimelineProjection,
    ) : MapItemCommunityProjectionSnapshot
}

sealed interface MapItemCommunityTimelineProjection {
    data object NotReady : MapItemCommunityTimelineProjection
    data object Unavailable : MapItemCommunityTimelineProjection
    data class Available(
        val notePreview: MapCoordinationNotePreviewProjectionResult,
    ) : MapItemCommunityTimelineProjection
    data class RetainedAfterFailure(
        val notePreview: MapCoordinationNotePreviewProjectionResult,
    ) : MapItemCommunityTimelineProjection
}
