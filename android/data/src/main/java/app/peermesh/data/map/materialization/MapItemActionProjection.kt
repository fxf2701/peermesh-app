package app.peermesh.data.map.materialization

import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.MapItemPersistenceMutationValidator
import app.peermesh.data.db.PersistenceParseResult

internal data class MapItemActionProjection(
    val lifecycle: MapSituationLifecycleStatus?,
    val latestConfirmationOrder: MapActionOrderKey?,
    val latestSituationUpdateOrder: MapActionOrderKey?,
    val latestLifecycleOrder: MapActionOrderKey?,
    val latestMaterialChangeOrder: MapActionOrderKey?,
    val confirmationActorCount: Int,
    val acknowledgementActorCount: Int,
) {
    fun validate(category: MapItemCategory): PersistenceParseResult<Unit> {
        listOf(latestConfirmationOrder, latestSituationUpdateOrder, latestLifecycleOrder, latestMaterialChangeOrder)
            .forEach { order ->
                val result = MapItemPersistenceMutationValidator.order(order)
                if (result !is PersistenceParseResult.Success) return result
            }
        val counts = MapItemPersistenceMutationValidator.counts(confirmationActorCount, acknowledgementActorCount)
        if (counts !is PersistenceParseResult.Success) return counts
        val correct = when (category) {
            MapItemCategory.SITUATION -> lifecycle != null && acknowledgementActorCount == 0
            MapItemCategory.COORDINATION -> lifecycle == null && latestConfirmationOrder == null &&
                latestSituationUpdateOrder == null && latestLifecycleOrder == null &&
                latestMaterialChangeOrder == null && confirmationActorCount == 0
        }
        return if (correct) PersistenceParseResult.Success(Unit)
        else PersistenceParseResult.InvalidRecordShape("action projection is not category-correct")
    }
}
