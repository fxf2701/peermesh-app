package app.peermesh.map.application.detail

import app.peermesh.core.map.MapItemCategory
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.map.MapItemDomainState
import app.peermesh.data.map.MapItemDomainStateRepository
import app.peermesh.data.map.MapItemLookupResult
import app.peermesh.data.map.MapQueryResult
import app.peermesh.data.map.canonical.AreaCanonicalRead
import app.peermesh.data.map.canonical.MapCanonicalAreaGeometrySummary
import app.peermesh.data.map.canonical.MapCanonicalAvailability
import app.peermesh.data.map.canonical.MapItemCanonicalLookupResult
import app.peermesh.data.map.canonical.MapItemCanonicalReadRepository
import app.peermesh.data.map.canonical.MarkerCanonicalRead

/**
 * M3A1 single-target read composition boundary.
 *
 * Intentionally has no action-history, reducer, processor, Room DAO, BLE or
 * relay dependency.
 */
class DefaultMapItemDetailComposer(
    private val canonicalRepository: MapItemCanonicalReadRepository,
    private val domainStateRepository: MapItemDomainStateRepository,
) : LoadMapItemDetail {

    override fun load(identity: MapItemIdentity): MapItemDetailLoadResult {
        identity.validationFailure()?.let {
            return MapItemDetailLoadResult.InvalidIdentity(
                when (it) {
                    MapItemIdentityValidationFailure.BLANK_GROUP_ID -> InvalidIdentityReason.BLANK_GROUP_ID
                    MapItemIdentityValidationFailure.BLANK_ITEM_ID -> InvalidIdentityReason.BLANK_ITEM_ID
                },
            )
        }

        val dataIdentity = app.peermesh.data.map.MapItemIdentity(identity.itemType, identity.itemId)
        val canonicalResult = try {
            canonicalRepository.find(identity.groupId, dataIdentity)
        } catch (_: Exception) {
            return MapItemDetailLoadResult.InternalReadFailure
        }
        val canonical = when (canonicalResult) {
            is MapItemCanonicalLookupResult.Found -> {
                val item = canonicalResult.item
                if (item.groupId != identity.groupId || item.itemType != identity.itemType || item.itemId != identity.itemId) {
                    return MapItemDetailLoadResult.InvalidCanonicalPersistedState
                }
                item.toPresentation(identity)
            }
            MapItemCanonicalLookupResult.Missing -> return MapItemDetailLoadResult.CanonicalItemMissing
            MapItemCanonicalLookupResult.InvalidRecord -> return MapItemDetailLoadResult.InvalidCanonicalPersistedState
            MapItemCanonicalLookupResult.ReadFailure -> return MapItemDetailLoadResult.InternalReadFailure
        }

        val domainResult = try {
            domainStateRepository.find(identity.groupId, dataIdentity)
        } catch (_: Exception) {
            return MapItemDetailLoadResult.InternalReadFailure
        }
        return when (domainResult) {
            is MapQueryResult.InvalidInput -> MapItemDetailLoadResult.InternalReadFailure
            is MapQueryResult.InvalidRecord -> MapItemDetailLoadResult.InvalidDomainPersistedState(canonical)
            is MapQueryResult.Success -> when (val lookup = domainResult.value) {
                MapItemLookupResult.Missing -> MapItemDetailLoadResult.CanonicalOnly(
                    detail = MapItemDetail(identity, canonical, null),
                    reason = CanonicalOnlyReason.DOMAIN_STATE_MISSING,
                )
                is MapItemLookupResult.Invalid -> MapItemDetailLoadResult.InvalidDomainPersistedState(canonical)
                is MapItemLookupResult.Found -> compose(identity, canonical, lookup.state)
            }
        }
    }

    private fun compose(
        requested: MapItemIdentity,
        canonical: MapItemCanonicalPresentation,
        state: MapItemDomainState,
    ): MapItemDetailLoadResult {
        if (state.gid != requested.groupId ||
            state.identity.itemType != requested.itemType ||
            state.identity.itemId != requested.itemId ||
            !state.hasPresentationSafeSemantics()
        ) {
            return MapItemDetailLoadResult.InvalidDomainPersistedState(canonical)
        }
        val domain = state.toPresentation()
        val detail = MapItemDetail(requested, canonical, domain)
        return if (state.categoryOrigin == MapItemCategoryOrigin.UNRESOLVED) {
            MapItemDetailLoadResult.UnresolvedLegacy(detail)
        } else {
            MapItemDetailLoadResult.Found(detail)
        }
    }

    private fun MapItemDomainState.hasPresentationSafeSemantics(): Boolean = when {
        categoryOrigin == MapItemCategoryOrigin.UNRESOLVED ->
            category == null && creatorInformationSource == null && lifecycle == null
        category == null -> false
        category == MapItemCategory.COORDINATION -> creatorInformationSource == null && lifecycle == null
        category == MapItemCategory.SITUATION && categoryOrigin == MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1 ->
            creatorInformationSource != null
        else -> true
    }

    private fun MapItemDomainState.toPresentation() = MapItemDomainPresentation(
        category = category,
        categoryOrigin = categoryOrigin,
        creatorInformationSource = creatorInformationSource,
        lifecycle = lifecycle,
        windows = windows,
        temporalOrigin = temporalOrigin,
        confirmationActorCount = confirmationActorCount,
        acknowledgementActorCount = acknowledgementActorCount,
        confirmationFreshnessBasis = confirmationFreshnessBasis,
    )

    private fun app.peermesh.data.map.canonical.MapItemCanonicalRead.toPresentation(
        identity: MapItemIdentity,
    ): MapItemCanonicalPresentation {
        val availability = when (availability) {
            MapCanonicalAvailability.ACTIVE -> MapItemCanonicalAvailability.ACTIVE
            MapCanonicalAvailability.DELETED -> MapItemCanonicalAvailability.DELETED
        }
        return when (this) {
            is MarkerCanonicalRead -> MarkerCanonicalPresentation(
                identity = identity,
                normalizedKind = normalizedKind,
                label = label,
                note = note,
                availability = availability,
                expiresAt = expiresAt,
                coordinates = MapCoordinatesPresentation(latitude, longitude),
                radiusMeters = radiusMeters,
            )
            is AreaCanonicalRead -> AreaCanonicalPresentation(
                identity = identity,
                normalizedKind = normalizedKind,
                label = label,
                note = note,
                availability = availability,
                expiresAt = expiresAt,
                geometrySummary = when (val geometry = geometrySummary) {
                    is MapCanonicalAreaGeometrySummary.Polygon -> MapAreaGeometrySummary.Polygon(geometry.vertexCount)
                    is MapCanonicalAreaGeometrySummary.MultiPolygon -> MapAreaGeometrySummary.MultiPolygon(
                        geometry.polygonCount,
                        geometry.vertexCount,
                    )
                },
            )
        }
    }
}
