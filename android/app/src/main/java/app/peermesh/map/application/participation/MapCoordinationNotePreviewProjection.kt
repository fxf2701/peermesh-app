package app.peermesh.map.application.participation

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.timeline.MapItemActivityEntry

fun interface ProjectMapCoordinationNotePreview {
    /**
     * [entries] must already be in accepted M3B newest-first order.
     *
     * The projector must not query, sort, page, refresh, replay, or infer
     * history completeness.
     */
    fun project(
        identity: MapItemIdentity,
        entries: List<MapItemActivityEntry>,
    ): MapCoordinationNotePreviewProjectionResult
}

sealed interface MapCoordinationNotePreviewProjectionResult {
    data class Available(
        val preview: MapCoordinationNotePreview,
    ) : MapCoordinationNotePreviewProjectionResult

    /**
     * No valid preview exists in the supplied historical window.
     * This does not mean that no Coordination note has ever existed.
     */
    data object NoPreviewInSuppliedHistory : MapCoordinationNotePreviewProjectionResult

    data object InvalidIdentity : MapCoordinationNotePreviewProjectionResult

    data object InvalidSuppliedHistory : MapCoordinationNotePreviewProjectionResult
}

/**
 * Pure M3C3 historical preview selector.
 *
 * It consumes already projected M3B entries only. It must not depend on
 * MapItemTimelineController, MapItemTimelineState, LoadMapItemActivity,
 * repositories, DAOs, cursors, Android UI, or submission.
 */
class DefaultMapCoordinationNotePreviewProjector : ProjectMapCoordinationNotePreview {
    override fun project(
        identity: MapItemIdentity,
        entries: List<MapItemActivityEntry>,
    ): MapCoordinationNotePreviewProjectionResult {
        if (identity.groupId.isBlank() || identity.itemId.isBlank()) {
            return MapCoordinationNotePreviewProjectionResult.InvalidIdentity
        }

        for (entry in entries) {
            if (entry.itemIdentity != identity ||
                entry.type != app.peermesh.map.application.timeline.MapItemActivityType.COORDINATION_NOTE_ADDED
            ) {
                continue
            }
            if (entry.entryId.isBlank() ||
                entry.actor.stableId.isBlank() ||
                entry.actor.displayValue.isBlank() ||
                entry.actionTimestampSeconds <= 0L ||
                entry.note.isNullOrBlank() ||
                entry.informationSource != null ||
                entry.updateDisposition != null ||
                entry.targetLifecycle != null
            ) {
                return MapCoordinationNotePreviewProjectionResult.InvalidSuppliedHistory
            }
            return MapCoordinationNotePreviewProjectionResult.Available(
                MapCoordinationNotePreview(
                    entryId = entry.entryId,
                    itemIdentity = entry.itemIdentity,
                    actor = entry.actor,
                    actionTimestampSeconds = entry.actionTimestampSeconds,
                    note = entry.note,
                ),
            )
        }
        return MapCoordinationNotePreviewProjectionResult.NoPreviewInSuppliedHistory
    }
}

object MapCoordinationNotePreviewPresentationFactory {
    fun create(): ProjectMapCoordinationNotePreview =
        DefaultMapCoordinationNotePreviewProjector()
}
