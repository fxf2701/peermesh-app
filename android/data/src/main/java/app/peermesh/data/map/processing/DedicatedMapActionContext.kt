package app.peermesh.data.map.processing

import app.peermesh.core.map.MapItemInteractionEvent
import app.peermesh.core.model.EventCore
import app.peermesh.data.map.MapItemMaterializationSnapshot
import app.peermesh.data.db.MapActionOrderKey

/**
 * DB-aware context supplied by the M2C transaction owner to dedicated-action policy/reduction.
 *
 * This type deliberately contains no UI, transport, arrival-order, or Room-row-ID input.
 */
internal data class DedicatedMapActionContext(
    val gid: String,
    val event: EventCore,
    val interaction: MapItemInteractionEvent,
    val target: DedicatedMapActionTarget,
    val snapshot: MapItemMaterializationSnapshot,
    val localActorDid: String?,
    /** Earliest applied resolution order: the irreversible Situation cutoff. */
    val earliestAppliedResolutionOrder: MapActionOrderKey? = null,
)

/** Canonical target facts already verified inside the active Room transaction. */
internal data class DedicatedMapActionTarget(
    val exists: Boolean,
    val deleted: Boolean,
)
