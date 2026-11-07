package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.MapItemPersistenceMutationValidator
import app.peermesh.data.db.MapItemPersistenceRecordValidator
import app.peermesh.data.db.MapItemPersistenceValues
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.PersistenceWriteResult
import app.peermesh.data.db.failure
import app.peermesh.data.db.entity.MapItemDomainStateEntity
import app.peermesh.data.map.materialization.MapItemActionProjection

@Dao
abstract class MapItemDomainStateDao {
    internal fun updateActionProjection(
        gid: String, itemType: MapItemType, itemId: String, projection: MapItemActionProjection,
    ): PersistenceWriteResult<Int> {
        val identity = MapItemPersistenceMutationValidator.targetIdentity(gid, itemType, itemId)
        if (identity !is PersistenceParseResult.Success) return PersistenceWriteResult.Rejected(identity.failure())
        listOf(projection.latestConfirmationOrder, projection.latestSituationUpdateOrder,
            projection.latestLifecycleOrder, projection.latestMaterialChangeOrder).forEach {
            val validation = MapItemPersistenceMutationValidator.order(it)
            if (validation !is PersistenceParseResult.Success) return PersistenceWriteResult.Rejected(validation.failure())
        }
        val counts = MapItemPersistenceMutationValidator.counts(projection.confirmationActorCount, projection.acknowledgementActorCount)
        if (counts !is PersistenceParseResult.Success) return PersistenceWriteResult.Rejected(counts.failure())
        return PersistenceWriteResult.Success(updateActionProjectionRaw(
            gid, persisted(itemType), itemId,
            projection.lifecycle?.let(MapItemPersistenceValues.SituationLifecycle::valueOf),
            projection.latestConfirmationOrder?.timestampSeconds, projection.latestConfirmationOrder?.lamport,
            projection.latestConfirmationOrder?.actorDid, projection.latestConfirmationOrder?.eventId,
            projection.latestSituationUpdateOrder?.timestampSeconds, projection.latestSituationUpdateOrder?.lamport,
            projection.latestSituationUpdateOrder?.actorDid, projection.latestSituationUpdateOrder?.eventId,
            projection.latestLifecycleOrder?.timestampSeconds, projection.latestLifecycleOrder?.lamport,
            projection.latestLifecycleOrder?.actorDid, projection.latestLifecycleOrder?.eventId,
            projection.latestMaterialChangeOrder?.timestampSeconds, projection.latestMaterialChangeOrder?.lamport,
            projection.latestMaterialChangeOrder?.actorDid, projection.latestMaterialChangeOrder?.eventId,
            projection.confirmationActorCount, projection.acknowledgementActorCount,
        ))
    }

    @Query("""UPDATE map_item_domain_state SET situation_lifecycle = :lifecycle,
        latest_confirmation_t = :confirmationT, latest_confirmation_lt = :confirmationLt,
        latest_confirmation_actor = :confirmationActor, latest_confirmation_event_id = :confirmationEvent,
        latest_update_t = :updateT, latest_update_lt = :updateLt,
        latest_update_actor = :updateActor, latest_update_event_id = :updateEvent,
        latest_lifecycle_t = :lifecycleT, latest_lifecycle_lt = :lifecycleLt,
        latest_lifecycle_actor = :lifecycleActor, latest_lifecycle_event_id = :lifecycleEvent,
        latest_material_t = :materialT, latest_material_lt = :materialLt,
        latest_material_actor = :materialActor, latest_material_event_id = :materialEvent,
        confirmation_actor_count = :confirmationCount, acknowledgement_actor_count = :acknowledgementCount
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId""")
    protected abstract fun updateActionProjectionRaw(
        gid: String, itemType: String, itemId: String, lifecycle: String?,
        confirmationT: Long?, confirmationLt: Long?, confirmationActor: String?, confirmationEvent: String?,
        updateT: Long?, updateLt: Long?, updateActor: String?, updateEvent: String?,
        lifecycleT: Long?, lifecycleLt: Long?, lifecycleActor: String?, lifecycleEvent: String?,
        materialT: Long?, materialLt: Long?, materialActor: String?, materialEvent: String?,
        confirmationCount: Int, acknowledgementCount: Int,
    ): Int
    @Query("SELECT * FROM map_item_domain_state WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId")
    abstract fun find(gid: String, itemType: String, itemId: String): MapItemDomainStateEntity?

    @Query("SELECT * FROM map_item_domain_state WHERE gid = :gid AND item_type = :itemType AND item_id IN (:itemIds)")
    abstract fun findByItemIds(gid: String, itemType: String, itemIds: Collection<String>): List<MapItemDomainStateEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertRaw(entity: MapItemDomainStateEntity): Long

    fun insertIgnore(entity: MapItemDomainStateEntity): PersistenceWriteResult<Long> =
        when (val validation = MapItemPersistenceRecordValidator.validate(entity)) {
            is PersistenceParseResult.Success -> PersistenceWriteResult.Success(insertRaw(entity))
            else -> PersistenceWriteResult.Rejected(validation.failure())
        }

    fun updateLifecycle(
        gid: String,
        itemType: MapItemType,
        itemId: String,
        lifecycle: MapSituationLifecycleStatus,
        order: MapActionOrderKey,
    ): PersistenceWriteResult<Int> = mutateTarget(gid, itemType, itemId, order) {
        updateLifecycleRaw(
            gid, persisted(itemType), itemId, MapItemPersistenceValues.SituationLifecycle.valueOf(lifecycle),
            order.timestampSeconds, order.lamport, order.actorDid, order.eventId,
        )
    }

    fun updateLatestConfirmation(
        gid: String, itemType: MapItemType, itemId: String, order: MapActionOrderKey?,
    ): PersistenceWriteResult<Int> = mutateTarget(gid, itemType, itemId, order) {
        updateLatestConfirmationRaw(gid, persisted(itemType), itemId, order?.timestampSeconds, order?.lamport, order?.actorDid, order?.eventId)
    }

    fun updateLatestSituationUpdate(
        gid: String, itemType: MapItemType, itemId: String, order: MapActionOrderKey?,
    ): PersistenceWriteResult<Int> = mutateTarget(gid, itemType, itemId, order) {
        updateLatestSituationUpdateRaw(gid, persisted(itemType), itemId, order?.timestampSeconds, order?.lamport, order?.actorDid, order?.eventId)
    }

    fun updateLatestMaterialChange(
        gid: String, itemType: MapItemType, itemId: String, order: MapActionOrderKey?,
    ): PersistenceWriteResult<Int> = mutateTarget(gid, itemType, itemId, order) {
        updateLatestMaterialChangeRaw(gid, persisted(itemType), itemId, order?.timestampSeconds, order?.lamport, order?.actorDid, order?.eventId)
    }

    fun updateAggregateCounts(
        gid: String, itemType: MapItemType, itemId: String, confirmationCount: Int, acknowledgementCount: Int,
    ): PersistenceWriteResult<Int> {
        val identity = MapItemPersistenceMutationValidator.targetIdentity(gid, itemType, itemId)
        if (identity !is PersistenceParseResult.Success) return PersistenceWriteResult.Rejected(identity.failure())
        val counts = MapItemPersistenceMutationValidator.counts(confirmationCount, acknowledgementCount)
        if (counts !is PersistenceParseResult.Success) return PersistenceWriteResult.Rejected(counts.failure())
        return PersistenceWriteResult.Success(
            updateAggregateCountsRaw(gid, persisted(itemType), itemId, confirmationCount, acknowledgementCount),
        )
    }

    private inline fun mutateTarget(
        gid: String, itemType: MapItemType, itemId: String, order: MapActionOrderKey?, query: () -> Int,
    ): PersistenceWriteResult<Int> {
        val identity = MapItemPersistenceMutationValidator.targetIdentity(gid, itemType, itemId)
        if (identity !is PersistenceParseResult.Success) return PersistenceWriteResult.Rejected(identity.failure())
        val orderValidation = MapItemPersistenceMutationValidator.order(order)
        if (orderValidation !is PersistenceParseResult.Success) return PersistenceWriteResult.Rejected(orderValidation.failure())
        return PersistenceWriteResult.Success(query())
    }

    private fun persisted(itemType: MapItemType) = MapItemPersistenceValues.ItemType.valueOf(itemType)

    @Query("""
        UPDATE map_item_domain_state SET situation_lifecycle = :lifecycle,
            latest_lifecycle_t = :timestamp, latest_lifecycle_lt = :lamport,
            latest_lifecycle_actor = :actorDid, latest_lifecycle_event_id = :eventId
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId
    """)
    protected abstract fun updateLifecycleRaw(gid: String, itemType: String, itemId: String, lifecycle: String?, timestamp: Long?, lamport: Long?, actorDid: String?, eventId: String?): Int

    @Query("""UPDATE map_item_domain_state SET latest_confirmation_t = :timestamp, latest_confirmation_lt = :lamport,
        latest_confirmation_actor = :actorDid, latest_confirmation_event_id = :eventId
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId""")
    protected abstract fun updateLatestConfirmationRaw(gid: String, itemType: String, itemId: String, timestamp: Long?, lamport: Long?, actorDid: String?, eventId: String?): Int

    @Query("""UPDATE map_item_domain_state SET latest_update_t = :timestamp, latest_update_lt = :lamport,
        latest_update_actor = :actorDid, latest_update_event_id = :eventId
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId""")
    protected abstract fun updateLatestSituationUpdateRaw(gid: String, itemType: String, itemId: String, timestamp: Long?, lamport: Long?, actorDid: String?, eventId: String?): Int

    @Query("""UPDATE map_item_domain_state SET latest_material_t = :timestamp, latest_material_lt = :lamport,
        latest_material_actor = :actorDid, latest_material_event_id = :eventId
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId""")
    protected abstract fun updateLatestMaterialChangeRaw(gid: String, itemType: String, itemId: String, timestamp: Long?, lamport: Long?, actorDid: String?, eventId: String?): Int

    @Query("""UPDATE map_item_domain_state SET confirmation_actor_count = :confirmationCount,
        acknowledgement_actor_count = :acknowledgementCount
        WHERE gid = :gid AND item_type = :itemType AND item_id = :itemId""")
    protected abstract fun updateAggregateCountsRaw(gid: String, itemType: String, itemId: String, confirmationCount: Int, acknowledgementCount: Int): Int
}
