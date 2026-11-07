package app.peermesh.data.map.replay

import app.peermesh.core.map.MapItemType

/** Exact target identity. Item type is never inferred during replay. */
internal data class PendingReplayTarget(
    val gid: String,
    val itemType: MapItemType,
    val itemId: String,
)

/**
 * Result of one target-scoped replay executed inside a caller-owned Room transaction.
 * No Room entities or transport/UI metadata cross this boundary.
 */
internal data class TargetPendingReplayResult(
    val examinedCount: Int,
    val appliedTransitionCount: Int,
    val rejectedTransitionCount: Int,
    val alreadyCompletedCount: Int,
    val projectionRefresh: ProjectionRefreshOutcome,
)

internal sealed interface ProjectionRefreshOutcome {
    data object NotRequired : ProjectionRefreshOutcome
    data object Refreshed : ProjectionRefreshOutcome
    /** Live-action orchestration owns one final refresh after inserting the new applied row. */
    data object DeferredToCaller : ProjectionRefreshOutcome
}

/**
 * M2C3B target-specific replay seam.
 *
 * Transaction contract: the caller MUST already own the Room transaction. Implementations MUST
 * NOT call runInTransaction, enqueue transport work, or replay any target other than [target].
 */
internal fun interface TargetPendingReplayCoordinator {
    fun replay(target: PendingReplayTarget): TargetPendingReplayResult
}
