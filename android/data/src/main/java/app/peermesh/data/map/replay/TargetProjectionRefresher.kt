package app.peermesh.data.map.replay

import app.peermesh.data.map.MapItemMaterializationSnapshot
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.PersistenceWriteResult
import app.peermesh.data.db.dao.MapItemDomainStateDao
import app.peermesh.data.map.materialization.RoomMapItemActionProjectionDeriver

/**
 * Projection refresh seam reused after a replay batch.
 * Implementations derive the complete projection from all applied history and perform exactly one
 * guarded domain-state mutation. They must not patch counters, pointers, or lifecycle incrementally.
 */
internal fun interface TargetProjectionRefresher {
    fun refresh(snapshot: MapItemMaterializationSnapshot): ProjectionRefreshResult
}

internal sealed interface ProjectionRefreshResult {
    data object Refreshed : ProjectionRefreshResult
    data class InvalidPersistedState(val detail: String) : ProjectionRefreshResult
}

internal class RoomTargetProjectionRefresher(
    private val deriver: RoomMapItemActionProjectionDeriver,
    private val domainStateDao: MapItemDomainStateDao,
) : TargetProjectionRefresher {
    override fun refresh(snapshot: MapItemMaterializationSnapshot): ProjectionRefreshResult {
        val category = snapshot.category
            ?: return ProjectionRefreshResult.InvalidPersistedState("missing_target_category")
        val projection = when (val result = deriver.derive(
            snapshot.gid, snapshot.identity.itemType, snapshot.identity.itemId, category,
        )) {
            is PersistenceParseResult.Success -> result.value
            else -> return ProjectionRefreshResult.InvalidPersistedState(result.toString())
        }
        return when (val result = domainStateDao.updateActionProjection(
            snapshot.gid, snapshot.identity.itemType, snapshot.identity.itemId, projection,
        )) {
            is PersistenceWriteResult.Rejected -> ProjectionRefreshResult.InvalidPersistedState(result.failure.toString())
            is PersistenceWriteResult.Success -> if (result.value == 1) ProjectionRefreshResult.Refreshed
                else ProjectionRefreshResult.InvalidPersistedState("action_projection_affected_${result.value}_rows")
        }
    }
}
