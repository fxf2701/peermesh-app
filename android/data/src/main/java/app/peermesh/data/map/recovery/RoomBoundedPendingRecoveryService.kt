package app.peermesh.data.map.recovery

import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.MapItemPersistenceMutationValidator
import app.peermesh.data.db.MapItemPersistenceValues
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.core.map.MapItemType
import app.peermesh.data.map.replay.PendingReplayTarget
import app.peermesh.data.map.replay.TargetPendingReplayCoordinator
import java.util.concurrent.CancellationException

/**
 * M2C3C implementation skeleton.
 *
 * Locked orchestration boundary:
 * 1. validate one group-scoped request before querying;
 * 2. read at most [BoundedPendingRecoveryRequest.limit] pending rows through the accepted DAO
 *    keyset scan in complete action-order ascending order;
 * 3. use the final scanned row, not the final recovered target, as the continuation cursor;
 * 4. deduplicate exact (item_type, item_id) targets within the page;
 * 5. process each target in its own [AppDatabase.runInTransaction] boundary;
 * 6. recheck canonical target and domain-state existence inside that target transaction;
 * 7. leave a still-missing target pending and count it as skipped;
 * 8. delegate present targets to [replayCoordinator] without duplicating replay semantics;
 * 9. isolate target-scoped failures and continue with sibling targets.
 *
 * This class must not add nested transactions inside the replay coordinator, mutate pending rows
 * directly, refresh projections directly, prune orphan rows, or scan across groups.
 */
internal class RoomBoundedPendingRecoveryService(
    private val db: AppDatabase,
    private val replayCoordinator: TargetPendingReplayCoordinator,
    private val targetTransaction: ((() -> PendingRecoveryTargetOutcome) -> PendingRecoveryTargetOutcome)? = null,
) : BoundedPendingRecoveryService {

    override fun recover(request: BoundedPendingRecoveryRequest): BoundedPendingRecoveryResult {
        validate(request)?.let { return BoundedPendingRecoveryResult.InvalidRequest(it) }

        val after = request.after?.order
        val rows = db.mapItemActionDao().scanPending(
            request.gid,
            after?.timestampSeconds,
            after?.lamport,
            after?.actorDid,
            after?.eventId,
            request.limit,
        )
        val nextCursor = rows.lastOrNull()?.let { PendingRecoveryCursor(it.orderKey()) }
        val targets = linkedSetOf<PersistedTargetIdentity>()
        rows.forEach { targets += PersistedTargetIdentity(it.item_type, it.item_id) }

        var completed = 0
        var skipped = 0
        val failures = mutableListOf<PendingRecoveryTargetFailure>()
        targets.forEach { target ->
            when (val outcome = recoverTarget(request.gid, target)) {
                PendingRecoveryTargetOutcome.Completed -> completed++
                PendingRecoveryTargetOutcome.Missing -> skipped++
                is PendingRecoveryTargetOutcome.Failed -> failures += PendingRecoveryTargetFailure(
                    target.itemType,
                    target.itemId,
                    outcome.reason,
                )
            }
        }
        return BoundedPendingRecoveryResult.Completed(
            scannedRowCount = rows.size,
            discoveredTargetCount = targets.size,
            completedTargetCount = completed,
            skippedMissingTargetCount = skipped,
            failedTargetCount = failures.size,
            nextCursor = nextCursor,
            failures = failures,
        )
    }

    private fun recoverTarget(gid: String, identity: PersistedTargetIdentity): PendingRecoveryTargetOutcome = try {
        runTargetTransaction {
            val itemType = when (val parsed = MapItemPersistenceValues.ItemType.parse(identity.itemType)) {
                is PersistenceParseResult.Success -> parsed.value
                else -> return@runTargetTransaction PendingRecoveryTargetOutcome.Failed(
                    PendingRecoveryFailureReason.INVALID_PENDING_IDENTITY,
                )
            }
            if (identity.itemId.isBlank()) {
                return@runTargetTransaction PendingRecoveryTargetOutcome.Failed(PendingRecoveryFailureReason.INVALID_PENDING_IDENTITY)
            }

            val marker = db.mapDao().findMarker(gid, identity.itemId)
            val area = db.mapDao().findArea(gid, identity.itemId)
            val state = db.mapItemDomainStateDao().find(gid, identity.itemType, identity.itemId)
            val exactCanonicalExists = when (itemType) {
                MapItemType.MARKER -> marker != null
                MapItemType.AREA -> area != null
            }
            val oppositeCanonicalExists = when (itemType) {
                MapItemType.MARKER -> area != null
                MapItemType.AREA -> marker != null
            }
            if (!exactCanonicalExists && !oppositeCanonicalExists && state == null) {
                return@runTargetTransaction PendingRecoveryTargetOutcome.Missing
            }
            if (!exactCanonicalExists || oppositeCanonicalExists || state == null) {
                return@runTargetTransaction PendingRecoveryTargetOutcome.Failed(
                    PendingRecoveryFailureReason.PERSISTENCE_INVARIANT_FAILURE,
                )
            }

            replayCoordinator.replay(PendingReplayTarget(gid, itemType, identity.itemId))
            PendingRecoveryTargetOutcome.Completed
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IllegalStateException) {
        PendingRecoveryTargetOutcome.Failed(PendingRecoveryFailureReason.INVALID_PERSISTED_TARGET_HISTORY)
    } catch (failure: Exception) {
        PendingRecoveryTargetOutcome.Failed(PendingRecoveryFailureReason.INTERNAL_PERSISTENCE_FAILURE)
    }

    /** Test seam only; production ownership remains one AppDatabase transaction per target. */
    private fun runTargetTransaction(block: () -> PendingRecoveryTargetOutcome): PendingRecoveryTargetOutcome =
        targetTransaction?.invoke(block) ?: db.runInTransaction<PendingRecoveryTargetOutcome>(block)

    private fun validate(request: BoundedPendingRecoveryRequest): String? {
        if (request.gid.isBlank()) return "gid must be nonblank"
        if (request.limit <= 0) return "limit must be positive"
        return when (val validation = MapItemPersistenceMutationValidator.order(request.after?.order)) {
            is PersistenceParseResult.Success -> null
            else -> validation.toString()
        }
    }

    private data class PersistedTargetIdentity(val itemType: String, val itemId: String)

    private fun MapItemActionEntity.orderKey() = MapActionOrderKey(
        action_t,
        action_lt,
        actor_did,
        event_id,
    )
}

internal sealed interface PendingRecoveryTargetOutcome {
    data object Completed : PendingRecoveryTargetOutcome
    data object Missing : PendingRecoveryTargetOutcome
    data class Failed(val reason: PendingRecoveryFailureReason) : PendingRecoveryTargetOutcome
}
