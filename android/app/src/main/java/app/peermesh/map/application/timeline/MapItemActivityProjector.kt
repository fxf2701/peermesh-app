package app.peermesh.map.application.timeline

import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.map.MapItemActionRead
import app.peermesh.map.application.detail.MapItemIdentity

/** Pure M2-read-model to M3B1 presentation projection seam. */
fun interface MapItemActivityProjector {
    fun project(
        requestedIdentity: MapItemIdentity,
        action: MapItemActionRead,
    ): MapItemActivityEntry?
}

/** Strict identity and action-shape projection without exposing ordering internals. */
class DefaultMapItemActivityProjector : MapItemActivityProjector {
    override fun project(
        requestedIdentity: MapItemIdentity,
        action: MapItemActionRead,
    ): MapItemActivityEntry? {
        if (action.gid != requestedIdentity.groupId ||
            action.identity.itemType != requestedIdentity.itemType ||
            action.identity.itemId != requestedIdentity.itemId ||
            action.order.eventId.isBlank() ||
            action.order.actorDid.isBlank() ||
            action.order.timestampSeconds <= 0L
        ) {
            return null
        }

        val type = when (action.action) {
            MapItemAction.CONFIRM -> {
                if (action.updateDisposition != null || action.targetLifecycle != null) return null
                MapItemActivityType.SITUATION_CONFIRMED
            }
            MapItemAction.ADD_UPDATE -> {
                if (action.note.isNullOrBlank() ||
                    action.updateDisposition == null ||
                    action.targetLifecycle != null
                ) {
                    return null
                }
                MapItemActivityType.SITUATION_UPDATED
            }
            MapItemAction.UPDATE_STATUS -> {
                if (action.updateDisposition != null ||
                    action.targetLifecycle != MapSituationLifecycleStatus.NO_LONGER_ACTIVE
                ) {
                    return null
                }
                MapItemActivityType.SITUATION_MARKED_NO_LONGER_ACTIVE
            }
            MapItemAction.ACKNOWLEDGE -> {
                if (action.informationSource != null ||
                    action.note != null ||
                    action.updateDisposition != null ||
                    action.targetLifecycle != null
                ) {
                    return null
                }
                MapItemActivityType.COORDINATION_ACKNOWLEDGED
            }
            MapItemAction.ADD_NOTE -> {
                if (action.note.isNullOrBlank() ||
                    action.informationSource != null ||
                    action.updateDisposition != null ||
                    action.targetLifecycle != null
                ) {
                    return null
                }
                MapItemActivityType.COORDINATION_NOTE_ADDED
            }
        }

        return MapItemActivityEntry(
            entryId = action.order.eventId,
            itemIdentity = requestedIdentity,
            type = type,
            actor = MapItemActivityActor(
                stableId = action.order.actorDid,
                displayValue = action.order.actorDid,
            ),
            actionTimestampSeconds = action.order.timestampSeconds,
            informationSource = action.informationSource,
            note = action.note,
            updateDisposition = action.updateDisposition,
            targetLifecycle = action.targetLifecycle,
        )
    }
}
