package app.peermesh.data.map.processing

import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.MapItemMaterializationState
import app.peermesh.data.db.MapItemPersistenceRecordValidator
import app.peermesh.data.db.MapItemPersistenceValues
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.core.map.MapItemInteractionEvent

/** Maps an accepted decoded dedicated action to immutable persistence content. */
internal enum class MapItemActionInitialState(
    val persistenceState: MapItemMaterializationState,
) {
    APPLIED(MapItemMaterializationState.APPLIED),
    PENDING_TARGET(MapItemMaterializationState.PENDING_TARGET),
}

internal fun interface MapItemActionEntityFactory {
    fun create(
        input: MapItemActionPersistenceInput,
        fingerprint: String,
        initialState: MapItemActionInitialState,
    ): PersistenceParseResult<MapItemActionEntity>
}

/** Target-independent immutable input shared by applied and pending action persistence. */
internal data class MapItemActionPersistenceInput(
    val gid: String,
    val event: app.peermesh.core.model.EventCore,
    val interaction: MapItemInteractionEvent,
)

internal fun DedicatedMapActionContext.persistenceInput() =
    MapItemActionPersistenceInput(gid, event, interaction)

/** M2C2A implementation anchor; mapping must use explicit stable persisted values. */
internal object DefaultMapItemActionEntityFactory : MapItemActionEntityFactory {
    override fun create(
        input: MapItemActionPersistenceInput,
        fingerprint: String,
        initialState: MapItemActionInitialState,
    ): PersistenceParseResult<MapItemActionEntity> {
        val interaction = input.interaction
        val metadata = when (interaction) {
            is MapItemInteractionEvent.Confirmation -> interaction.action.metadata
            is MapItemInteractionEvent.SituationUpdate -> interaction.action.metadata
            is MapItemInteractionEvent.SituationStatus -> interaction.action.metadata
            is MapItemInteractionEvent.Acknowledgement -> interaction.action.metadata
            is MapItemInteractionEvent.CoordinationNote -> interaction.action.metadata
        }
        val source = when (interaction) {
            is MapItemInteractionEvent.Confirmation -> interaction.action.source
            is MapItemInteractionEvent.SituationUpdate -> interaction.action.source
            is MapItemInteractionEvent.SituationStatus -> interaction.action.source
            else -> null
        }
        val note = when (interaction) {
            is MapItemInteractionEvent.Confirmation -> interaction.action.note
            is MapItemInteractionEvent.SituationUpdate -> interaction.action.content
            is MapItemInteractionEvent.SituationStatus -> interaction.action.note
            is MapItemInteractionEvent.CoordinationNote -> interaction.action.content
            else -> null
        }
        val entity = MapItemActionEntity(
            gid = input.gid,
            action_id = metadata.actionId,
            event_id = input.event.id,
            event_type = input.event.type,
            map_domain_v = 1,
            item_type = MapItemPersistenceValues.ItemType.valueOf(interaction.itemType),
            item_id = metadata.itemId,
            action_type = MapItemPersistenceValues.ActionType.valueOf(interaction.actionType()),
            actor_did = metadata.actorDid,
            action_t = metadata.timestampSeconds,
            action_lt = metadata.eventLamport,
            information_source = source?.let(MapItemPersistenceValues.InformationSource::valueOf),
            action_note = note,
            update_disposition = (interaction as? MapItemInteractionEvent.SituationUpdate)?.action?.updateType
                ?.let(MapItemPersistenceValues.UpdateDisposition::valueOf),
            target_lifecycle = (interaction as? MapItemInteractionEvent.SituationStatus)?.action?.status
                ?.let(MapItemPersistenceValues.SituationLifecycle::valueOf),
            event_fingerprint = fingerprint,
            materialization_state = initialState.persistenceState.persistedValue,
            rejection_reason = null,
        )
        return when (val validation = MapItemPersistenceRecordValidator.validate(entity)) {
            is PersistenceParseResult.Success -> PersistenceParseResult.Success(entity)
            is PersistenceParseResult.MissingRequiredValue -> validation
            is PersistenceParseResult.UnknownPersistedValue -> validation
            is PersistenceParseResult.InvalidRecordShape -> validation
        }
    }
}
