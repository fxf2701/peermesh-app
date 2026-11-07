package app.peermesh.data.map.processing

import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemInteractionEvent
import app.peermesh.data.map.MapItemActionRead
import app.peermesh.data.map.MapItemIdentity
import app.peermesh.data.map.replay.DefaultPendingActionSemanticEvaluator
import app.peermesh.data.map.replay.PendingActionSemanticDecision
import app.peermesh.data.map.replay.PendingActionSemanticInput

/** Pure semantic classification boundary for existing-target dedicated actions. */
internal fun interface DedicatedMapActionPolicy {
    fun evaluate(context: DedicatedMapActionContext): DedicatedMapActionPolicyDecision
}

internal object DefaultDedicatedMapActionPolicy : DedicatedMapActionPolicy {
    override fun evaluate(context: DedicatedMapActionContext): DedicatedMapActionPolicyDecision {
        val action = MapItemActionRead(
            context.gid, MapItemIdentity(context.interaction.itemType, context.interaction.itemId()),
            context.interaction.actionType(), MapEventOrderingPolicy.orderKey(context.event),
            context.interaction.informationSource(), context.interaction.actionNote(),
            context.interaction.updateDisposition(), context.interaction.targetLifecycle(),
        )
        return when (val decision = DefaultPendingActionSemanticEvaluator.evaluate(PendingActionSemanticInput(
            action, context.snapshot, context.earliestAppliedResolutionOrder, context.target.deleted,
        ))) {
            PendingActionSemanticDecision.Apply -> if (
                context.earliestAppliedResolutionOrder != null &&
                (action.action == MapItemAction.CONFIRM || action.action == MapItemAction.ADD_UPDATE) &&
                action.order < context.earliestAppliedResolutionOrder
            ) DedicatedMapActionPolicyDecision.AcceptHistorical else DedicatedMapActionPolicyDecision.AcceptApplied
            is PendingActionSemanticDecision.Reject -> DedicatedMapActionPolicyDecision.Reject(decision.reason)
        }
    }
}

private fun MapItemInteractionEvent.informationSource() = when (this) {
    is MapItemInteractionEvent.Confirmation -> action.source
    is MapItemInteractionEvent.SituationUpdate -> action.source
    is MapItemInteractionEvent.SituationStatus -> action.source
    else -> null
}
private fun MapItemInteractionEvent.actionNote() = when (this) {
    is MapItemInteractionEvent.Confirmation -> action.note
    is MapItemInteractionEvent.SituationUpdate -> action.content
    is MapItemInteractionEvent.SituationStatus -> action.note
    is MapItemInteractionEvent.CoordinationNote -> action.content
    else -> null
}
private fun MapItemInteractionEvent.updateDisposition() =
    (this as? MapItemInteractionEvent.SituationUpdate)?.action?.updateType
private fun MapItemInteractionEvent.targetLifecycle() =
    (this as? MapItemInteractionEvent.SituationStatus)?.action?.status

internal fun MapItemInteractionEvent.actionType(): MapItemAction = when (this) {
    is MapItemInteractionEvent.Confirmation -> MapItemAction.CONFIRM
    is MapItemInteractionEvent.SituationUpdate -> MapItemAction.ADD_UPDATE
    is MapItemInteractionEvent.SituationStatus -> MapItemAction.UPDATE_STATUS
    is MapItemInteractionEvent.Acknowledgement -> MapItemAction.ACKNOWLEDGE
    is MapItemInteractionEvent.CoordinationNote -> MapItemAction.ADD_NOTE
}

internal sealed interface DedicatedMapActionPolicyDecision {
    /** The immutable action may be inserted as applied history. */
    data object AcceptApplied : DedicatedMapActionPolicyDecision

    /**
     * The action is historically valid but must not reopen or otherwise replace newer projection.
     * M2C2A persists it as applied history; M2C2B owns projection selection.
     */
    data object AcceptHistorical : DedicatedMapActionPolicyDecision

    data class Reject(
        val reason: MapEventRejectionReason,
    ) : DedicatedMapActionPolicyDecision
}
