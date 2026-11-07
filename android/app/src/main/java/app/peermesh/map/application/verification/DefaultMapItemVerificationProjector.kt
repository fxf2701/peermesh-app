package app.peermesh.map.application.verification

import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis
import app.peermesh.map.application.detail.AreaCanonicalPresentation
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemDetailLoadResult
import app.peermesh.map.application.detail.MarkerCanonicalPresentation

/**
 * Pure M3C1 projection from one accepted M3A detail snapshot.
 *
 * Implementation constraints:
 * - consume only the supplied M3A detail snapshot;
 * - perform no repository, DAO, timeline, processor, BLE or relay access;
 * - reuse M2-projected counts and lifecycle without recalculation;
 * - never infer category or current evidence from history;
 * - propagate fatal JVM Error subclasses.
 */
class DefaultMapItemVerificationProjector : ProjectMapItemVerification {
    override fun project(
        detailResult: MapItemDetailLoadResult,
    ): MapItemVerificationProjectionResult = when (detailResult) {
        is MapItemDetailLoadResult.Found -> projectFound(detailResult.detail)
        is MapItemDetailLoadResult.UnresolvedLegacy -> projectUnresolved(detailResult.detail)
        is MapItemDetailLoadResult.CanonicalOnly -> unavailable(MapVerificationUnavailableReason.DOMAIN_STATE_MISSING)
        is MapItemDetailLoadResult.InvalidIdentity ->
            MapItemVerificationProjectionResult.InvalidIdentity(detailResult.reason)
        MapItemDetailLoadResult.CanonicalItemMissing ->
            unavailable(MapVerificationUnavailableReason.CANONICAL_ITEM_MISSING)
        MapItemDetailLoadResult.InvalidCanonicalPersistedState ->
            unavailable(MapVerificationUnavailableReason.INVALID_CANONICAL_PERSISTED_STATE)
        is MapItemDetailLoadResult.InvalidDomainPersistedState ->
            unavailable(MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE)
        MapItemDetailLoadResult.InternalReadFailure ->
            unavailable(MapVerificationUnavailableReason.INTERNAL_READ_FAILURE)
    }

    private fun projectFound(detail: MapItemDetail): MapItemVerificationProjectionResult {
        if (!detail.hasConsistentCanonicalIdentity()) {
            return unavailable(MapVerificationUnavailableReason.INVALID_CANONICAL_PERSISTED_STATE)
        }
        val domain = detail.domain
            ?: return unavailable(MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE)
        if (domain.confirmationActorCount < 0 || domain.acknowledgementActorCount < 0) {
            return unavailable(MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE)
        }
        return when (domain.category) {
            MapItemCategory.COORDINATION -> {
                if (domain.categoryOrigin == MapItemCategoryOrigin.UNRESOLVED ||
                    domain.creatorInformationSource != null ||
                    domain.lifecycle != null
                ) {
                    unavailable(MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE)
                } else {
                    MapItemVerificationProjectionResult.CoordinationNotApplicable
                }
            }
            MapItemCategory.SITUATION -> {
                val lifecycle = domain.lifecycle
                    ?: return unavailable(MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE)
                val origin = when (domain.categoryOrigin) {
                    MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1 -> {
                        if (domain.creatorInformationSource == null) {
                            return unavailable(MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE)
                        }
                        MapVerificationCategoryOrigin.EXPLICIT_DOMAIN_V1
                    }
                    MapItemCategoryOrigin.LEGACY_KIND_DERIVED ->
                        MapVerificationCategoryOrigin.LEGACY_KIND_DERIVED
                    MapItemCategoryOrigin.UNRESOLVED ->
                        return unavailable(MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE)
                }
                val evidence = if (
                    lifecycle == MapSituationLifecycleStatus.ACTIVE && domain.confirmationActorCount > 0
                ) {
                    val latestCurrentConfirmationAtSeconds =
                        (domain.confirmationFreshnessBasis as? MapSituationConfirmationFreshnessBasis.Current)
                            ?.latestCurrentConfirmationAtSeconds
                    MapVerificationEvidence.Present(
                        actorCount = domain.confirmationActorCount,
                        latestCurrentConfirmationAtSeconds = latestCurrentConfirmationAtSeconds,
                    )
                } else {
                    MapVerificationEvidence.Absent
                }
                MapItemVerificationProjectionResult.Available(
                    MapItemVerificationPresentation(
                        identity = detail.identity,
                        targetShape = when (detail.identity.itemType) {
                            MapItemType.MARKER -> MapVerificationTargetShape.MARKER
                            MapItemType.AREA -> MapVerificationTargetShape.AREA
                        },
                        categoryOrigin = origin,
                        lifecycle = lifecycle,
                        evidence = evidence,
                        creatorSource = domain.creatorInformationSource?.let {
                            MapCreatorInformationSourcePresentation.Known(it)
                        } ?: MapCreatorInformationSourcePresentation.UnspecifiedLegacy,
                        limitation = when (detail.identity.itemType) {
                            MapItemType.MARKER -> null
                            MapItemType.AREA -> MapVerificationLimitation.AREA_REPORTED_CONDITION_ONLY
                        },
                        freshnessBasis = domain.confirmationFreshnessBasis,
                    ),
                )
            }
            null -> unavailable(MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE)
        }
    }

    private fun projectUnresolved(detail: MapItemDetail): MapItemVerificationProjectionResult {
        if (!detail.hasConsistentCanonicalIdentity()) {
            return unavailable(MapVerificationUnavailableReason.INVALID_CANONICAL_PERSISTED_STATE)
        }
        val domain = detail.domain
            ?: return unavailable(MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE)
        return if (
            domain.category == null &&
            domain.categoryOrigin == MapItemCategoryOrigin.UNRESOLVED &&
            domain.creatorInformationSource == null &&
            domain.lifecycle == null &&
            domain.confirmationActorCount >= 0 &&
            domain.acknowledgementActorCount >= 0
        ) {
            MapItemVerificationProjectionResult.UnresolvedLegacyCategory
        } else {
            unavailable(MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE)
        }
    }

    private fun MapItemDetail.hasConsistentCanonicalIdentity(): Boolean =
        canonical.identity == identity && when (identity.itemType) {
            MapItemType.MARKER -> canonical is MarkerCanonicalPresentation
            MapItemType.AREA -> canonical is AreaCanonicalPresentation
        }

    private fun unavailable(reason: MapVerificationUnavailableReason) =
        MapItemVerificationProjectionResult.Unavailable(reason)
}
