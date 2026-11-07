package app.peermesh.data.map

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemStateOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.db.PersistenceParseResult

data class MapItemIdentity(val itemType: MapItemType, val itemId: String)
data class MapItemWindows(val expiresAt: Long?, val ownerEditUntil: Long?, val interactionUntil: Long?)

/**
 * UI-safe semantic explanation basis derived at the accepted M2 read boundary.
 *
 * Full MapActionOrderKey values must never cross this boundary. M3C projectors
 * consume only this classified result and optional safe timestamps.
 */
sealed interface MapSituationConfirmationFreshnessBasis {
    data class Current(
        val latestCurrentConfirmationAtSeconds: Long?,
    ) : MapSituationConfirmationFreshnessBasis

    data object NoAcceptedPriorConfirmationEstablished : MapSituationConfirmationFreshnessBasis
    data object InvalidatedBySituationChange : MapSituationConfirmationFreshnessBasis
    data object InvalidatedByResolution : MapSituationConfirmationFreshnessBasis

    /** Legacy or otherwise unprovable explanation; presentation must remain honest. */
    data object Unknown : MapSituationConfirmationFreshnessBasis
}

data class MapItemDomainState(
    val gid: String,
    val identity: MapItemIdentity,
    val category: MapItemCategory?,
    val categoryOrigin: MapItemCategoryOrigin,
    val creatorInformationSource: MapInformationSource?,
    val lifecycle: MapSituationLifecycleStatus?,
    val windows: MapItemWindows,
    val temporalOrigin: MapItemTemporalOrigin,
    val confirmationActorCount: Int,
    val acknowledgementActorCount: Int,
    /**
     * Conservative default preserves source compatibility for pre-M3C2 fixtures.
     * Production entity mapping always supplies an explicit classification.
     */
    val confirmationFreshnessBasis: MapSituationConfirmationFreshnessBasis =
        MapSituationConfirmationFreshnessBasis.Unknown,
)

internal data class MapItemMaterializationSnapshot(
    val gid: String, val identity: MapItemIdentity, val category: MapItemCategory?,
    val categoryOrigin: MapItemCategoryOrigin, val creatorInformationSource: MapInformationSource?,
    val ownerDid: String, val domainVersion: Int?, val stateOrigin: MapItemStateOrigin,
    val lifecycle: MapSituationLifecycleStatus?, val windows: MapItemWindows,
    val temporalOrigin: MapItemTemporalOrigin, val canonicalAddOrder: MapActionOrderKey?,
    val latestConfirmationOrder: MapActionOrderKey?, val latestUpdateOrder: MapActionOrderKey?,
    val latestLifecycleOrder: MapActionOrderKey?, val latestMaterialChangeOrder: MapActionOrderKey?,
    val confirmationActorCount: Int, val acknowledgementActorCount: Int,
)

data class MapItemActionRead(
    val gid: String, val identity: MapItemIdentity, val action: MapItemAction,
    val order: MapActionOrderKey, val informationSource: MapInformationSource?,
    val note: String?, val updateDisposition: MapSituationUpdateType?,
    val targetLifecycle: MapSituationLifecycleStatus?,
)

data class MapActionHistoryCursor(val order: MapActionOrderKey)
data class MapActionHistoryPage(val actions: List<MapItemActionRead>, val nextCursor: MapActionHistoryCursor?)

sealed interface MapQueryResult<out T> {
    data class Success<T>(val value: T) : MapQueryResult<T>
    data class InvalidInput(val failure: PersistenceParseResult<Nothing>) : MapQueryResult<Nothing>
    data class InvalidRecord(val failure: PersistenceParseResult<Nothing>) : MapQueryResult<Nothing>
}

sealed interface MapItemLookupResult {
    data class Found(val state: MapItemDomainState) : MapItemLookupResult
    data object Missing : MapItemLookupResult
    data class Invalid(val failure: PersistenceParseResult<Nothing>) : MapItemLookupResult
}

data class MapItemBatchLookupResult(
    val found: Map<MapItemIdentity, MapItemDomainState>,
    val missing: Set<MapItemIdentity>,
    val invalid: Map<MapItemIdentity, PersistenceParseResult<Nothing>>,
)
