package app.peermesh.map.application.rendering

import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.participation.MapCoordinationAcknowledgementEvidence
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.scene.MapSceneItem
import app.peermesh.map.application.scene.MapSceneTemporalPresentation
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult
import app.peermesh.map.application.verification.MapVerificationEvidence

/**
 * Pure M3D2 semantic-to-visual policy.
 *
 * Implementation constraints:
 * - consume only the supplied M3D1 scene item and ephemeral presentation inputs;
 * - perform no repository, DAO, timeline, Android-resource, SDK, BLE, relay, or submission access;
 * - preserve Situation verification and Coordination participation as distinct vocabularies;
 * - reject unsupported semantic combinations with typed results;
 * - propagate fatal JVM Error subclasses.
 */
class DefaultMapItemVisualPolicy : MapItemVisualPolicy {
    override fun evaluate(
        item: MapSceneItem,
        emphasis: MapItemEmphasis,
        environment: MapRenderEnvironment,
        itemOutcome: MapSceneItemOutcome,
    ): MapItemVisualPolicyResult {
        if (!item.identity.matches(item.geometry)) {
            return unsupported(item, MapVisualPolicyFailure.IDENTITY_GEOMETRY_MISMATCH)
        }
        if (itemOutcome in NON_RENDERABLE_OUTCOMES ||
            item.availability == MapItemCanonicalAvailability.DELETED ||
            item.temporal is MapSceneTemporalPresentation.Deleted
        ) {
            return unsupported(item, MapVisualPolicyFailure.INVALID_ITEM_OUTCOME)
        }

        val category = when (itemOutcome) {
            MapSceneItemOutcome.CANONICAL_ONLY -> MapCategoryVisualStyle.CANONICAL_ONLY
            MapSceneItemOutcome.UNRESOLVED_LEGACY -> MapCategoryVisualStyle.UNRESOLVED
            MapSceneItemOutcome.FOUND -> when (item.domain?.category) {
                MapItemCategory.SITUATION -> MapCategoryVisualStyle.SITUATION
                MapItemCategory.COORDINATION -> MapCategoryVisualStyle.COORDINATION
                null -> return unsupported(item, MapVisualPolicyFailure.INVALID_CATEGORY_STATE)
            }
            else -> return unsupported(item, MapVisualPolicyFailure.INVALID_ITEM_OUTCOME)
        }

        val lifecycle = lifecycle(item, category)
            ?: if (category == MapCategoryVisualStyle.SITUATION) {
                return unsupported(item, MapVisualPolicyFailure.INVALID_SITUATION_LIFECYCLE)
            } else {
                null
            }
        if (category == MapCategoryVisualStyle.COORDINATION && item.domain?.lifecycle != null) {
            return unsupported(item, MapVisualPolicyFailure.COORDINATION_WITH_SITUATION_LIFECYCLE)
        }

        val temporal = temporal(item, category)
            ?: return unsupported(item, MapVisualPolicyFailure.INVALID_TEMPORAL_COMBINATION)
        val situationEvidence = situationEvidence(item, category)
            ?: return unsupported(item, MapVisualPolicyFailure.INVALID_VERIFICATION_COMBINATION)
        val participation = participation(item, category)
            ?: return unsupported(item, MapVisualPolicyFailure.INVALID_PARTICIPATION_COMBINATION)
        val limitations = buildSet {
            if (category == MapCategoryVisualStyle.CANONICAL_ONLY) add(MapItemLimitationVisualStyle.CANONICAL_ONLY)
            if (category == MapCategoryVisualStyle.UNRESOLVED) add(MapItemLimitationVisualStyle.UNRESOLVED_LEGACY)
            if (category == MapCategoryVisualStyle.SITUATION && item.identity.itemType == MapItemType.AREA) {
                add(MapItemLimitationVisualStyle.AREA_CONDITION_NOT_BOUNDARY_VERIFICATION)
            }
        }
        val emphasized = emphasis != MapItemEmphasis.NORMAL
        val labelMode = when {
            item.label.isNullOrBlank() -> MapLabelMode.HIDDEN
            emphasized -> MapLabelMode.SHORT
            environment.zoomBand == MapZoomBand.FAR -> MapLabelMode.HIDDEN
            environment.densityBand == MapDensityBand.DENSE -> MapLabelMode.HIDDEN
            else -> MapLabelMode.SHORT
        }
        val validRadius = (item.geometry as? app.peermesh.map.application.scene.MapSceneGeometry.Marker)
            ?.radiusMeters?.takeIf { it.isFinite() && it > 0.0 }
        val radiusMode = if (
            validRadius != null &&
            (environment.zoomBand == MapZoomBand.NEAR ||
                (emphasized && environment.zoomBand == MapZoomBand.MEDIUM))
        ) {
            MapRadiusMode.VISIBLE
        } else {
            MapRadiusMode.HIDDEN
        }
        val accessibilityTokens = buildList {
            when (lifecycle) {
                MapLifecycleVisualStyle.ACTIVE -> add(MapAccessibilityStateToken.ACTIVE)
                MapLifecycleVisualStyle.RESOLVED -> add(MapAccessibilityStateToken.RESOLVED)
                null -> Unit
            }
            when (temporal) {
                MapTemporalVisualStyle.EXPIRED -> add(MapAccessibilityStateToken.EXPIRED)
                MapTemporalVisualStyle.INTERACTION_CLOSED -> add(MapAccessibilityStateToken.INTERACTION_CLOSED)
                else -> Unit
            }
            when (situationEvidence) {
                MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_PRESENT ->
                    add(MapAccessibilityStateToken.CURRENT_EVIDENCE_PRESENT)
                MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_ABSENT ->
                    add(MapAccessibilityStateToken.NO_CURRENT_EVIDENCE)
                else -> Unit
            }
            when (participation) {
                MapCoordinationParticipationVisualStyle.ACKNOWLEDGED ->
                    add(MapAccessibilityStateToken.ACKNOWLEDGEMENT_PRESENT)
                MapCoordinationParticipationVisualStyle.NOT_ACKNOWLEDGED ->
                    add(MapAccessibilityStateToken.NO_ACKNOWLEDGEMENT)
                else -> Unit
            }
            if (MapItemLimitationVisualStyle.AREA_CONDITION_NOT_BOUNDARY_VERIFICATION in limitations) {
                add(MapAccessibilityStateToken.AREA_BOUNDARY_NOT_VERIFIED)
            }
            if (limitations.any {
                    it == MapItemLimitationVisualStyle.CANONICAL_ONLY ||
                        it == MapItemLimitationVisualStyle.UNRESOLVED_LEGACY
                }
            ) {
                add(MapAccessibilityStateToken.LIMITED_INFORMATION)
            }
        }

        return MapItemVisualPolicyResult.Renderable(
            MapItemRenderSpec(
                identity = item.identity,
                geometry = item.geometry,
                content = MapItemRenderContent(item.normalizedKind, item.label),
                visual = MapItemVisualSpec(
                    category = category,
                    lifecycle = lifecycle,
                    temporal = temporal,
                    situationEvidence = situationEvidence,
                    coordinationParticipation = participation,
                    limitations = limitations,
                    emphasis = emphasis,
                    prominence = when (emphasis) {
                        MapItemEmphasis.NORMAL -> if (
                            temporal == MapTemporalVisualStyle.EXPIRED ||
                            category == MapCategoryVisualStyle.UNRESOLVED
                        ) MapItemProminence.MUTED else MapItemProminence.STANDARD
                        else -> MapItemProminence.EMPHASIZED
                    },
                    labelMode = labelMode,
                    radiusMode = radiusMode,
                ),
                interaction = MapItemRenderInteractionSpec(selectable = true, detailOpenable = true),
                accessibility = MapItemAccessibilitySpec(
                    category = when (category) {
                        MapCategoryVisualStyle.SITUATION -> MapAccessibilityCategory.SITUATION
                        MapCategoryVisualStyle.COORDINATION -> MapAccessibilityCategory.COORDINATION
                        MapCategoryVisualStyle.UNRESOLVED, MapCategoryVisualStyle.CANONICAL_ONLY ->
                            MapAccessibilityCategory.UNRESOLVED_ITEM
                    },
                    stateTokens = accessibilityTokens,
                ),
            ),
        )
    }

    private fun lifecycle(
        item: MapSceneItem,
        category: MapCategoryVisualStyle,
    ): MapLifecycleVisualStyle? = when (category) {
        MapCategoryVisualStyle.SITUATION -> when (item.domain?.lifecycle) {
            MapSituationLifecycleStatus.ACTIVE -> MapLifecycleVisualStyle.ACTIVE
            MapSituationLifecycleStatus.NO_LONGER_ACTIVE -> MapLifecycleVisualStyle.RESOLVED
            null -> null
        }
        else -> null
    }

    private fun temporal(
        item: MapSceneItem,
        category: MapCategoryVisualStyle,
    ): MapTemporalVisualStyle? = when (item.temporal) {
        MapSceneTemporalPresentation.Current -> MapTemporalVisualStyle.CURRENT
        MapSceneTemporalPresentation.Expired -> MapTemporalVisualStyle.EXPIRED
        MapSceneTemporalPresentation.InteractionClosed -> MapTemporalVisualStyle.INTERACTION_CLOSED
        MapSceneTemporalPresentation.Resolved ->
            if (category == MapCategoryVisualStyle.SITUATION &&
                item.domain?.lifecycle == MapSituationLifecycleStatus.NO_LONGER_ACTIVE
            ) MapTemporalVisualStyle.CURRENT else null
        MapSceneTemporalPresentation.Unresolved ->
            if (category == MapCategoryVisualStyle.UNRESOLVED ||
                category == MapCategoryVisualStyle.CANONICAL_ONLY
            ) MapTemporalVisualStyle.UNRESOLVED else null
        MapSceneTemporalPresentation.Deleted -> null
    }

    private fun situationEvidence(
        item: MapSceneItem,
        category: MapCategoryVisualStyle,
    ): MapSituationEvidenceVisualStyle? = when (category) {
        MapCategoryVisualStyle.SITUATION -> when (val result = item.verification) {
            is MapItemVerificationProjectionResult.Available -> {
                if (result.presentation.identity != item.identity) null else when (result.presentation.evidence) {
                    is MapVerificationEvidence.Present ->
                        MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_PRESENT
                    MapVerificationEvidence.Absent -> when (result.presentation.freshnessBasis) {
                        MapSituationConfirmationFreshnessBasis.InvalidatedByResolution,
                        MapSituationConfirmationFreshnessBasis.InvalidatedBySituationChange ->
                            MapSituationEvidenceVisualStyle.HISTORICAL_OR_STALE_ONLY
                        else -> MapSituationEvidenceVisualStyle.CURRENT_EVIDENCE_ABSENT
                    }
                }
            }
            is MapItemVerificationProjectionResult.Unavailable ->
                MapSituationEvidenceVisualStyle.UNAVAILABLE
            else -> null
        }
        MapCategoryVisualStyle.COORDINATION ->
            if (item.verification == MapItemVerificationProjectionResult.CoordinationNotApplicable) {
                MapSituationEvidenceVisualStyle.NOT_APPLICABLE
            } else null
        MapCategoryVisualStyle.UNRESOLVED, MapCategoryVisualStyle.CANONICAL_ONLY ->
            MapSituationEvidenceVisualStyle.UNAVAILABLE
    }

    private fun participation(
        item: MapSceneItem,
        category: MapCategoryVisualStyle,
    ): MapCoordinationParticipationVisualStyle? = when (category) {
        MapCategoryVisualStyle.COORDINATION -> when (val result = item.participation) {
            is MapCoordinationParticipationProjectionResult.Available -> {
                if (result.presentation.identity != item.identity) null else when (result.presentation.evidence) {
                    is MapCoordinationAcknowledgementEvidence.Present ->
                        MapCoordinationParticipationVisualStyle.ACKNOWLEDGED
                    MapCoordinationAcknowledgementEvidence.Absent ->
                        MapCoordinationParticipationVisualStyle.NOT_ACKNOWLEDGED
                }
            }
            else -> null
        }
        MapCategoryVisualStyle.SITUATION ->
            if (item.participation == MapCoordinationParticipationProjectionResult.NotApplicableToSituation) {
                MapCoordinationParticipationVisualStyle.NOT_APPLICABLE
            } else null
        MapCategoryVisualStyle.UNRESOLVED, MapCategoryVisualStyle.CANONICAL_ONLY ->
            MapCoordinationParticipationVisualStyle.UNAVAILABLE
    }

    private fun app.peermesh.map.application.detail.MapItemIdentity.matches(
        geometry: app.peermesh.map.application.scene.MapSceneGeometry,
    ): Boolean = when (itemType) {
        MapItemType.MARKER -> geometry is app.peermesh.map.application.scene.MapSceneGeometry.Marker
        MapItemType.AREA -> geometry is app.peermesh.map.application.scene.MapSceneGeometry.Area
    }

    private fun unsupported(item: MapSceneItem, reason: MapVisualPolicyFailure) =
        MapItemVisualPolicyResult.Unsupported(item.identity, reason)

    private companion object {
        val NON_RENDERABLE_OUTCOMES = setOf(
            MapSceneItemOutcome.MISSING,
            MapSceneItemOutcome.INVALID_CANONICAL,
            MapSceneItemOutcome.INVALID_DOMAIN,
        )
    }
}
