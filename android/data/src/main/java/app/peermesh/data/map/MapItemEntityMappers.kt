package app.peermesh.data.map

import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.MapItemPersistenceRecordValidator
import app.peermesh.data.db.MapItemPersistenceValues
import app.peermesh.data.db.MapItemStateOrigin
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.data.db.entity.MapItemDomainStateEntity
import app.peermesh.data.db.failure

internal object MapItemEntityMappers {
    fun domain(entity: MapItemDomainStateEntity): PersistenceParseResult<MapItemDomainState> = validated(entity) {
        val category = value(MapItemPersistenceValues.Category.parseNullable(entity.category))
        val lifecycle = value(MapItemPersistenceValues.SituationLifecycle.parseNullable(entity.situation_lifecycle))
        val stateOrigin = value(MapItemPersistenceValues.StateOrigin.parse(entity.state_origin))
        val canonicalAddOrder = order(entity.canonical_add_t, entity.canonical_add_lt,
            entity.canonical_add_actor, entity.canonical_add_event_id)
        val confirmationOrder = order(entity.latest_confirmation_t, entity.latest_confirmation_lt,
            entity.latest_confirmation_actor, entity.latest_confirmation_event_id)
        val updateOrder = order(entity.latest_update_t, entity.latest_update_lt,
            entity.latest_update_actor, entity.latest_update_event_id)
        val lifecycleOrder = order(entity.latest_lifecycle_t, entity.latest_lifecycle_lt,
            entity.latest_lifecycle_actor, entity.latest_lifecycle_event_id)
        val materialOrder = order(entity.latest_material_t, entity.latest_material_lt,
            entity.latest_material_actor, entity.latest_material_event_id)
        MapItemDomainState(entity.gid, identity(entity), category,
            value(MapItemPersistenceValues.CategoryOrigin.parse(entity.category_origin)),
            value(MapItemPersistenceValues.InformationSource.parseNullable(entity.creator_information_source)),
            lifecycle, windows(entity),
            value(MapItemPersistenceValues.TemporalOrigin.parse(entity.temporal_origin)), entity.confirmation_actor_count,
            entity.acknowledgement_actor_count,
            confirmationFreshness(category, stateOrigin, lifecycle, canonicalAddOrder, confirmationOrder, updateOrder,
                lifecycleOrder, materialOrder, entity.confirmation_actor_count))
    }

    fun snapshot(entity: MapItemDomainStateEntity): PersistenceParseResult<MapItemMaterializationSnapshot> = validated(entity) {
        MapItemMaterializationSnapshot(entity.gid, identity(entity), value(MapItemPersistenceValues.Category.parseNullable(entity.category)),
            value(MapItemPersistenceValues.CategoryOrigin.parse(entity.category_origin)),
            value(MapItemPersistenceValues.InformationSource.parseNullable(entity.creator_information_source)), entity.owner_did,
            entity.domain_version, value(MapItemPersistenceValues.StateOrigin.parse(entity.state_origin)),
            value(MapItemPersistenceValues.SituationLifecycle.parseNullable(entity.situation_lifecycle)), windows(entity),
            value(MapItemPersistenceValues.TemporalOrigin.parse(entity.temporal_origin)),
            order(entity.canonical_add_t, entity.canonical_add_lt, entity.canonical_add_actor, entity.canonical_add_event_id),
            order(entity.latest_confirmation_t, entity.latest_confirmation_lt, entity.latest_confirmation_actor, entity.latest_confirmation_event_id),
            order(entity.latest_update_t, entity.latest_update_lt, entity.latest_update_actor, entity.latest_update_event_id),
            order(entity.latest_lifecycle_t, entity.latest_lifecycle_lt, entity.latest_lifecycle_actor, entity.latest_lifecycle_event_id),
            order(entity.latest_material_t, entity.latest_material_lt, entity.latest_material_actor, entity.latest_material_event_id),
            entity.confirmation_actor_count, entity.acknowledgement_actor_count)
    }

    fun action(entity: MapItemActionEntity): PersistenceParseResult<MapItemActionRead> = when (val check = MapItemPersistenceRecordValidator.validate(entity)) {
        is PersistenceParseResult.Success -> PersistenceParseResult.Success(MapItemActionRead(entity.gid,
            MapItemIdentity(value(MapItemPersistenceValues.ItemType.parse(entity.item_type)), entity.item_id),
            value(MapItemPersistenceValues.ActionType.parse(entity.action_type)),
            MapActionOrderKey(entity.action_t, entity.action_lt, entity.actor_did, entity.event_id),
            value(MapItemPersistenceValues.InformationSource.parseNullable(entity.information_source)), entity.action_note,
            value(MapItemPersistenceValues.UpdateDisposition.parseNullable(entity.update_disposition)),
            value(MapItemPersistenceValues.SituationLifecycle.parseNullable(entity.target_lifecycle))))
        else -> check.failure()
    }

    private inline fun <T> validated(entity: MapItemDomainStateEntity, build: () -> T): PersistenceParseResult<T> =
        when (val check = MapItemPersistenceRecordValidator.validate(entity)) {
            is PersistenceParseResult.Success -> PersistenceParseResult.Success(build())
            else -> check.failure()
        }
    private fun identity(e: MapItemDomainStateEntity) = MapItemIdentity(value(MapItemPersistenceValues.ItemType.parse(e.item_type)), e.item_id)
    private fun windows(e: MapItemDomainStateEntity) = MapItemWindows(e.expires_at, e.owner_edit_until, e.interaction_until)
    private fun order(t: Long?, lt: Long?, actor: String?, event: String?) = if (t == null) null else MapActionOrderKey(t, lt!!, actor!!, event!!)

    private fun confirmationFreshness(
        category: MapItemCategory?,
        stateOrigin: MapItemStateOrigin,
        lifecycle: MapSituationLifecycleStatus?,
        canonicalAdd: MapActionOrderKey?,
        confirmation: MapActionOrderKey?,
        update: MapActionOrderKey?,
        lifecycleOrder: MapActionOrderKey?,
        material: MapActionOrderKey?,
        confirmationActorCount: Int,
    ): MapSituationConfirmationFreshnessBasis {
        if (category != MapItemCategory.SITUATION || lifecycle == null) return unknown()
        if (canonicalAdd == null) return unknown()
        if (listOf(confirmation, update, lifecycleOrder, material).any { it != null && it <= canonicalAdd }) {
            return unknown()
        }
        if (confirmationActorCount > 0 && confirmation == null) return unknown()
        if (confirmation != null && (confirmation == update || confirmation == lifecycleOrder)) return unknown()
        if (lifecycleOrder != null && lifecycleOrder == update) return unknown()

        return when (lifecycle) {
            MapSituationLifecycleStatus.ACTIVE -> activeConfirmationFreshness(
                stateOrigin, confirmation, update, lifecycleOrder, material, confirmationActorCount,
            )
            MapSituationLifecycleStatus.NO_LONGER_ACTIVE -> resolvedConfirmationFreshness(
                stateOrigin, confirmation, update, lifecycleOrder, material, confirmationActorCount,
            )
        }
    }

    private fun activeConfirmationFreshness(
        stateOrigin: MapItemStateOrigin,
        confirmation: MapActionOrderKey?,
        update: MapActionOrderKey?,
        lifecycleOrder: MapActionOrderKey?,
        material: MapActionOrderKey?,
        confirmationActorCount: Int,
    ): MapSituationConfirmationFreshnessBasis {
        if (lifecycleOrder != null) return unknown()
        if (material != null && (update == null || material > update)) return unknown()
        if (confirmation == null) {
            if (confirmationActorCount != 0) return unknown()
            return noPriorConfirmation(stateOrigin)
        }

        if (material == null || confirmation > material) {
            if (confirmationActorCount <= 0) return unknown()
            return MapSituationConfirmationFreshnessBasis.Current(confirmation.timestampSeconds)
        }
        if (confirmation == material || confirmationActorCount != 0) return unknown()
        return MapSituationConfirmationFreshnessBasis.InvalidatedBySituationChange
    }

    private fun resolvedConfirmationFreshness(
        stateOrigin: MapItemStateOrigin,
        confirmation: MapActionOrderKey?,
        update: MapActionOrderKey?,
        lifecycleOrder: MapActionOrderKey?,
        material: MapActionOrderKey?,
        confirmationActorCount: Int,
    ): MapSituationConfirmationFreshnessBasis {
        val resolution = lifecycleOrder ?: return unknown()
        if (material != resolution || confirmationActorCount != 0) return unknown()
        if (update != null && update >= resolution) return unknown()
        if (confirmation == null) return noPriorConfirmation(stateOrigin)
        return if (confirmation < resolution) {
            MapSituationConfirmationFreshnessBasis.InvalidatedByResolution
        } else {
            unknown()
        }
    }

    private fun noPriorConfirmation(stateOrigin: MapItemStateOrigin) =
        if (stateOrigin == MapItemStateOrigin.EXPLICIT_DOMAIN_V1) {
            MapSituationConfirmationFreshnessBasis.NoAcceptedPriorConfirmationEstablished
        } else {
            unknown()
        }

    private fun unknown() = MapSituationConfirmationFreshnessBasis.Unknown
    private fun <T> value(result: PersistenceParseResult<T>): T = (result as PersistenceParseResult.Success).value
}
