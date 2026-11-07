package app.peermesh.data.map.processing

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.map.MapItemEventCodec
import app.peermesh.core.model.EventCore
import app.peermesh.data.db.MapItemPersistenceRecordValidator
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.db.entity.MapItemActionEntity
import kotlinx.serialization.decodeFromString

/** Validates that immutable dedicated-action history is backed by its exact durable wire event. */
internal object DedicatedMapActionPersistedPairValidator {
    fun validate(
        gid: String,
        action: MapItemActionEntity,
        byActionId: MapItemActionEntity?,
        byEventId: MapItemActionEntity?,
        durableEvent: EventEntity?,
    ): PersistedActionPairValidationResult {
        if (byActionId == null || byEventId == null || byActionId != action || byEventId != action) {
            return invalid("action_event_indexes_inconsistent")
        }
        if (MapItemPersistenceRecordValidator.validate(action) !is PersistenceParseResult.Success) {
            return invalid("stored_action_contract_invalid")
        }
        val event = durableEvent ?: return invalid("durable_event_missing")
        if (action.gid != gid || event.gid != gid || action.action_id != action.event_id ||
            event.event_id != action.event_id || event.type != action.event_type ||
            event.actor_did != action.actor_did || event.created_t != action.action_t ||
            event.time_lt != action.action_lt || event.ref_target != action.item_id
        ) return invalid("durable_event_action_identity_contradiction")
        val core = try {
            CanonicalJson.json.decodeFromString(EventCore.serializer(), event.payload_json)
        } catch (_: Exception) {
            return invalid("durable_event_payload_invalid")
        }
        if (core.id != event.event_id || core.type != event.type || core.actor.did != event.actor_did ||
            core.time.t != event.created_t || (core.time.lt ?: 0L) != event.time_lt ||
            core.ref.target != event.ref_target
        ) return invalid("durable_event_row_payload_contradiction")
        val interaction = MapItemEventCodec.decode(core) ?: return invalid("durable_event_not_valid_dedicated_action")
        val expected = when (val result = DefaultMapItemActionEntityFactory.create(
            MapItemActionPersistenceInput(gid, core, interaction), Sha256MapEventFingerprint.sha256(core),
            MapItemActionInitialState.PENDING_TARGET,
        )) {
            is PersistenceParseResult.Success -> result.value
            else -> return invalid("durable_event_action_mapping_invalid")
        }
        if (action.event_fingerprint != expected.event_fingerprint) return invalid("durable_event_fingerprint_mismatch")
        return if (action.sameImmutableActionAs(expected)) PersistedActionPairValidationResult.Valid
        else invalid("durable_event_action_semantic_contradiction")
    }

    private fun invalid(detail: String) = PersistedActionPairValidationResult.InvalidPersistedState(detail)
}

internal sealed interface PersistedActionPairValidationResult {
    data object Valid : PersistedActionPairValidationResult
    data class InvalidPersistedState(val detail: String) : PersistedActionPairValidationResult
}
