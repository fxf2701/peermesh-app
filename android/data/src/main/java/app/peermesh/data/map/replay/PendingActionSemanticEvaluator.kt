package app.peermesh.data.map.replay

import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.map.MapItemActionRead
import app.peermesh.data.map.MapItemMaterializationSnapshot
import app.peermesh.data.map.processing.MapEventRejectionReason
import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemActionPolicy
import app.peermesh.core.map.MapSituationLifecycleStatus

/**
 * Shared target-dependent semantic seam for live existing-target actions and pending replay.
 *
 * Inputs are normalized immutable semantics, not EventCore payload parsing and not Room entities.
 * Membership is intentionally absent: it was accepted when the pending action was persisted.
 */
internal fun interface PendingActionSemanticEvaluator {
    fun evaluate(input: PendingActionSemanticInput): PendingActionSemanticDecision
}

internal data class PendingActionSemanticInput(
    val action: MapItemActionRead,
    val targetSnapshot: MapItemMaterializationSnapshot,
    /** Earliest applied no-longer-active action in total action order, or null. */
    val earliestResolutionOrder: MapActionOrderKey?,
    val targetDeleted: Boolean,
)

internal sealed interface PendingActionSemanticDecision {
    data object Apply : PendingActionSemanticDecision
    data class Reject(val reason: MapEventRejectionReason) : PendingActionSemanticDecision
}

internal object DefaultPendingActionSemanticEvaluator : PendingActionSemanticEvaluator {
    override fun evaluate(input: PendingActionSemanticInput): PendingActionSemanticDecision {
        if (input.targetDeleted) return reject(MapEventRejectionReason.TARGET_DELETED)
        val category = input.targetSnapshot.category
            ?: return reject(MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED)
        if (!MapItemActionPolicy.allows(category, input.action.action)) {
            return reject(MapEventRejectionReason.ACTION_NOT_ALLOWED_FOR_CATEGORY)
        }
        input.targetSnapshot.windows.expiresAt?.let {
            if (input.action.order.timestampSeconds >= it) return reject(MapEventRejectionReason.TARGET_EXPIRED)
        }
        input.targetSnapshot.windows.interactionUntil?.let {
            if (input.action.order.timestampSeconds >= it) return reject(MapEventRejectionReason.INTERACTION_WINDOW_CLOSED)
        }
        if (input.action.action == MapItemAction.UPDATE_STATUS) {
            return if (input.action.targetLifecycle == MapSituationLifecycleStatus.NO_LONGER_ACTIVE) {
                PendingActionSemanticDecision.Apply
            } else reject(MapEventRejectionReason.LIFECYCLE_REACTIVATION)
        }
        if (input.action.action == MapItemAction.CONFIRM || input.action.action == MapItemAction.ADD_UPDATE) {
            val cutoff = input.earliestResolutionOrder
            if (cutoff != null && input.action.order >= cutoff) return reject(MapEventRejectionReason.TARGET_RESOLVED)
        }
        return PendingActionSemanticDecision.Apply
    }

    private fun reject(reason: MapEventRejectionReason) = PendingActionSemanticDecision.Reject(reason)
}
