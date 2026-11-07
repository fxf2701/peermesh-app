package app.peermesh.data.db

import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.data.db.entity.MapItemDomainStateEntity

sealed interface PersistenceWriteResult<out T> {
    data class Success<T>(val value: T) : PersistenceWriteResult<T>
    data class Rejected(val failure: PersistenceParseResult<Nothing>) : PersistenceWriteResult<Nothing>
}

data class MapActionOrderKey(
    val timestampSeconds: Long,
    val lamport: Long,
    val actorDid: String,
    val eventId: String,
) : Comparable<MapActionOrderKey> {
    override fun compareTo(other: MapActionOrderKey): Int = ASCENDING.compare(this, other)

    companion object {
        val ASCENDING: Comparator<MapActionOrderKey> = compareBy<MapActionOrderKey> { it.timestampSeconds }
            .thenBy { it.lamport }.thenBy { it.actorDid }.thenBy { it.eventId }
        val DESCENDING: Comparator<MapActionOrderKey> = Comparator { left, right -> ASCENDING.compare(right, left) }
    }
}

object MapItemPersistenceRecordValidator {
    fun validate(entity: MapItemActionEntity): PersistenceParseResult<Unit> {
        required(entity.gid, "gid")?.let { return it }
        required(entity.item_id, "item_id")?.let { return it }
        required(entity.action_id, "action_id")?.let { return it }
        required(entity.event_id, "event_id")?.let { return it }
        required(entity.actor_did, "actor_did")?.let { return it }
        required(entity.event_type, "event_type")?.let { return it }
        required(entity.event_fingerprint, "event_fingerprint")?.let { return it }
        if (entity.action_id != entity.event_id) return invalid("action_id must equal event_id")
        if (entity.action_t <= 0L) return invalid("action_t must be positive")
        if (entity.action_lt < 0L) return invalid("action_lt must not be negative")
        if (entity.map_domain_v != 1) return invalid("map_domain_v must be 1")
        firstFailure(
            MapItemPersistenceValues.ItemType.parse(entity.item_type),
            MapItemPersistenceValues.ActionType.parse(entity.action_type),
            MapItemPersistenceValues.InformationSource.parseNullable(entity.information_source),
            MapItemPersistenceValues.UpdateDisposition.parseNullable(entity.update_disposition),
            MapItemPersistenceValues.SituationLifecycle.parseNullable(entity.target_lifecycle),
            MapItemPersistenceValues.MaterializationState.parse(entity.materialization_state),
        )?.let { return it }
        val rejected = entity.materialization_state == MapItemPersistenceValues.MaterializationState.REJECTED
        if (rejected != (entity.rejection_reason != null)) {
            return invalid("rejection_reason must be nonblank only for rejected actions")
        }
        if (rejected) {
            when (val reason = MapItemRejectionReasonContract.validate(entity.rejection_reason)) {
                is PersistenceParseResult.Success -> Unit
                else -> return reason.failure()
            }
        }
        return PersistenceParseResult.Success(Unit)
    }

    fun validate(entity: MapItemDomainStateEntity): PersistenceParseResult<Unit> {
        required(entity.gid, "gid")?.let { return it }
        required(entity.item_id, "item_id")?.let { return it }
        required(entity.owner_did, "owner_did")?.let { return it }
        firstFailure(
            MapItemPersistenceValues.ItemType.parse(entity.item_type),
            MapItemPersistenceValues.Category.parseNullable(entity.category),
            MapItemPersistenceValues.CategoryOrigin.parse(entity.category_origin),
            MapItemPersistenceValues.InformationSource.parseNullable(entity.creator_information_source),
            MapItemPersistenceValues.StateOrigin.parse(entity.state_origin),
            MapItemPersistenceValues.SituationLifecycle.parseNullable(entity.situation_lifecycle),
            MapItemPersistenceValues.TemporalOrigin.parse(entity.temporal_origin),
        )?.let { return it }
        if (entity.category_origin == MapItemPersistenceValues.CategoryOrigin.UNRESOLVED && entity.category != null) {
            return invalid("unresolved category origin requires null category")
        }
        if (entity.category_origin != MapItemPersistenceValues.CategoryOrigin.UNRESOLVED && entity.category == null) {
            return invalid("resolved category origin requires category")
        }
        if (entity.domain_version != null && entity.domain_version != 1) return invalid("domain_version must be 1 when present")
        if (entity.state_origin == MapItemPersistenceValues.StateOrigin.EXPLICIT_DOMAIN_V1 && entity.domain_version != 1) {
            return invalid("explicit domain state requires domain_version 1")
        }
        if (entity.state_origin == MapItemPersistenceValues.StateOrigin.EXPLICIT_DOMAIN_V1 &&
            (entity.category_origin != MapItemPersistenceValues.CategoryOrigin.EXPLICIT_DOMAIN_V1 || entity.category == null)
        ) {
            return invalid("explicit domain state requires explicit resolved category")
        }
        if (entity.confirmation_actor_count < 0 || entity.acknowledgement_actor_count < 0) {
            return invalid("aggregate counts must not be negative")
        }
        listOf(entity.expires_at, entity.owner_edit_until, entity.interaction_until)
            .filterNotNull().firstOrNull { it <= 0L }?.let { return invalid("finite timestamps must be positive") }
        if (entity.interaction_until != null && entity.expires_at == null) {
            return invalid("finite interaction cutoff requires finite expiry")
        }
        if (entity.interaction_until != null && entity.interaction_until < entity.expires_at!!) {
            return invalid("interaction cutoff must not be earlier than expiry")
        }
        validateOrder("canonical_add", entity.canonical_add_t, entity.canonical_add_lt, entity.canonical_add_actor, entity.canonical_add_event_id)?.let { return it }
        validateOrder("latest_confirmation", entity.latest_confirmation_t, entity.latest_confirmation_lt, entity.latest_confirmation_actor, entity.latest_confirmation_event_id)?.let { return it }
        validateOrder("latest_update", entity.latest_update_t, entity.latest_update_lt, entity.latest_update_actor, entity.latest_update_event_id)?.let { return it }
        validateOrder("latest_lifecycle", entity.latest_lifecycle_t, entity.latest_lifecycle_lt, entity.latest_lifecycle_actor, entity.latest_lifecycle_event_id)?.let { return it }
        validateOrder("latest_material", entity.latest_material_t, entity.latest_material_lt, entity.latest_material_actor, entity.latest_material_event_id)?.let { return it }
        return PersistenceParseResult.Success(Unit)
    }

    private fun validateOrder(name: String, t: Long?, lt: Long?, actor: String?, event: String?): PersistenceParseResult.InvalidRecordShape? {
        val values = listOf(t, lt, actor, event)
        if (values.all { it == null }) return null
        if (values.any { it == null }) return invalid("$name order must be complete or null")
        if (t!! <= 0L || lt!! < 0L || actor!!.isBlank() || event!!.isBlank()) return invalid("$name order contains invalid values")
        return null
    }

    private fun required(value: String, field: String): PersistenceParseResult.InvalidRecordShape? =
        if (value.isBlank()) invalid("$field must be nonblank") else null

    private fun firstFailure(vararg results: PersistenceParseResult<*>): PersistenceParseResult<Nothing>? {
        return when (val failure = results.firstOrNull { it !is PersistenceParseResult.Success } ?: return null) {
            is PersistenceParseResult.MissingRequiredValue -> failure
            is PersistenceParseResult.UnknownPersistedValue -> failure
            is PersistenceParseResult.InvalidRecordShape -> failure
            is PersistenceParseResult.Success -> null
        }
    }

    private fun invalid(reason: String) = PersistenceParseResult.InvalidRecordShape(reason)
}

object MapItemRejectionReasonContract {
    private val machineReadable = Regex("[a-z0-9_]+")

    fun validate(value: String?): PersistenceParseResult<String> = when {
        value.isNullOrBlank() -> PersistenceParseResult.InvalidRecordShape("rejection_reason must be nonblank")
        !machineReadable.matches(value) -> PersistenceParseResult.InvalidRecordShape("rejection_reason must be machine-readable")
        else -> PersistenceParseResult.Success(value)
    }
}

object MapItemPersistenceMutationValidator {
    fun targetIdentity(gid: String, itemType: MapItemType, itemId: String): PersistenceParseResult<Unit> {
        if (gid.isBlank()) return PersistenceParseResult.InvalidRecordShape("gid must be nonblank")
        if (itemId.isBlank()) return PersistenceParseResult.InvalidRecordShape("item_id must be nonblank")
        MapItemPersistenceValues.ItemType.valueOf(itemType)
        return PersistenceParseResult.Success(Unit)
    }

    fun actionIdentity(gid: String, actionId: String): PersistenceParseResult<Unit> {
        if (gid.isBlank()) return PersistenceParseResult.InvalidRecordShape("gid must be nonblank")
        if (actionId.isBlank()) return PersistenceParseResult.InvalidRecordShape("action_id must be nonblank")
        return PersistenceParseResult.Success(Unit)
    }

    fun order(order: MapActionOrderKey?): PersistenceParseResult<Unit> = when {
        order == null -> PersistenceParseResult.Success(Unit)
        order.timestampSeconds <= 0L -> PersistenceParseResult.InvalidRecordShape("order timestamp must be positive")
        order.lamport < 0L -> PersistenceParseResult.InvalidRecordShape("order lamport must not be negative")
        order.actorDid.isBlank() -> PersistenceParseResult.InvalidRecordShape("order actor DID must be nonblank")
        order.eventId.isBlank() -> PersistenceParseResult.InvalidRecordShape("order event ID must be nonblank")
        else -> PersistenceParseResult.Success(Unit)
    }

    fun counts(confirmationCount: Int, acknowledgementCount: Int): PersistenceParseResult<Unit> =
        if (confirmationCount < 0 || acknowledgementCount < 0) {
            PersistenceParseResult.InvalidRecordShape("aggregate counts must not be negative")
        } else {
            PersistenceParseResult.Success(Unit)
        }
}

fun PersistenceParseResult<*>.failure(): PersistenceParseResult<Nothing> = when (this) {
    is PersistenceParseResult.MissingRequiredValue -> this
    is PersistenceParseResult.UnknownPersistedValue -> this
    is PersistenceParseResult.InvalidRecordShape -> this
    is PersistenceParseResult.Success -> error("success has no failure")
}
