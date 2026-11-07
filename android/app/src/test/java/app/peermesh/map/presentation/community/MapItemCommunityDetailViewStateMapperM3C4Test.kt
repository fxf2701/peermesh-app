package app.peermesh.map.presentation.community

import app.peermesh.R
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis
import app.peermesh.map.application.detail.InvalidIdentityReason
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.participation.MapCoordinationAcknowledgementEvidence
import app.peermesh.map.application.participation.MapCoordinationCategoryOrigin
import app.peermesh.map.application.participation.MapCoordinationNotePreview
import app.peermesh.map.application.participation.MapCoordinationNotePreviewProjectionResult
import app.peermesh.map.application.participation.MapCoordinationParticipationLimitation
import app.peermesh.map.application.participation.MapCoordinationParticipationPresentation
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.participation.MapCoordinationTargetShape
import app.peermesh.map.application.timeline.MapItemActivityActor
import app.peermesh.map.application.verification.MapCreatorInformationSourcePresentation
import app.peermesh.map.application.verification.MapItemVerificationPresentation
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult
import app.peermesh.map.application.verification.MapVerificationAbsenceReason
import app.peermesh.map.application.verification.MapVerificationCategoryOrigin
import app.peermesh.map.application.verification.MapVerificationEvidence
import app.peermesh.map.application.verification.MapVerificationFreshnessPresentation
import app.peermesh.map.application.verification.MapVerificationFreshnessProjectionResult
import app.peermesh.map.application.verification.MapVerificationFreshnessState
import app.peermesh.map.application.verification.MapVerificationLimitation
import app.peermesh.map.application.verification.MapVerificationTargetShape
import app.peermesh.map.application.verification.MapVerificationUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemCommunityDetailViewStateMapperM3C4Test {
    @Test fun situationEvidenceRendersCountTimestampKnownSourceAndAreaLimitation() {
        val view = map(
            verification = MapItemVerificationProjectionResult.Available(areaVerification()),
            freshness = currentFreshness(),
        )
        assertResources(view.verification, R.string.map_detail_verification_current,
            R.string.map_detail_verification_latest, R.string.map_detail_verification_source,
            R.string.map_detail_verification_area_limit)
        assertEquals(123L, (view.verification.lines[1] as MapItemCommunityText.Timestamp).seconds)
    }

    @Test fun situationLegacySourceAbsenceResolvedUnresolvedUnavailableAndInvalidStayDistinct() {
        val legacySource = map(verification = MapItemVerificationProjectionResult.Available(verification(
            evidence = MapVerificationEvidence.Absent,
            creatorSource = MapCreatorInformationSourcePresentation.UnspecifiedLegacy,
        )))
        assertResources(legacySource.verification, R.string.map_detail_verification_no_evidence,
            R.string.map_detail_verification_source_legacy)
        val resolved = map(verification = MapItemVerificationProjectionResult.Available(verification(
            lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
            evidence = MapVerificationEvidence.Absent,
        )))
        assertResources(resolved.verification, R.string.map_detail_verification_resolved,
            R.string.map_detail_verification_source)
        assertResources(map(verification = MapItemVerificationProjectionResult.UnresolvedLegacyCategory).verification,
            R.string.map_detail_verification_legacy)
        assertResources(map(verification = MapItemVerificationProjectionResult.Unavailable(
            MapVerificationUnavailableReason.INTERNAL_READ_FAILURE,
        )).verification, R.string.map_detail_verification_failure)
        assertResources(map(verification = MapItemVerificationProjectionResult.InvalidIdentity(
            InvalidIdentityReason.BLANK_ITEM_ID,
        )).verification, R.string.map_detail_verification_unavailable)
    }

    @Test fun freshnessMapsEveryMeaningAndHidesForCoordination() {
        assertResources(map(freshness = currentFreshness()).freshness, R.string.map_detail_fresh_current)
        listOf(
            MapVerificationAbsenceReason.NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED to R.string.map_detail_fresh_none,
            MapVerificationAbsenceReason.INVALIDATED_BY_SITUATION_CHANGE to R.string.map_detail_fresh_changed,
            MapVerificationAbsenceReason.INVALIDATED_BY_RESOLUTION to R.string.map_detail_fresh_resolved,
            MapVerificationAbsenceReason.RESOLVED_WITHOUT_PRIOR_CONFIRMATION to R.string.map_detail_fresh_resolved_none,
            MapVerificationAbsenceReason.UNKNOWN to R.string.map_detail_fresh_unknown,
        ).forEach { (reason, text) ->
            assertResources(map(freshness = absentFreshness(reason)).freshness, text)
        }
        assertResources(map(freshness = MapVerificationFreshnessProjectionResult.Unavailable(
            MapVerificationUnavailableReason.DOMAIN_STATE_MISSING,
        )).freshness, R.string.map_detail_fresh_unavailable)
        assertFalse(map(freshness = MapVerificationFreshnessProjectionResult.CoordinationNotApplicable).freshness.visible)
    }

    @Test fun participationMapsEvidenceLimitationFailuresAndSituationHiding() {
        assertResources(map(participation = availableParticipation(4)).participation,
            R.string.map_detail_participation_current, R.string.map_detail_participation_limit)
        assertResources(map(participation = availableParticipation(0)).participation,
            R.string.map_detail_participation_none, R.string.map_detail_participation_limit)
        assertResources(map(participation = MapCoordinationParticipationProjectionResult.UnresolvedLegacyCategory).participation,
            R.string.map_detail_participation_legacy)
        assertResources(map(participation = MapCoordinationParticipationProjectionResult.InternalProjectionFailure).participation,
            R.string.map_detail_participation_failure)
        assertResources(map(participation = MapCoordinationParticipationProjectionResult.TargetUnavailable).participation,
            R.string.map_detail_participation_unavailable)
        assertFalse(map(participation = MapCoordinationParticipationProjectionResult.NotApplicableToSituation).participation.visible)
    }

    @Test fun notePreviewDistinguishesAvailableEmptyNotReadyUnavailableAndInvalid() {
        assertResources(map(timeline = MapItemCommunityTimelineProjection.Available(preview())).notePreview,
            R.string.map_detail_note_preview)
        assertResources(map(timeline = MapItemCommunityTimelineProjection.Available(
            MapCoordinationNotePreviewProjectionResult.NoPreviewInSuppliedHistory,
        )).notePreview, R.string.map_detail_note_preview_empty)
        assertResources(map(timeline = MapItemCommunityTimelineProjection.NotReady).notePreview,
            R.string.map_detail_note_preview_loading)
        assertResources(map(timeline = MapItemCommunityTimelineProjection.Unavailable).notePreview,
            R.string.map_detail_note_preview_unavailable)
        assertResources(map(timeline = MapItemCommunityTimelineProjection.Available(
            MapCoordinationNotePreviewProjectionResult.InvalidSuppliedHistory,
        )).notePreview, R.string.map_detail_note_preview_invalid)
    }

    @Test fun retainedTimelineEmitsTheRetainedHistoryLimitationForPreviewAndEmptyHistory() {
        assertResources(map(timeline = MapItemCommunityTimelineProjection.RetainedAfterFailure(preview())).notePreview,
            R.string.map_detail_note_preview, R.string.map_detail_note_preview_retained)
        assertResources(map(timeline = MapItemCommunityTimelineProjection.RetainedAfterFailure(
            MapCoordinationNotePreviewProjectionResult.NoPreviewInSuppliedHistory,
        )).notePreview, R.string.map_detail_note_preview_empty, R.string.map_detail_note_preview_retained)
    }

    @Test fun situationHidesNotePreviewAndStateTransitionsResetAllVisibility() {
        val coordination = map(participation = availableParticipation(1))
        assertTrue(coordination.participation.visible)
        assertTrue(coordination.notePreview.visible)
        val situation = map(participation = MapCoordinationParticipationProjectionResult.NotApplicableToSituation)
        assertFalse(situation.participation.visible)
        assertFalse(situation.notePreview.visible)
        assertFalse(MapItemCommunityDetailViewStateMapper.map(null, null, false).visible)
        assertFalse(MapItemCommunityDetailViewStateMapper.map(
            MapItemCommunityProjectionSnapshot.PendingCurrentDetail, null, false,
        ).verification.visible)
        assertResources(map(verification = MapItemVerificationProjectionResult.Unavailable(
            MapVerificationUnavailableReason.DOMAIN_STATE_MISSING,
        )).verification, R.string.map_detail_verification_unavailable)
    }

    @Test fun ctaRequiresFreshnessVisibleEnabledM3aAndNoSubmission() {
        val fresh = absentFreshness(MapVerificationAbsenceReason.NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED)
        assertTrue(map(freshness = fresh, availability = availability(true), inFlight = false).confirmCta.visible)
        assertTrue(map(freshness = fresh, availability = availability(true), inFlight = false).confirmCta.enabled)
        assertFalse(map(freshness = fresh, availability = availability(false), inFlight = false).confirmCta.visible)
        assertFalse(map(freshness = currentFreshness(), availability = availability(true), inFlight = false).confirmCta.visible)
        val inFlight = map(freshness = fresh, availability = availability(true), inFlight = true).confirmCta
        assertTrue(inFlight.visible)
        assertFalse(inFlight.enabled)
        assertFalse(map(freshness = MapVerificationFreshnessProjectionResult.CoordinationNotApplicable,
            availability = availability(true)).confirmCta.visible)
    }

    private fun map(
        verification: MapItemVerificationProjectionResult = MapItemVerificationProjectionResult.CoordinationNotApplicable,
        freshness: MapVerificationFreshnessProjectionResult = MapVerificationFreshnessProjectionResult.CoordinationNotApplicable,
        participation: MapCoordinationParticipationProjectionResult = availableParticipation(1),
        timeline: MapItemCommunityTimelineProjection = MapItemCommunityTimelineProjection.Available(preview()),
        availability: app.peermesh.map.application.interaction.MapItemInteractionsAvailability? = null,
        inFlight: Boolean = false,
    ): MapItemCommunityDetailViewState = MapItemCommunityDetailViewStateMapper.map(
        MapItemCommunityProjectionSnapshot.Projected(verification, freshness, participation, timeline),
        availability,
        inFlight,
    )

    private fun verification(
        lifecycle: MapSituationLifecycleStatus = MapSituationLifecycleStatus.ACTIVE,
        evidence: MapVerificationEvidence = MapVerificationEvidence.Absent,
        creatorSource: MapCreatorInformationSourcePresentation =
            MapCreatorInformationSourcePresentation.Known(MapInformationSource.FIRSTHAND),
        limitation: MapVerificationLimitation? = null,
    ) = MapItemVerificationPresentation(
        IDENTITY, MapVerificationTargetShape.MARKER,
        if (creatorSource is MapCreatorInformationSourcePresentation.UnspecifiedLegacy) {
            MapVerificationCategoryOrigin.LEGACY_KIND_DERIVED
        } else {
            MapVerificationCategoryOrigin.EXPLICIT_DOMAIN_V1
        },
        lifecycle, evidence, creatorSource, limitation, MapSituationConfirmationFreshnessBasis.Unknown,
    )

    private fun areaVerification() = MapItemVerificationPresentation(
        AREA_IDENTITY, MapVerificationTargetShape.AREA, MapVerificationCategoryOrigin.EXPLICIT_DOMAIN_V1,
        MapSituationLifecycleStatus.ACTIVE, MapVerificationEvidence.Present(3, 123),
        MapCreatorInformationSourcePresentation.Known(MapInformationSource.FIRSTHAND),
        MapVerificationLimitation.AREA_REPORTED_CONDITION_ONLY,
        MapSituationConfirmationFreshnessBasis.Current(123),
    )

    private fun currentFreshness() = MapVerificationFreshnessProjectionResult.Available(
        MapVerificationFreshnessPresentation(IDENTITY, MapVerificationFreshnessState.Current(1, 1)),
    )

    private fun absentFreshness(reason: MapVerificationAbsenceReason) =
        MapVerificationFreshnessProjectionResult.Available(
            MapVerificationFreshnessPresentation(IDENTITY, MapVerificationFreshnessState.Absent(reason)),
        )

    private fun availableParticipation(count: Int) = MapCoordinationParticipationProjectionResult.Available(
        MapCoordinationParticipationPresentation(
            IDENTITY, MapCoordinationTargetShape.MARKER, MapCoordinationCategoryOrigin.EXPLICIT_DOMAIN_V1,
            if (count == 0) MapCoordinationAcknowledgementEvidence.Absent
            else MapCoordinationAcknowledgementEvidence.Present(count),
            MapCoordinationParticipationLimitation.ACKNOWLEDGEMENT_IS_NON_VERIFYING,
        ),
    )

    private fun preview() = MapCoordinationNotePreviewProjectionResult.Available(MapCoordinationNotePreview(
        "entry", IDENTITY, MapItemActivityActor("actor", "Actor"), 50, "Bring water",
    ))

    private fun availability(enabled: Boolean) = app.peermesh.map.application.interaction.MapItemInteractionsAvailability(
        IDENTITY,
        listOf(app.peermesh.map.application.interaction.MapItemInteractionAvailability(
            app.peermesh.map.application.interaction.MapInteractionType.Situation.Confirm,
            if (enabled) app.peermesh.map.application.interaction.MapInteractionAvailabilityState.ENABLED
            else app.peermesh.map.application.interaction.MapInteractionAvailabilityState.DISABLED,
        )),
    )

    private fun assertResources(section: MapItemCommunitySection, vararg expected: Int) {
        assertTrue(section.visible)
        assertEquals(expected.toList(), section.lines.map {
            when (it) {
                is MapItemCommunityText.Resource -> it.id
                is MapItemCommunityText.Timestamp -> it.id
            }
        })
    }

    companion object {
        private val IDENTITY = MapItemIdentity("group", MapItemType.MARKER, "item")
        private val AREA_IDENTITY = MapItemIdentity("group", MapItemType.AREA, "item")
    }
}
