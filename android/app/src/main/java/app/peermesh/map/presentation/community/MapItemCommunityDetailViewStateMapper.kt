package app.peermesh.map.presentation.community

import app.peermesh.R
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.map.application.interaction.MapItemInteractionsAvailability
import app.peermesh.map.application.participation.MapCoordinationAcknowledgementEvidence
import app.peermesh.map.application.participation.MapCoordinationNotePreviewProjectionResult
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.verification.MapCreatorInformationSourcePresentation
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult
import app.peermesh.map.application.verification.MapVerificationAbsenceReason
import app.peermesh.map.application.verification.MapVerificationEvidence
import app.peermesh.map.application.verification.MapVerificationFreshnessProjectionResult
import app.peermesh.map.application.verification.MapVerificationFreshnessState
import app.peermesh.map.application.verification.MapVerificationLimitation
import app.peermesh.map.application.verification.MapVerificationUnavailableReason

/** Android-only, immutable view instructions derived from existing projector output. */
internal object MapItemCommunityDetailViewStateMapper {
    fun map(
        projection: MapItemCommunityProjectionSnapshot?,
        availability: MapItemInteractionsAvailability?,
        submissionInFlight: Boolean,
    ): MapItemCommunityDetailViewState {
        val projected = projection as? MapItemCommunityProjectionSnapshot.Projected
            ?: return MapItemCommunityDetailViewState.Hidden
        val confirmationEligible = MapItemCommunityInteractionPolicy.canRequestFreshConfirmation(
            projected,
            availability,
        )
        return MapItemCommunityDetailViewState(
            visible = true,
            verification = verification(projected.verification),
            freshness = freshness(projected.freshness),
            participation = participation(projected.participation),
            notePreview = notePreview(projected.participation, projected.timeline),
            confirmCta = MapItemCommunityConfirmCtaState(
                visible = confirmationEligible,
                enabled = confirmationEligible && !submissionInFlight,
            ),
        )
    }

    private fun verification(result: MapItemVerificationProjectionResult): MapItemCommunitySection = when (result) {
        is MapItemVerificationProjectionResult.Available -> MapItemCommunitySection.visible(buildList {
            add(when (val evidence = result.presentation.evidence) {
                is MapVerificationEvidence.Present -> MapItemCommunityText.Resource(
                    R.string.map_detail_verification_current, listOf(evidence.actorCount),
                )
                MapVerificationEvidence.Absent -> MapItemCommunityText.Resource(
                    if (result.presentation.lifecycle == MapSituationLifecycleStatus.NO_LONGER_ACTIVE) {
                        R.string.map_detail_verification_resolved
                    } else {
                        R.string.map_detail_verification_no_evidence
                    },
                )
            })
            (result.presentation.evidence as? MapVerificationEvidence.Present)
                ?.latestCurrentConfirmationAtSeconds?.let {
                    add(MapItemCommunityText.Timestamp(
                        R.string.map_detail_verification_latest,
                        it,
                    ))
                }
            add(when (val source = result.presentation.creatorSource) {
                is MapCreatorInformationSourcePresentation.Known -> MapItemCommunityText.Resource(
                    R.string.map_detail_verification_source,
                    listOf(MapItemCommunityStringResource(sourceText(source.source))),
                )
                MapCreatorInformationSourcePresentation.UnspecifiedLegacy -> MapItemCommunityText.Resource(
                    R.string.map_detail_verification_source_legacy,
                )
            })
            if (result.presentation.limitation == MapVerificationLimitation.AREA_REPORTED_CONDITION_ONLY) {
                add(MapItemCommunityText.Resource(R.string.map_detail_verification_area_limit))
            }
        })
        MapItemVerificationProjectionResult.CoordinationNotApplicable -> MapItemCommunitySection.Hidden
        MapItemVerificationProjectionResult.UnresolvedLegacyCategory -> MapItemCommunitySection.visible(
            MapItemCommunityText.Resource(R.string.map_detail_verification_legacy),
        )
        is MapItemVerificationProjectionResult.Unavailable -> MapItemCommunitySection.visible(
            MapItemCommunityText.Resource(
                if (result.reason == MapVerificationUnavailableReason.INTERNAL_READ_FAILURE) {
                    R.string.map_detail_verification_failure
                } else {
                    R.string.map_detail_verification_unavailable
                },
            ),
        )
        is MapItemVerificationProjectionResult.InvalidIdentity -> MapItemCommunitySection.visible(
            MapItemCommunityText.Resource(R.string.map_detail_verification_unavailable),
        )
    }

    private fun freshness(result: MapVerificationFreshnessProjectionResult): MapItemCommunitySection = when (result) {
        is MapVerificationFreshnessProjectionResult.Available -> MapItemCommunitySection.visible(
            when (val state = result.presentation.state) {
                is MapVerificationFreshnessState.Current -> MapItemCommunityText.Resource(
                    R.string.map_detail_fresh_current,
                )
                is MapVerificationFreshnessState.Absent -> MapItemCommunityText.Resource(when (state.reason) {
                    MapVerificationAbsenceReason.NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED ->
                        R.string.map_detail_fresh_none
                    MapVerificationAbsenceReason.INVALIDATED_BY_SITUATION_CHANGE ->
                        R.string.map_detail_fresh_changed
                    MapVerificationAbsenceReason.INVALIDATED_BY_RESOLUTION ->
                        R.string.map_detail_fresh_resolved
                    MapVerificationAbsenceReason.RESOLVED_WITHOUT_PRIOR_CONFIRMATION ->
                        R.string.map_detail_fresh_resolved_none
                    MapVerificationAbsenceReason.UNKNOWN -> R.string.map_detail_fresh_unknown
                })
            },
        )
        MapVerificationFreshnessProjectionResult.CoordinationNotApplicable -> MapItemCommunitySection.Hidden
        MapVerificationFreshnessProjectionResult.UnresolvedLegacyCategory,
        is MapVerificationFreshnessProjectionResult.Unavailable,
        is MapVerificationFreshnessProjectionResult.InvalidIdentity,
        -> MapItemCommunitySection.visible(MapItemCommunityText.Resource(R.string.map_detail_fresh_unavailable))
    }

    private fun participation(result: MapCoordinationParticipationProjectionResult): MapItemCommunitySection = when (result) {
        is MapCoordinationParticipationProjectionResult.Available -> MapItemCommunitySection.visible(buildList {
            add(when (val evidence = result.presentation.evidence) {
                is MapCoordinationAcknowledgementEvidence.Present -> MapItemCommunityText.Resource(
                    R.string.map_detail_participation_current, listOf(evidence.actorCount),
                )
                MapCoordinationAcknowledgementEvidence.Absent -> MapItemCommunityText.Resource(
                    R.string.map_detail_participation_none,
                )
            })
            if (result.presentation.limitation != null) {
                add(MapItemCommunityText.Resource(R.string.map_detail_participation_limit))
            }
        })
        MapCoordinationParticipationProjectionResult.NotApplicableToSituation -> MapItemCommunitySection.Hidden
        MapCoordinationParticipationProjectionResult.UnresolvedLegacyCategory -> MapItemCommunitySection.visible(
            MapItemCommunityText.Resource(R.string.map_detail_participation_legacy),
        )
        MapCoordinationParticipationProjectionResult.InternalProjectionFailure -> MapItemCommunitySection.visible(
            MapItemCommunityText.Resource(R.string.map_detail_participation_failure),
        )
        MapCoordinationParticipationProjectionResult.DomainStateUnavailable,
        MapCoordinationParticipationProjectionResult.TargetUnavailable,
        is MapCoordinationParticipationProjectionResult.InvalidIdentity,
        MapCoordinationParticipationProjectionResult.InvalidCanonicalPersistedState,
        MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState,
        -> MapItemCommunitySection.visible(MapItemCommunityText.Resource(R.string.map_detail_participation_unavailable))
    }

    private fun notePreview(
        participation: MapCoordinationParticipationProjectionResult,
        timeline: MapItemCommunityTimelineProjection,
    ): MapItemCommunitySection {
        if (participation == MapCoordinationParticipationProjectionResult.NotApplicableToSituation) {
            return MapItemCommunitySection.Hidden
        }
        return when (timeline) {
            MapItemCommunityTimelineProjection.NotReady -> MapItemCommunitySection.visible(
                MapItemCommunityText.Resource(R.string.map_detail_note_preview_loading),
            )
            MapItemCommunityTimelineProjection.Unavailable -> MapItemCommunitySection.visible(
                MapItemCommunityText.Resource(R.string.map_detail_note_preview_unavailable),
            )
            is MapItemCommunityTimelineProjection.Available ->
                previewSection(timeline.notePreview, retained = false)
            is MapItemCommunityTimelineProjection.RetainedAfterFailure ->
                previewSection(timeline.notePreview, retained = true)
        }
    }

    private fun previewSection(
        result: MapCoordinationNotePreviewProjectionResult,
        retained: Boolean,
    ): MapItemCommunitySection = MapItemCommunitySection.visible(buildList {
        when (result) {
            is MapCoordinationNotePreviewProjectionResult.Available -> add(
                MapItemCommunityText.Timestamp(
                    R.string.map_detail_note_preview,
                    result.preview.actionTimestampSeconds,
                    beforeTimestamp = listOf(result.preview.actor.displayValue),
                    afterTimestamp = listOf(result.preview.note),
                ),
            )
            MapCoordinationNotePreviewProjectionResult.NoPreviewInSuppliedHistory ->
                add(MapItemCommunityText.Resource(R.string.map_detail_note_preview_empty))
            MapCoordinationNotePreviewProjectionResult.InvalidIdentity,
            MapCoordinationNotePreviewProjectionResult.InvalidSuppliedHistory,
            -> add(MapItemCommunityText.Resource(R.string.map_detail_note_preview_invalid))
        }
        if (retained) add(MapItemCommunityText.Resource(R.string.map_detail_note_preview_retained))
    })

    private fun sourceText(source: MapInformationSource): Int = when (source) {
        MapInformationSource.FIRSTHAND -> R.string.map_detail_source_firsthand
        MapInformationSource.REPORTED_BY_OTHERS -> R.string.map_detail_source_reported
        MapInformationSource.UNCERTAIN -> R.string.map_detail_source_uncertain
    }
}

internal data class MapItemCommunityDetailViewState(
    val visible: Boolean,
    val verification: MapItemCommunitySection,
    val freshness: MapItemCommunitySection,
    val participation: MapItemCommunitySection,
    val notePreview: MapItemCommunitySection,
    val confirmCta: MapItemCommunityConfirmCtaState,
) {
    companion object {
        val Hidden = MapItemCommunityDetailViewState(
            visible = false,
            verification = MapItemCommunitySection.Hidden,
            freshness = MapItemCommunitySection.Hidden,
            participation = MapItemCommunitySection.Hidden,
            notePreview = MapItemCommunitySection.Hidden,
            confirmCta = MapItemCommunityConfirmCtaState.Hidden,
        )
    }
}

internal data class MapItemCommunitySection(
    val visible: Boolean,
    val lines: List<MapItemCommunityText>,
) {
    companion object {
        val Hidden = MapItemCommunitySection(false, emptyList())
        fun visible(vararg lines: MapItemCommunityText) = MapItemCommunitySection(true, lines.toList())
        fun visible(lines: List<MapItemCommunityText>) = MapItemCommunitySection(true, lines)
    }
}

internal sealed interface MapItemCommunityText {
    data class Resource(val id: Int, val arguments: List<Any> = emptyList()) : MapItemCommunityText
    data class Timestamp(
        val id: Int,
        val seconds: Long,
        val beforeTimestamp: List<Any> = emptyList(),
        val afterTimestamp: List<Any> = emptyList(),
    ) : MapItemCommunityText
}

internal data class MapItemCommunityStringResource(val id: Int)

internal data class MapItemCommunityConfirmCtaState(
    val visible: Boolean,
    val enabled: Boolean,
) {
    companion object { val Hidden = MapItemCommunityConfirmCtaState(false, false) }
}
