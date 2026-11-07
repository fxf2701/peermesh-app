package app.peermesh.map.composition

import app.peermesh.map.application.participation.MapCoordinationNotePreviewPresentationFactory
import app.peermesh.map.application.participation.MapCoordinationParticipationPresentationFactory
import app.peermesh.map.presentation.community.DefaultMapItemCommunityProjectionComposer
import app.peermesh.map.presentation.community.MapItemCommunityProjectionComposer

/**
 * M3C4 composition boundary for pure projectors only.
 *
 * It deliberately has no Context, database, repository, controller, writer,
 * processor, timeline cursor, or submission dependency.
 */
object MapItemCommunityPresentationFactory {
    fun create(): MapItemCommunityProjectionComposer = DefaultMapItemCommunityProjectionComposer(
        projectVerification = MapItemVerificationPresentationFactory.create(),
        projectFreshness = MapVerificationFreshnessPresentationFactory.create(),
        projectParticipation = MapCoordinationParticipationPresentationFactory.create(),
        projectNotePreview = MapCoordinationNotePreviewPresentationFactory.create(),
    )
}
