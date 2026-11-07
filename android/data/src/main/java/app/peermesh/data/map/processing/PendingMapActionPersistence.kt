package app.peermesh.data.map.processing

import app.peermesh.core.map.MapItemInteractionEvent
import app.peermesh.core.map.MapItemType
import app.peermesh.core.model.EventCore

/**
 * M2C3A seam for immutable action-before-target persistence.
 *
 * The caller owns the active Room transaction. This boundary must not inspect projection state,
 * replay pending rows, create placeholder target state, or open its own transaction.
 */
internal interface PendingMapActionPersistence {
    fun persist(request: PendingMapActionPersistenceRequest): PendingMapActionPersistenceResult
}

/** Exact wire-derived input after structural decoding and membership enforcement. */
internal data class PendingMapActionPersistenceRequest(
    val gid: String,
    val event: EventCore,
    val interaction: MapItemInteractionEvent,
    val itemType: MapItemType,
    val itemId: String,
)

/** Internal M2C3A result; public processing results remain owned by RoomMapEventProcessor. */
internal sealed interface PendingMapActionPersistenceResult {
    data object Inserted : PendingMapActionPersistenceResult
    data object ExactDuplicate : PendingMapActionPersistenceResult
    data object ConflictingDuplicate : PendingMapActionPersistenceResult

    data class InvalidPersistedState(
        val detail: String,
    ) : PendingMapActionPersistenceResult
}

internal fun MapItemInteractionEvent.itemId(): String = when (this) {
    is MapItemInteractionEvent.Confirmation -> action.metadata.itemId
    is MapItemInteractionEvent.SituationUpdate -> action.metadata.itemId
    is MapItemInteractionEvent.SituationStatus -> action.metadata.itemId
    is MapItemInteractionEvent.Acknowledgement -> action.metadata.itemId
    is MapItemInteractionEvent.CoordinationNote -> action.metadata.itemId
}

internal fun MapItemInteractionEvent.actionId(): String = when (this) {
    is MapItemInteractionEvent.Confirmation -> action.metadata.actionId
    is MapItemInteractionEvent.SituationUpdate -> action.metadata.actionId
    is MapItemInteractionEvent.SituationStatus -> action.metadata.actionId
    is MapItemInteractionEvent.Acknowledgement -> action.metadata.actionId
    is MapItemInteractionEvent.CoordinationNote -> action.metadata.actionId
}
