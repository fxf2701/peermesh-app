package app.peermesh.map.application.scene

import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.map.MapItemBatchLookupResult
import app.peermesh.data.map.MapItemDomainState
import app.peermesh.data.map.MapItemDomainStateRepository
import app.peermesh.data.map.MapQueryResult
import app.peermesh.data.map.canonical.AreaCanonicalRead
import app.peermesh.data.map.canonical.MapCanonicalAreaGeometry
import app.peermesh.data.map.canonical.MapCanonicalAreaGeometrySummary
import app.peermesh.data.map.canonical.MapCanonicalAvailability
import app.peermesh.data.map.canonical.MapItemCanonicalBatchReadRepository
import app.peermesh.data.map.canonical.MapItemCanonicalRead
import app.peermesh.data.map.canonical.MarkerCanonicalRead
import app.peermesh.map.application.detail.AreaCanonicalPresentation
import app.peermesh.map.application.detail.MapAreaGeometrySummary
import app.peermesh.map.application.detail.MapCoordinatesPresentation
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemDetailLoadResult
import app.peermesh.map.application.detail.MapItemDomainPresentation
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.detail.MarkerCanonicalPresentation
import app.peermesh.map.application.interaction.MapAvailabilityClock
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.participation.ProjectMapCoordinationParticipation
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult
import app.peermesh.map.application.verification.ProjectMapItemVerification

class DefaultLoadMapSceneItems(
    private val canonicalRepository: MapItemCanonicalBatchReadRepository,
    private val domainStateRepository: MapItemDomainStateRepository,
    private val clock: MapAvailabilityClock,
    private val verificationProjector: ProjectMapItemVerification,
    private val participationProjector: ProjectMapCoordinationParticipation,
) : LoadMapSceneItems {

    companion object {
        /** A visible map set should remain bounded independently of SQLite bind chunking. */
        const val MAX_SCENE_ITEM_IDENTITIES = 1_000
        const val MAX_RAW_SCENE_ITEM_IDENTITIES = 4_000
    }

    override fun load(
        groupId: String,
        identities: Collection<MapItemIdentity>,
    ): MapSceneLoadResult {
        if (groupId.isBlank()) {
            return MapSceneLoadResult.InvalidRequest(MapSceneInvalidRequestReason.BlankGroupId)
        }
        // This guard bounds duplicate-heavy inputs before materialising a LinkedHashSet.
        if (identities.size > MAX_RAW_SCENE_ITEM_IDENTITIES) {
            return MapSceneLoadResult.InvalidRequest(MapSceneInvalidRequestReason.TooManyIdentities)
        }
        val requested = linkedSetOf<MapItemIdentity>()
        for (identity in identities) {
            if (identity.groupId.isBlank() || identity.itemId.isBlank()) {
                return MapSceneLoadResult.InvalidRequest(MapSceneInvalidRequestReason.InvalidIdentity)
            }
            if (identity.groupId != groupId) {
                return MapSceneLoadResult.InvalidRequest(MapSceneInvalidRequestReason.GroupMismatch)
            }
            requested += identity
            if (requested.size > MAX_SCENE_ITEM_IDENTITIES) {
                return MapSceneLoadResult.InvalidRequest(MapSceneInvalidRequestReason.TooManyIdentities)
            }
        }
        if (requested.isEmpty()) return MapSceneLoadResult.Success(emptyList())
        val dataIdentities = requested.mapTo(linkedSetOf()) { it.toDataIdentity() }

        val canonical = try {
            canonicalRepository.findBatch(groupId, dataIdentities)
        } catch (_: Exception) {
            return failure(MapSceneLoadFailure.CanonicalReadUnavailable)
        }
        if (canonical.readFailure) {
            return failure(MapSceneLoadFailure.CanonicalReadUnavailable)
        }
        if (canonical.hasUntrustworthyKeys(dataIdentities)) {
            return failure(MapSceneLoadFailure.InvalidRepositoryResult)
        }

        val domain = try {
            domainStateRepository.findBatch(groupId, dataIdentities)
        } catch (_: Exception) {
            return failure(MapSceneLoadFailure.DomainStateReadUnavailable)
        }
        val domainBatch = when (domain) {
            is MapQueryResult.Success -> domain.value
            is MapQueryResult.InvalidInput, is MapQueryResult.InvalidRecord ->
                return failure(MapSceneLoadFailure.DomainStateReadUnavailable)
        }
        if (domainBatch.hasUntrustworthyKeys(dataIdentities)) {
            return failure(MapSceneLoadFailure.InvalidRepositoryResult)
        }
        val now = try {
            clock.nowSeconds()
        } catch (_: Exception) {
            return failure(MapSceneLoadFailure.ClockUnavailable)
        }

        return MapSceneLoadResult.Success(
            requested.map { identity ->
                val key = identity.toDataIdentity()
                val canonicalConflict = listOf(
                    key in canonical.found,
                    key in canonical.missing,
                    key in canonical.invalid,
                ).count { it } != 1
                val domainConflict = listOf(
                    key in domainBatch.found,
                    key in domainBatch.missing,
                    key in domainBatch.invalid,
                ).count { it } != 1
                when {
                    canonicalConflict -> MapSceneItemLoadResult.InvalidCanonical(identity)
                    domainConflict -> MapSceneItemLoadResult.InvalidDomain(identity)
                    key in canonical.invalid -> MapSceneItemLoadResult.InvalidCanonical(identity)
                    key in canonical.missing -> {
                        if (key in domainBatch.invalid || key in domainBatch.found) {
                            MapSceneItemLoadResult.InvalidDomain(identity)
                        } else {
                            MapSceneItemLoadResult.Missing(identity)
                        }
                    }
                    else -> project(
                        identity = identity,
                        canonical = canonical.found.getValue(key),
                        domain = domainBatch.found[key],
                        domainInvalid = key in domainBatch.invalid,
                        nowSeconds = now,
                    )
                }
            },
        )
    }

    private fun project(
        identity: MapItemIdentity,
        canonical: MapItemCanonicalRead,
        domain: MapItemDomainState?,
        domainInvalid: Boolean,
        nowSeconds: Long,
    ): MapSceneItemLoadResult {
        if (canonical.groupId != identity.groupId ||
            canonical.itemType != identity.itemType ||
            canonical.itemId != identity.itemId
        ) {
            return MapSceneItemLoadResult.InvalidCanonical(identity)
        }
        val geometry = canonical.toSceneGeometry() ?: return MapSceneItemLoadResult.InvalidCanonical(identity)
        val canonicalPresentation = canonical.toDetailPresentation(identity)
        if (domainInvalid) return MapSceneItemLoadResult.InvalidDomain(identity)
        if (domain == null) {
            val detail = MapItemDetail(identity, canonicalPresentation, null)
            return MapSceneItemLoadResult.CanonicalOnly(
                canonical.toSceneItem(
                    identity,
                    geometry,
                    null,
                    MapSceneTemporalPresentation.Unresolved,
                    verificationProjector.project(
                        MapItemDetailLoadResult.CanonicalOnly(
                            detail,
                            app.peermesh.map.application.detail.CanonicalOnlyReason.DOMAIN_STATE_MISSING,
                        ),
                    ),
                    participationProjector.project(
                        MapItemDetailLoadResult.CanonicalOnly(
                            detail,
                            app.peermesh.map.application.detail.CanonicalOnlyReason.DOMAIN_STATE_MISSING,
                        ),
                    ),
                ),
            )
        }
        if (domain.gid != identity.groupId ||
            domain.identity != identity.toDataIdentity()
        ) {
            return MapSceneItemLoadResult.InvalidDomain(identity)
        }
        val domainPresentation = domain.toPresentation()
        val detail = MapItemDetail(identity, canonicalPresentation, domainPresentation)
        val detailResult = if (domainPresentation.isUnresolvedLegacyCategory) {
            MapItemDetailLoadResult.UnresolvedLegacy(detail)
        } else {
            MapItemDetailLoadResult.Found(detail)
        }
        val verification = verificationProjector.project(detailResult)
        val participation = participationProjector.project(detailResult)
        if (verification.isInvalidDomain() || participation.isInvalidDomain()) {
            return MapSceneItemLoadResult.InvalidDomain(identity)
        }
        val item = canonical.toSceneItem(
            identity,
            geometry,
            domainPresentation,
            temporal(canonical, domainPresentation, nowSeconds),
            verification,
            participation,
        )
        return if (detailResult is MapItemDetailLoadResult.UnresolvedLegacy) {
            MapSceneItemLoadResult.UnresolvedLegacy(item)
        } else {
            MapSceneItemLoadResult.Found(item)
        }
    }

    private fun MapItemCanonicalRead.toSceneGeometry(): MapSceneGeometry? = when (this) {
        is MarkerCanonicalRead -> MapSceneGeometry.Marker(latitude, longitude, radiusMeters)
        is AreaCanonicalRead -> when (val value = geometry) {
            is MapCanonicalAreaGeometry.Polygon -> MapSceneGeometry.Area(
                MapSceneAreaGeometry.Polygon(value.rings.map { ring ->
                    ring.map { MapSceneCoordinate(it.latitude, it.longitude) }
                }),
            )
            is MapCanonicalAreaGeometry.MultiPolygon -> MapSceneGeometry.Area(
                MapSceneAreaGeometry.MultiPolygon(value.polygons.map { polygon ->
                    polygon.map { ring -> ring.map { MapSceneCoordinate(it.latitude, it.longitude) } }
                }),
            )
            null -> null
        }
    }

    private fun MapItemCanonicalRead.toDetailPresentation(identity: MapItemIdentity) = when (this) {
        is MarkerCanonicalRead -> MarkerCanonicalPresentation(
            identity, normalizedKind, label, note, availability.toPresentation(), expiresAt,
            MapCoordinatesPresentation(latitude, longitude), radiusMeters,
        )
        is AreaCanonicalRead -> AreaCanonicalPresentation(
            identity, normalizedKind, label, note, availability.toPresentation(), expiresAt,
            when (val summary = geometrySummary) {
                is MapCanonicalAreaGeometrySummary.Polygon -> MapAreaGeometrySummary.Polygon(summary.vertexCount)
                is MapCanonicalAreaGeometrySummary.MultiPolygon ->
                    MapAreaGeometrySummary.MultiPolygon(summary.polygonCount, summary.vertexCount)
            },
        )
    }

    private fun MapCanonicalAvailability.toPresentation() = when (this) {
        MapCanonicalAvailability.ACTIVE -> MapItemCanonicalAvailability.ACTIVE
        MapCanonicalAvailability.DELETED -> MapItemCanonicalAvailability.DELETED
    }

    private fun MapItemDomainState.toPresentation() = MapItemDomainPresentation(
        category, categoryOrigin, creatorInformationSource, lifecycle, windows, temporalOrigin,
        confirmationActorCount, acknowledgementActorCount, confirmationFreshnessBasis,
    )

    private fun MapItemCanonicalRead.toSceneItem(
        identity: MapItemIdentity,
        geometry: MapSceneGeometry,
        domain: MapItemDomainPresentation?,
        temporal: MapSceneTemporalPresentation,
        verification: MapItemVerificationProjectionResult,
        participation: MapCoordinationParticipationProjectionResult,
    ) = MapSceneItem(
        identity, normalizedKind, label, note, availability.toPresentation(), expiresAt,
        geometry, domain, temporal, verification, participation,
    )

    private fun temporal(
        canonical: MapItemCanonicalRead,
        domain: MapItemDomainPresentation,
        nowSeconds: Long,
    ): MapSceneTemporalPresentation {
        val expiresAt = domain.windows.expiresAt
        val interactionUntil = domain.windows.interactionUntil
        return when {
            canonical.availability == MapCanonicalAvailability.DELETED -> MapSceneTemporalPresentation.Deleted
            domain.isUnresolvedLegacyCategory -> MapSceneTemporalPresentation.Unresolved
            domain.lifecycle == MapSituationLifecycleStatus.NO_LONGER_ACTIVE -> MapSceneTemporalPresentation.Resolved
            expiresAt != null && nowSeconds >= expiresAt -> MapSceneTemporalPresentation.Expired
            interactionUntil != null && nowSeconds >= interactionUntil ->
                MapSceneTemporalPresentation.InteractionClosed
            else -> MapSceneTemporalPresentation.Current
        }
    }

    private fun MapItemIdentity.toDataIdentity() =
        app.peermesh.data.map.MapItemIdentity(itemType, itemId)

    private fun app.peermesh.data.map.canonical.MapItemCanonicalBatchLookupResult.hasUntrustworthyKeys(
        requested: Set<app.peermesh.data.map.MapItemIdentity>,
    ): Boolean = (found.keys + missing + invalid).any { it !in requested }

    private fun MapItemBatchLookupResult.hasUntrustworthyKeys(
        requested: Set<app.peermesh.data.map.MapItemIdentity>,
    ): Boolean = (found.keys + missing + invalid.keys).any { it !in requested }

    private fun MapItemVerificationProjectionResult.isInvalidDomain() =
        this is MapItemVerificationProjectionResult.Unavailable &&
            reason == app.peermesh.map.application.verification.MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE

    private fun MapCoordinationParticipationProjectionResult.isInvalidDomain() =
        this == MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState

    private fun failure(failure: MapSceneLoadFailure) = MapSceneLoadResult.InternalFailure(failure)
}
