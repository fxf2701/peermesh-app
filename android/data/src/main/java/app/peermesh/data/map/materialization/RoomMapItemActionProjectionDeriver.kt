package app.peermesh.data.map.materialization

import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapItemPersistenceValues
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.dao.MapItemActionDao
import app.peermesh.data.db.failure
import app.peermesh.data.map.MapItemActionRead
import app.peermesh.data.map.MapItemEntityMappers

internal class RoomMapItemActionProjectionDeriver(
    private val actionDao: MapItemActionDao,
) {
    fun derive(
        gid: String,
        itemType: MapItemType,
        itemId: String,
        category: MapItemCategory,
    ): PersistenceParseResult<MapItemActionProjection> {
        if (gid.isBlank() || itemId.isBlank()) return PersistenceParseResult.InvalidRecordShape("target identity must be nonblank")
        val persistedType = MapItemPersistenceValues.ItemType.valueOf(itemType)
        val history = mutableListOf<MapItemActionRead>()
        var cursor: MapItemActionRead? = null
        do {
            val rows = actionDao.findAppliedHistory(
                gid, persistedType, itemId, cursor?.order?.timestampSeconds, cursor?.order?.lamport,
                cursor?.order?.actorDid, cursor?.order?.eventId, PAGE_SIZE,
            )
            for (row in rows) {
                val mapped = MapItemEntityMappers.action(row)
                if (mapped !is PersistenceParseResult.Success) return mapped.failure()
                history += mapped.value
            }
            cursor = history.lastOrNull()
        } while (rows.size == PAGE_SIZE)

        val allowed = when (category) {
            MapItemCategory.SITUATION -> setOf(MapItemAction.CONFIRM, MapItemAction.ADD_UPDATE, MapItemAction.UPDATE_STATUS)
            MapItemCategory.COORDINATION -> setOf(MapItemAction.ACKNOWLEDGE, MapItemAction.ADD_NOTE)
        }
        if (history.any { it.action !in allowed }) {
            return PersistenceParseResult.InvalidRecordShape("applied action family contradicts target category")
        }
        for (action in history) {
            val semanticallyValid = when (action.action) {
                MapItemAction.CONFIRM -> action.updateDisposition == null && action.targetLifecycle == null
                MapItemAction.ADD_UPDATE -> !action.note.isNullOrBlank() && action.updateDisposition != null &&
                    action.targetLifecycle == null
                MapItemAction.UPDATE_STATUS -> action.updateDisposition == null &&
                    action.targetLifecycle == MapSituationLifecycleStatus.NO_LONGER_ACTIVE
                MapItemAction.ACKNOWLEDGE -> action.informationSource == null && action.note == null &&
                    action.updateDisposition == null && action.targetLifecycle == null
                MapItemAction.ADD_NOTE -> !action.note.isNullOrBlank() && action.informationSource == null &&
                    action.updateDisposition == null && action.targetLifecycle == null
            }
            if (!semanticallyValid) return PersistenceParseResult.InvalidRecordShape("malformed applied ${action.action.wireValue} action")
        }
        val projection = when (category) {
            MapItemCategory.SITUATION -> {
                // The first resolution in total action order is irreversible. A normal action
                // delivered before it may be an immutable row, but cannot be projected if its
                // own order is after this cutoff. This makes rebuild independent of delivery.
                val resolutions = history.filter { it.action == MapItemAction.UPDATE_STATUS }
                val earliestResolution = resolutions.minByOrNull { it.order }
                val validNormalActions = history.filter { action ->
                    action.action == MapItemAction.CONFIRM || action.action == MapItemAction.ADD_UPDATE
                }.filter { action -> earliestResolution == null || action.order < earliestResolution.order }
                val confirmation = validNormalActions.filter { it.action == MapItemAction.CONFIRM }
                    .maxByOrNull { it.order }?.order
                val update = validNormalActions.filter { it.action == MapItemAction.ADD_UPDATE }
                    .maxByOrNull { it.order }?.order
                val lifecycle = resolutions.maxByOrNull { it.order }?.order
                val material = (resolutions + validNormalActions.filter {
                    it.action == MapItemAction.ADD_UPDATE &&
                        it.updateDisposition?.wireValue == MapItemPersistenceValues.UpdateDisposition.SITUATION_CHANGED
                }).maxByOrNull { it.order }?.order
                val confirmations = if (earliestResolution != null) 0 else validNormalActions
                    .asSequence()
                    .filter { it.action == MapItemAction.CONFIRM }
                    .filter { material == null || it.order > material }
                    .map { it.order.actorDid }
                    .distinct()
                    .count()
                MapItemActionProjection(
                    if (lifecycle == null) MapSituationLifecycleStatus.ACTIVE else MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                    confirmation, update, lifecycle, material, confirmations, 0,
                )
            }
            MapItemCategory.COORDINATION -> MapItemActionProjection(
                null, null, null, null, null, 0,
                actionDao.countAcknowledgementActors(gid, persistedType, itemId),
            )
        }
        return when (val validation = projection.validate(category)) {
            is PersistenceParseResult.Success -> PersistenceParseResult.Success(projection)
            else -> validation.failure()
        }
    }

    private companion object { const val PAGE_SIZE = 256 }
}
