package app.peermesh.map.presentation.community

import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.participation.ProjectMapCoordinationNotePreview
import app.peermesh.map.application.participation.ProjectMapCoordinationParticipation
import app.peermesh.map.application.verification.ProjectMapItemVerification
import app.peermesh.map.application.verification.ProjectMapVerificationFreshness
import app.peermesh.map.presentation.detail.MapItemDetailShellContent

/**
 * Stateless M3C4 presentation composition boundary.
 *
 * The caller supplies snapshots already owned by the M3A and M3B controllers.
 * This contract must never load, refresh, page, submit, or persist anything.
 */
fun interface MapItemCommunityProjectionComposer {
    fun project(input: MapItemCommunityProjectionInput): MapItemCommunityProjectionSnapshot
}

data class MapItemCommunityProjectionInput(
    val identity: MapItemIdentity,
    val detailContent: MapItemDetailShellContent,
    val timeline: MapItemCommunityTimelineInput,
)

class DefaultMapItemCommunityProjectionComposer(
    private val projectVerification: ProjectMapItemVerification,
    private val projectFreshness: ProjectMapVerificationFreshness,
    private val projectParticipation: ProjectMapCoordinationParticipation,
    private val projectNotePreview: ProjectMapCoordinationNotePreview,
) : MapItemCommunityProjectionComposer {
    override fun project(input: MapItemCommunityProjectionInput): MapItemCommunityProjectionSnapshot {
        val detailResult = MapItemDetailProjectionInputAdapter.adapt(input.detailContent)
            ?: return MapItemCommunityProjectionSnapshot.PendingCurrentDetail
        val verification = projectVerification.project(detailResult)
        return MapItemCommunityProjectionSnapshot.Projected(
            verification = verification,
            freshness = projectFreshness.project(verification),
            participation = projectParticipation.project(detailResult),
            timeline = when (val timeline = input.timeline) {
                MapItemCommunityTimelineInput.NotReady ->
                    MapItemCommunityTimelineProjection.NotReady
                MapItemCommunityTimelineInput.Unavailable ->
                    MapItemCommunityTimelineProjection.Unavailable
                is MapItemCommunityTimelineInput.Available ->
                    MapItemCommunityTimelineProjection.Available(
                        projectNotePreview.project(input.identity, timeline.entries),
                    )
                is MapItemCommunityTimelineInput.RetainedAfterFailure ->
                    MapItemCommunityTimelineProjection.RetainedAfterFailure(
                        projectNotePreview.project(input.identity, timeline.entries),
                    )
            },
        )
    }
}
