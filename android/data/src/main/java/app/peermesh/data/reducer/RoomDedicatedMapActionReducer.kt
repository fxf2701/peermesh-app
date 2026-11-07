package app.peermesh.data.reducer

import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.PersistenceWriteResult
import app.peermesh.data.map.processing.DedicatedMapActionContext
import app.peermesh.data.db.dao.MapItemActionDao
import app.peermesh.data.map.processing.DedicatedMapActionPolicy
import app.peermesh.data.map.processing.MapEventFingerprint
import app.peermesh.data.map.processing.MapItemActionEntityFactory
import app.peermesh.data.map.processing.persistenceInput
import app.peermesh.data.map.processing.sameImmutableActionAs
import app.peermesh.data.db.dao.MapItemDomainStateDao
import app.peermesh.data.map.materialization.RoomMapItemActionProjectionDeriver

/**
 * Room-backed M2C2A reducer implementation anchor.
 *
 * RoomMapEventProcessor remains the transaction owner. This reducer must not open a transaction,
 * mutate canonical marker/area fields, or materialize lifecycle/pointers/aggregates in M2C2A.
 */
internal class RoomDedicatedMapActionReducer(
    private val actionDao: MapItemActionDao,
    private val policy: DedicatedMapActionPolicy,
    private val fingerprint: MapEventFingerprint,
    private val entityFactory: MapItemActionEntityFactory,
    private val projectionDeriver: RoomMapItemActionProjectionDeriver,
    private val domainStateDao: MapItemDomainStateDao,
) : DedicatedMapActionReducer {
    override fun apply(context: DedicatedMapActionContext): DedicatedMapActionReductionResult {
        val historical = when (val decision = policy.evaluate(context)) {
            is app.peermesh.data.map.processing.DedicatedMapActionPolicyDecision.Reject ->
                return DedicatedMapActionReductionResult.Rejected(decision.reason)
            app.peermesh.data.map.processing.DedicatedMapActionPolicyDecision.AcceptHistorical -> true
            app.peermesh.data.map.processing.DedicatedMapActionPolicyDecision.AcceptApplied -> false
        }
        val expected = when (val mapped = entityFactory.create(
            context.persistenceInput(),
            fingerprint.sha256(context.event),
            app.peermesh.data.map.processing.MapItemActionInitialState.APPLIED,
        )) {
            is PersistenceParseResult.Success -> mapped.value
            else -> return DedicatedMapActionReductionResult.InvalidPersistedState(mapped.toString())
        }
        return when (val inserted = actionDao.insertIgnore(expected)) {
            is PersistenceWriteResult.Rejected -> DedicatedMapActionReductionResult.InvalidPersistedState(inserted.failure.toString())
            is PersistenceWriteResult.Success -> if (inserted.value != -1L) {
                val category = context.snapshot.category
                    ?: return DedicatedMapActionReductionResult.InvalidPersistedState("missing_target_category")
                val projection = when (val derived = projectionDeriver.derive(
                    context.gid, context.interaction.itemType, context.snapshot.identity.itemId, category,
                )) {
                    is PersistenceParseResult.Success -> derived.value
                    else -> return DedicatedMapActionReductionResult.InvalidPersistedState(derived.toString())
                }
                when (val updated = domainStateDao.updateActionProjection(
                    context.gid, context.interaction.itemType, context.snapshot.identity.itemId, projection,
                )) {
                    is PersistenceWriteResult.Rejected -> return DedicatedMapActionReductionResult.InvalidPersistedState(updated.failure.toString())
                    is PersistenceWriteResult.Success -> if (updated.value != 1) {
                        return DedicatedMapActionReductionResult.InvalidPersistedState("action_projection_affected_${updated.value}_rows")
                    }
                }
                if (historical) DedicatedMapActionReductionResult.HistoricalAppliedInserted else DedicatedMapActionReductionResult.AppliedInserted
            } else classifyIgnored(context.gid, expected)
        }
    }

    private fun classifyIgnored(gid: String, expected: app.peermesh.data.db.entity.MapItemActionEntity): DedicatedMapActionReductionResult {
        val byAction = actionDao.findByActionId(gid, expected.action_id)
        val byEvent = actionDao.findByEventId(gid, expected.event_id)
        if (byAction == null || byEvent == null || byAction != byEvent) {
            return DedicatedMapActionReductionResult.InvalidPersistedState("action_event_indexes_inconsistent")
        }
        if (app.peermesh.data.db.MapItemPersistenceRecordValidator.validate(byAction) !is PersistenceParseResult.Success) {
            return DedicatedMapActionReductionResult.InvalidPersistedState("stored_action_contract_invalid")
        }
        if (byAction.event_fingerprint != expected.event_fingerprint) return DedicatedMapActionReductionResult.ConflictingDuplicate
        return if (byAction.sameImmutableActionAs(expected)) DedicatedMapActionReductionResult.ExactDuplicate
        else DedicatedMapActionReductionResult.InvalidPersistedState("immutable_action_row_contradiction")
    }
}
