package app.peermesh.map.application.rendering

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.map.MapItemWindows
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemDomainPresentation
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.participation.MapCoordinationAcknowledgementEvidence
import app.peermesh.map.application.participation.MapCoordinationCategoryOrigin
import app.peermesh.map.application.participation.MapCoordinationParticipationLimitation
import app.peermesh.map.application.participation.MapCoordinationParticipationPresentation
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.participation.MapCoordinationTargetShape
import app.peermesh.map.application.scene.MapSceneAreaGeometry
import app.peermesh.map.application.scene.MapSceneCoordinate
import app.peermesh.map.application.scene.MapSceneGeometry
import app.peermesh.map.application.scene.MapSceneItem
import app.peermesh.map.application.scene.MapSceneTemporalPresentation
import app.peermesh.map.application.verification.MapCreatorInformationSourcePresentation
import app.peermesh.map.application.verification.MapItemVerificationPresentation
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult
import app.peermesh.map.application.verification.MapVerificationCategoryOrigin
import app.peermesh.map.application.verification.MapVerificationEvidence
import app.peermesh.map.application.verification.MapVerificationLimitation
import app.peermesh.map.application.verification.MapVerificationTargetShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMapItemVisualPolicyM3D2Test {
    private val policy = DefaultMapItemVisualPolicy()
    private val near = MapRenderEnvironment(MapZoomBand.NEAR, MapDensityBand.SPARSE)

    @Test fun situationAndCoordinationRemainSeparateWithoutKindInference() {
        val situation = render(scene(MapItemCategory.SITUATION, kind = "same"))
        val coordination = render(scene(MapItemCategory.COORDINATION, kind = "same"))
        assertEquals(MapCategoryVisualStyle.SITUATION, situation.visual.category)
        assertEquals(MapCategoryVisualStyle.COORDINATION, coordination.visual.category)
        assertNotEquals(situation.visual.situationEvidence, coordination.visual.situationEvidence)
        assertNotEquals(
            situation.visual.coordinationParticipation,
            coordination.visual.coordinationParticipation,
        )

        val unresolved = render(
            scene(null, kind = "situation").copy(
                domain = unresolvedDomain(),
                temporal = MapSceneTemporalPresentation.Unresolved,
                verification = MapItemVerificationProjectionResult.UnresolvedLegacyCategory,
                participation = MapCoordinationParticipationProjectionResult.UnresolvedLegacyCategory,
            ),
            MapSceneItemOutcome.UNRESOLVED_LEGACY,
        )
        assertEquals(MapCategoryVisualStyle.UNRESOLVED, unresolved.visual.category)
    }

    @Test fun canonicalOnlyAndUnresolvedCarryExplicitLimitations() {
        val canonical = render(
            scene(null).copy(
                domain = null,
                temporal = MapSceneTemporalPresentation.Unresolved,
                verification = MapItemVerificationProjectionResult.Unavailable(
                    app.peermesh.map.application.verification.MapVerificationUnavailableReason.DOMAIN_STATE_MISSING,
                ),
                participation = MapCoordinationParticipationProjectionResult.DomainStateUnavailable,
            ),
            MapSceneItemOutcome.CANONICAL_ONLY,
        )
        assertTrue(MapItemLimitationVisualStyle.CANONICAL_ONLY in canonical.visual.limitations)
        assertEquals(MapTemporalVisualStyle.UNRESOLVED, canonical.visual.temporal)
    }

    @Test fun nonRenderableOutcomesAndDeletedReturnTypedUnsupported() {
        listOf(
            MapSceneItemOutcome.MISSING,
            MapSceneItemOutcome.INVALID_CANONICAL,
            MapSceneItemOutcome.INVALID_DOMAIN,
        ).forEach { outcome ->
            assertTrue(policy.evaluate(scene(), MapItemEmphasis.NORMAL, near, outcome) is
                MapItemVisualPolicyResult.Unsupported)
        }
        assertTrue(
            policy.evaluate(
                scene().copy(
                    availability = MapItemCanonicalAvailability.DELETED,
                    temporal = MapSceneTemporalPresentation.Deleted,
                ),
                MapItemEmphasis.NORMAL,
                near,
                MapSceneItemOutcome.FOUND,
            ) is MapItemVisualPolicyResult.Unsupported,
        )
    }

    @Test fun resolvedExpiredAndInteractionClosedStayDistinct() {
        val resolved = render(
            scene().copy(
                domain = domain(MapItemCategory.SITUATION, MapSituationLifecycleStatus.NO_LONGER_ACTIVE),
                temporal = MapSceneTemporalPresentation.Resolved,
                verification = verification(
                    evidence = MapVerificationEvidence.Absent,
                    lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                    freshness = MapSituationConfirmationFreshnessBasis.InvalidatedByResolution,
                ),
            ),
        )
        val expired = render(scene().copy(temporal = MapSceneTemporalPresentation.Expired))
        val closed = render(scene().copy(temporal = MapSceneTemporalPresentation.InteractionClosed))
        assertEquals(MapLifecycleVisualStyle.RESOLVED, resolved.visual.lifecycle)
        assertEquals(MapTemporalVisualStyle.EXPIRED, expired.visual.temporal)
        assertEquals(MapTemporalVisualStyle.INTERACTION_CLOSED, closed.visual.temporal)
        assertNotEquals(resolved.visual.lifecycle, expired.visual.lifecycle)
    }

    @Test fun evidenceAndAcknowledgementUseDifferentVocabularies() {
        val present = render(scene(evidence = MapVerificationEvidence.Present(2, 5L)))
        val absent = render(scene(evidence = MapVerificationEvidence.Absent))
        val stale = render(
            scene(
                evidence = MapVerificationEvidence.Absent,
                freshness = MapSituationConfirmationFreshnessBasis.InvalidatedBySituationChange,
            ),
        )
        val acknowledged = render(scene(MapItemCategory.COORDINATION, acknowledged = true))
        val notAcknowledged = render(scene(MapItemCategory.COORDINATION, acknowledged = false))
        assertEquals(MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_PRESENT, present.visual.situationEvidence)
        assertEquals(MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_ABSENT, absent.visual.situationEvidence)
        assertEquals(MapSituationEvidenceVisualStyle.HISTORICAL_OR_STALE_ONLY, stale.visual.situationEvidence)
        assertEquals(
            MapCoordinationParticipationVisualStyle.ACKNOWLEDGED,
            acknowledged.visual.coordinationParticipation,
        )
        assertEquals(
            MapCoordinationParticipationVisualStyle.NOT_ACKNOWLEDGED,
            notAcknowledged.visual.coordinationParticipation,
        )
        assertEquals(MapSituationEvidenceVisualStyle.NOT_APPLICABLE, acknowledged.visual.situationEvidence)
    }

    @Test fun coordinationWithSituationLifecycleIsRejected() {
        val invalid = scene(MapItemCategory.COORDINATION).copy(
            domain = domain(MapItemCategory.COORDINATION, MapSituationLifecycleStatus.ACTIVE),
        )
        val result = policy.evaluate(invalid, MapItemEmphasis.NORMAL, near, MapSceneItemOutcome.FOUND)
        assertEquals(
            MapVisualPolicyFailure.COORDINATION_WITH_SITUATION_LIFECYCLE,
            (result as MapItemVisualPolicyResult.Unsupported).reason,
        )
    }

    @Test fun emphasisChangesOnlyPresentationAxesAndCombinedPriorityIsExplicit() {
        val item = scene()
        val normal = render(item, emphasis = MapItemEmphasis.NORMAL)
        val selected = render(item, emphasis = MapItemEmphasis.SELECTED)
        val combined = render(item, emphasis = MapItemEmphasis.SELECTED_AND_FOCUSED)
        assertEquals(normal.visual.category, selected.visual.category)
        assertEquals(normal.visual.lifecycle, selected.visual.lifecycle)
        assertEquals(normal.visual.situationEvidence, selected.visual.situationEvidence)
        assertEquals(MapItemProminence.EMPHASIZED, selected.visual.prominence)
        assertEquals(MapItemEmphasis.SELECTED_AND_FOCUSED, combined.visual.emphasis)
    }

    @Test fun environmentDeterministicallyControlsLabelsAndMarkerRadius() {
        val item = scene().copy(
            label = "Water",
            geometry = MapSceneGeometry.Marker(10.0, 20.0, 50.0),
        )
        val farDense = MapRenderEnvironment(MapZoomBand.FAR, MapDensityBand.DENSE)
        assertEquals(MapLabelMode.HIDDEN, render(item, environment = farDense).visual.labelMode)
        assertEquals(
            MapLabelMode.SHORT,
            render(item, emphasis = MapItemEmphasis.SELECTED, environment = farDense).visual.labelMode,
        )
        assertEquals(
            MapRadiusMode.VISIBLE,
            render(
                item,
                emphasis = MapItemEmphasis.FOCUSED,
                environment = MapRenderEnvironment(MapZoomBand.MEDIUM, MapDensityBand.DENSE),
            ).visual.radiusMode,
        )
        assertEquals(
            MapRadiusMode.HIDDEN,
            render(item.copy(geometry = MapSceneGeometry.Marker(10.0, 20.0, -1.0))).visual.radiusMode,
        )
    }

    @Test fun areaBoundaryLimitationAndDeterminismArePreserved() {
        val areaId = MapItemIdentity("g", MapItemType.AREA, "x")
        val area = scene(identity = areaId).copy(
            geometry = MapSceneGeometry.Area(
                MapSceneAreaGeometry.Polygon(
                    listOf(listOf(MapSceneCoordinate(0.0, 0.0), MapSceneCoordinate(1.0, 0.0))),
                ),
            ),
            verification = verification(areaId, MapVerificationEvidence.Absent),
        )
        val first = render(area)
        val second = render(area)
        assertEquals(first, second)
        assertTrue(
            MapItemLimitationVisualStyle.AREA_CONDITION_NOT_BOUNDARY_VERIFICATION in
                first.visual.limitations,
        )
        assertTrue(
            MapAccessibilityStateToken.AREA_BOUNDARY_NOT_VERIFIED in first.accessibility.stateTokens,
        )
    }

    private fun render(
        item: MapSceneItem,
        outcome: MapSceneItemOutcome = MapSceneItemOutcome.FOUND,
        emphasis: MapItemEmphasis = MapItemEmphasis.NORMAL,
        environment: MapRenderEnvironment = near,
    ) = (policy.evaluate(item, emphasis, environment, outcome) as
        MapItemVisualPolicyResult.Renderable).spec

    private fun scene(
        category: MapItemCategory? = MapItemCategory.SITUATION,
        kind: String? = "kind",
        identity: MapItemIdentity = MapItemIdentity("g", MapItemType.MARKER, "x"),
        evidence: MapVerificationEvidence = MapVerificationEvidence.Absent,
        freshness: MapSituationConfirmationFreshnessBasis =
            MapSituationConfirmationFreshnessBasis.NoAcceptedPriorConfirmationEstablished,
        acknowledged: Boolean = false,
    ): MapSceneItem {
        val domain = category?.let { domain(it) }
        return MapSceneItem(
            identity = identity,
            normalizedKind = kind,
            label = "Label",
            note = null,
            availability = MapItemCanonicalAvailability.ACTIVE,
            expiresAt = null,
            geometry = MapSceneGeometry.Marker(10.0, 20.0, 25.0),
            domain = domain,
            temporal = MapSceneTemporalPresentation.Current,
            verification = if (category == MapItemCategory.COORDINATION) {
                MapItemVerificationProjectionResult.CoordinationNotApplicable
            } else {
                verification(identity, evidence, freshness = freshness)
            },
            participation = if (category == MapItemCategory.COORDINATION) {
                participation(identity, acknowledged)
            } else {
                MapCoordinationParticipationProjectionResult.NotApplicableToSituation
            },
        )
    }

    private fun domain(
        category: MapItemCategory,
        lifecycle: MapSituationLifecycleStatus? =
            if (category == MapItemCategory.SITUATION) MapSituationLifecycleStatus.ACTIVE else null,
    ) = MapItemDomainPresentation(
        category,
        MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1,
        if (category == MapItemCategory.SITUATION) MapInformationSource.FIRSTHAND else null,
        lifecycle,
        MapItemWindows(null, null, null),
        MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1,
        0,
        0,
    )

    private fun unresolvedDomain() = MapItemDomainPresentation(
        null,
        MapItemCategoryOrigin.UNRESOLVED,
        null,
        null,
        MapItemWindows(null, null, null),
        MapItemTemporalOrigin.LEGACY_UNRESOLVED,
        0,
        0,
    )

    private fun verification(
        identity: MapItemIdentity = MapItemIdentity("g", MapItemType.MARKER, "x"),
        evidence: MapVerificationEvidence,
        lifecycle: MapSituationLifecycleStatus = MapSituationLifecycleStatus.ACTIVE,
        freshness: MapSituationConfirmationFreshnessBasis =
            MapSituationConfirmationFreshnessBasis.NoAcceptedPriorConfirmationEstablished,
    ) = MapItemVerificationProjectionResult.Available(
        MapItemVerificationPresentation(
            identity,
            if (identity.itemType == MapItemType.AREA) MapVerificationTargetShape.AREA
            else MapVerificationTargetShape.MARKER,
            MapVerificationCategoryOrigin.EXPLICIT_DOMAIN_V1,
            lifecycle,
            evidence,
            MapCreatorInformationSourcePresentation.Known(MapInformationSource.FIRSTHAND),
            if (identity.itemType == MapItemType.AREA) {
                MapVerificationLimitation.AREA_REPORTED_CONDITION_ONLY
            } else null,
            freshness,
        ),
    )

    private fun participation(identity: MapItemIdentity, acknowledged: Boolean) =
        MapCoordinationParticipationProjectionResult.Available(
            MapCoordinationParticipationPresentation(
                identity,
                if (identity.itemType == MapItemType.AREA) MapCoordinationTargetShape.AREA
                else MapCoordinationTargetShape.MARKER,
                MapCoordinationCategoryOrigin.EXPLICIT_DOMAIN_V1,
                if (acknowledged) MapCoordinationAcknowledgementEvidence.Present(1)
                else MapCoordinationAcknowledgementEvidence.Absent,
                MapCoordinationParticipationLimitation.ACKNOWLEDGEMENT_IS_NON_VERIFYING,
            ),
        )
}
