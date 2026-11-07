package app.peermesh.map.application.participation

import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.map.application.detail.AreaCanonicalPresentation
import app.peermesh.map.application.detail.InvalidIdentityReason
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemDetailLoadResult
import app.peermesh.map.application.detail.MarkerCanonicalPresentation

fun interface ProjectMapCoordinationParticipation {
    fun project(detailResult: MapItemDetailLoadResult): MapCoordinationParticipationProjectionResult
}

sealed interface MapCoordinationParticipationProjectionResult {
    data class Available(
        val presentation: MapCoordinationParticipationPresentation,
    ) : MapCoordinationParticipationProjectionResult

    data object NotApplicableToSituation : MapCoordinationParticipationProjectionResult

    data object UnresolvedLegacyCategory : MapCoordinationParticipationProjectionResult

    data object DomainStateUnavailable : MapCoordinationParticipationProjectionResult

    data object TargetUnavailable : MapCoordinationParticipationProjectionResult

    data class InvalidIdentity(
        val reason: InvalidIdentityReason,
    ) : MapCoordinationParticipationProjectionResult

    data object InvalidCanonicalPersistedState : MapCoordinationParticipationProjectionResult

    data object InvalidDomainPersistedState : MapCoordinationParticipationProjectionResult

    data object InternalProjectionFailure : MapCoordinationParticipationProjectionResult
}

/**
 * Pure M3C3 current-participation projector.
 *
 * Category origin is the accepted M3A presentation type, which currently
 * originates in app.peermesh.data.db. That inherited enum is mapped straight
 * into M3C3's UI-safe vocabulary; no DAO, Room entity, repository, database
 * operation, or other persistence implementation dependency is introduced.
 */
class DefaultMapCoordinationParticipationProjector : ProjectMapCoordinationParticipation {
    override fun project(
        detailResult: MapItemDetailLoadResult,
    ): MapCoordinationParticipationProjectionResult = when (detailResult) {
        is MapItemDetailLoadResult.Found -> projectFound(detailResult.detail)
        is MapItemDetailLoadResult.UnresolvedLegacy -> projectUnresolved(detailResult.detail)
        is MapItemDetailLoadResult.CanonicalOnly ->
            MapCoordinationParticipationProjectionResult.DomainStateUnavailable
        is MapItemDetailLoadResult.InvalidIdentity ->
            MapCoordinationParticipationProjectionResult.InvalidIdentity(detailResult.reason)
        MapItemDetailLoadResult.CanonicalItemMissing ->
            MapCoordinationParticipationProjectionResult.TargetUnavailable
        MapItemDetailLoadResult.InvalidCanonicalPersistedState ->
            MapCoordinationParticipationProjectionResult.InvalidCanonicalPersistedState
        is MapItemDetailLoadResult.InvalidDomainPersistedState ->
            MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState
        MapItemDetailLoadResult.InternalReadFailure ->
            MapCoordinationParticipationProjectionResult.InternalProjectionFailure
    }

    private fun projectFound(detail: MapItemDetail): MapCoordinationParticipationProjectionResult {
        if (!detail.hasConsistentCanonicalIdentity()) {
            return MapCoordinationParticipationProjectionResult.InvalidCanonicalPersistedState
        }
        val domain = detail.domain
            ?: return MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState
        if (domain.confirmationActorCount < 0 || domain.acknowledgementActorCount < 0) {
            return MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState
        }

        return when (domain.category) {
            MapItemCategory.SITUATION -> {
                if (domain.categoryOrigin == MapItemCategoryOrigin.UNRESOLVED || domain.lifecycle == null) {
                    MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState
                } else {
                    MapCoordinationParticipationProjectionResult.NotApplicableToSituation
                }
            }
            MapItemCategory.COORDINATION -> {
                if (domain.categoryOrigin == MapItemCategoryOrigin.UNRESOLVED ||
                    domain.creatorInformationSource != null ||
                    domain.lifecycle != null
                ) {
                    return MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState
                }
                val origin = when (domain.categoryOrigin) {
                    MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1 ->
                        MapCoordinationCategoryOrigin.EXPLICIT_DOMAIN_V1
                    MapItemCategoryOrigin.LEGACY_KIND_DERIVED ->
                        MapCoordinationCategoryOrigin.LEGACY_KIND_DERIVED
                    MapItemCategoryOrigin.UNRESOLVED -> error("handled above")
                }
                MapCoordinationParticipationProjectionResult.Available(
                    MapCoordinationParticipationPresentation(
                        identity = detail.identity,
                        targetShape = when (detail.identity.itemType) {
                            MapItemType.MARKER -> MapCoordinationTargetShape.MARKER
                            MapItemType.AREA -> MapCoordinationTargetShape.AREA
                        },
                        categoryOrigin = origin,
                        evidence = if (domain.acknowledgementActorCount == 0) {
                            MapCoordinationAcknowledgementEvidence.Absent
                        } else {
                            MapCoordinationAcknowledgementEvidence.Present(domain.acknowledgementActorCount)
                        },
                        limitation = MapCoordinationParticipationLimitation.ACKNOWLEDGEMENT_IS_NON_VERIFYING,
                    ),
                )
            }
            null -> MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState
        }
    }

    private fun projectUnresolved(detail: MapItemDetail): MapCoordinationParticipationProjectionResult {
        if (!detail.hasConsistentCanonicalIdentity()) {
            return MapCoordinationParticipationProjectionResult.InvalidCanonicalPersistedState
        }
        val domain = detail.domain
            ?: return MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState
        return if (
            domain.category == null &&
            domain.categoryOrigin == MapItemCategoryOrigin.UNRESOLVED &&
            domain.creatorInformationSource == null &&
            domain.lifecycle == null &&
            domain.confirmationActorCount >= 0 &&
            domain.acknowledgementActorCount >= 0
        ) {
            MapCoordinationParticipationProjectionResult.UnresolvedLegacyCategory
        } else {
            MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState
        }
    }

    private fun MapItemDetail.hasConsistentCanonicalIdentity(): Boolean =
        canonical.identity == identity && when (identity.itemType) {
            MapItemType.MARKER -> canonical is MarkerCanonicalPresentation
            MapItemType.AREA -> canonical is AreaCanonicalPresentation
        }
}

object MapCoordinationParticipationPresentationFactory {
    fun create(): ProjectMapCoordinationParticipation =
        DefaultMapCoordinationParticipationProjector()
}
