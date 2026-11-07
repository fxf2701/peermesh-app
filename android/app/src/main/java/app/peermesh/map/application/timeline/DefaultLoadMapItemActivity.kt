package app.peermesh.map.application.timeline

import app.peermesh.data.map.MapActionHistoryCursor
import app.peermesh.data.map.MapItemActionQueryRepository
import app.peermesh.data.map.MapQueryResult
import app.peermesh.map.application.detail.MapItemIdentity

/**
 * M3B1 one-page read boundary.
 *
 * Must remain read-only and depend only on the accepted M2 action-query repository plus a pure
 * projector. No DAO, reducer, processor, durable-event log, BLE or relay dependency belongs here.
 */
class DefaultLoadMapItemActivity(
    private val repository: MapItemActionQueryRepository,
    private val projector: MapItemActivityProjector,
) : LoadMapItemActivity {

    override fun load(
        identity: MapItemIdentity,
        after: MapItemActivityCursor?,
        limit: Int,
    ): MapItemActivityLoadResult {
        if (identity.groupId.isBlank()) return invalid(InvalidMapItemActivityIdentityReason.BLANK_GROUP_ID)
        if (identity.itemId.isBlank()) return invalid(InvalidMapItemActivityIdentityReason.BLANK_ITEM_ID)
        if (limit <= 0) return invalid(InvalidMapItemActivityIdentityReason.NON_POSITIVE_LIMIT)
        if (after != null && after !is DataMapItemActivityCursor) {
            return invalid(InvalidMapItemActivityIdentityReason.INVALID_CURSOR)
        }

        return try {
            when (val result = repository.appliedHistory(
                gid = identity.groupId,
                identity = MapItemActivityIdentityAdapter.toDataIdentity(identity),
                after = (after as? DataMapItemActivityCursor)?.value,
                limit = limit,
            )) {
                is MapQueryResult.Success -> {
                    val entries = ArrayList<MapItemActivityEntry>(result.value.actions.size)
                    for (action in result.value.actions) {
                        val entry = projector.project(identity, action)
                            ?: return MapItemActivityLoadResult.InvalidPersistedHistory
                        entries += entry
                    }
                    MapItemActivityLoadResult.Loaded(
                        MapItemActivityPage(
                            entries = entries,
                            nextCursor = result.value.nextCursor?.let(::DataMapItemActivityCursor),
                            requestedLimit = limit,
                        ),
                    )
                }
                is MapQueryResult.InvalidRecord -> MapItemActivityLoadResult.InvalidPersistedHistory
                is MapQueryResult.InvalidInput -> MapItemActivityLoadResult.InternalReadFailure
            }
        } catch (_: Exception) {
            MapItemActivityLoadResult.InternalReadFailure
        }
    }

    private fun invalid(reason: InvalidMapItemActivityIdentityReason) =
        MapItemActivityLoadResult.InvalidIdentity(reason)
}

/** Internal bridge keeps the complete M2 cursor opaque to presentation contracts. */
internal data class DataMapItemActivityCursor(
    val value: MapActionHistoryCursor,
) : MapItemActivityCursor
