package app.peermesh.data.map.replay

import app.peermesh.data.db.dao.MapItemActionDao
import app.peermesh.data.db.dao.MapItemDomainStateDao
import app.peermesh.data.db.dao.MapDao
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.MapItemPersistenceValues
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.PersistenceWriteResult
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.map.MapItemEntityMappers
import app.peermesh.core.map.MapItemAction
import app.peermesh.data.map.processing.DedicatedMapActionTargetSnapshotValidator
import app.peermesh.data.map.processing.DedicatedMapActionPersistedPairValidator
import app.peermesh.data.map.processing.PersistedActionPairValidationResult

/**
 * M2C3B implementation anchor. RoomMapEventProcessor remains the sole transaction owner.
 *
 * Codex implementation responsibilities are intentionally constrained to:
 * 1. load and validate one exact target snapshot;
 * 2. load exact-target pending rows in DAO-provided ascending total order;
 * 3. classify each normalized immutable action through [semanticEvaluator];
 * 4. own guarded pending -> applied/rejected transition results;
 * 5. reload zero-row transition losers and classify terminal rows as already completed;
 * 6. refresh projection once iff at least one transition was won;
 * 7. fail transactionally on persistence invariant contradictions.
 */
internal class RoomTargetPendingReplayCoordinator(
    private val actionDao: MapItemActionDao,
    private val eventsDao: EventsDao,
    private val domainStateDao: MapItemDomainStateDao,
    private val mapDao: MapDao,
    private val semanticEvaluator: PendingActionSemanticEvaluator,
    private val projectionRefresher: TargetProjectionRefresher,
) : TargetPendingReplayCoordinator {

    override fun replay(target: PendingReplayTarget): TargetPendingReplayResult = replay(target, refreshProjection = true)

    /** Used only by the live-action path, whose reducer performs one final full-history refresh. */
    fun replayBeforeLiveAction(target: PendingReplayTarget): TargetPendingReplayResult =
        replay(target, refreshProjection = false)

    private fun replay(target: PendingReplayTarget, refreshProjection: Boolean): TargetPendingReplayResult {
        require(target.gid.isNotBlank()) { "gid must be nonblank" }
        require(target.itemId.isNotBlank()) { "itemId must be nonblank" }

        val persistedType = MapItemPersistenceValues.ItemType.valueOf(target.itemType)
        val marker = mapDao.findMarker(target.gid, target.itemId)
        val area = mapDao.findArea(target.gid, target.itemId)
        val canonicalConsistent = when (target.itemType) {
            app.peermesh.core.map.MapItemType.MARKER -> marker != null && area == null
            app.peermesh.core.map.MapItemType.AREA -> area != null && marker == null
        }
        if (!canonicalConsistent) error("canonical_target_inconsistent")
        val canonicalOwner = marker?.actor_did ?: area!!.actor_did
        val canonicalKind = marker?.kind ?: area?.kind
        val canonicalExpires = marker?.expires_at ?: area?.expires_at
        val canonicalDeleted = marker?.status == "deleted" || area?.status == "deleted"
        val state = domainStateDao.find(target.gid, persistedType, target.itemId)
            ?: error("missing_domain_state")
        val snapshot = when (val mapped = MapItemEntityMappers.snapshot(state)) {
            is PersistenceParseResult.Success -> mapped.value
            else -> error("invalid_domain_state: $mapped")
        }
        if (!DedicatedMapActionTargetSnapshotValidator.isConsistent(
                target.gid, target.itemType, target.itemId, canonicalOwner,
                canonicalKind, canonicalExpires, snapshot,
            )) error("canonical_domain_state_mismatch")

        val pending = actionDao.findPendingTarget(target.gid, persistedType, target.itemId)
        var earliestResolution = actionDao.findEarliestAppliedOfType(
            target.gid, persistedType, target.itemId, MapItemPersistenceValues.ActionType.UPDATE_STATUS,
        )?.let(::order)
        var applied = 0
        var rejected = 0
        var completed = 0
        for (row in pending) {
            when (val pair = DedicatedMapActionPersistedPairValidator.validate(
                target.gid, row, actionDao.findByActionId(target.gid, row.action_id),
                actionDao.findByEventId(target.gid, row.event_id), eventsDao.findById(target.gid, row.event_id),
            )) {
                PersistedActionPairValidationResult.Valid -> Unit
                is PersistedActionPairValidationResult.InvalidPersistedState -> error(pair.detail)
            }
            val action = when (val mapped = MapItemEntityMappers.action(row)) {
                is PersistenceParseResult.Success -> mapped.value
                else -> error("invalid_pending_action: $mapped")
            }
            val decision = semanticEvaluator.evaluate(PendingActionSemanticInput(
                action, snapshot, earliestResolution, canonicalDeleted,
            ))
            val write = when (decision) {
                PendingActionSemanticDecision.Apply -> actionDao.transitionPendingToApplied(target.gid, row.action_id)
                is PendingActionSemanticDecision.Reject -> actionDao.transitionPendingToRejected(
                    target.gid, row.action_id, decision.reason.persistedValue,
                )
            }
            val affected = when (write) {
                is PersistenceWriteResult.Success -> write.value
                is PersistenceWriteResult.Rejected -> error("invalid_pending_transition: ${write.failure}")
            }
            if (affected == 1) {
                when (decision) {
                    PendingActionSemanticDecision.Apply -> {
                        applied++
                        if (action.action == MapItemAction.UPDATE_STATUS &&
                            (earliestResolution == null || action.order < earliestResolution)
                        ) earliestResolution = action.order
                    }
                    is PendingActionSemanticDecision.Reject -> rejected++
                }
            } else {
                val current = actionDao.findByActionId(target.gid, row.action_id)
                    ?: error("pending_transition_row_missing")
                when (current.materialization_state) {
                    MapItemPersistenceValues.MaterializationState.APPLIED,
                    MapItemPersistenceValues.MaterializationState.REJECTED -> completed++
                    MapItemPersistenceValues.MaterializationState.PENDING_TARGET -> error("pending_transition_lost_while_still_pending")
                    else -> error("unsupported_materialization_state")
                }
            }
        }
        val refresh = if (applied + rejected == 0) ProjectionRefreshOutcome.NotRequired
        else if (!refreshProjection) ProjectionRefreshOutcome.DeferredToCaller else {
            when (val result = projectionRefresher.refresh(snapshot)) {
                ProjectionRefreshResult.Refreshed -> ProjectionRefreshOutcome.Refreshed
                is ProjectionRefreshResult.InvalidPersistedState -> error(result.detail)
            }
        }
        return TargetPendingReplayResult(pending.size, applied, rejected, completed, refresh)
    }

    private fun order(row: app.peermesh.data.db.entity.MapItemActionEntity) =
        MapActionOrderKey(row.action_t, row.action_lt, row.actor_did, row.event_id)
}
