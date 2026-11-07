package app.peermesh.data.map.processing

import app.peermesh.core.model.EventCore
import app.peermesh.data.db.MapActionOrderKey

/**
 * M2C1 ordering contract.
 *
 * The original serialized EventCore ordering inputs are canonical for persistence and reduction:
 * timestamp, Lamport, actor DID, then event ID. Clock-skew checks may reject an event, but must not
 * create a reducer-only adjusted EventCore.
 */
object MapEventOrderingPolicy {
    fun orderKey(core: EventCore): MapActionOrderKey = MapActionOrderKey(
        timestampSeconds = core.time.t,
        lamport = core.time.lt ?: 0L,
        actorDid = core.actor.did,
        eventId = core.id,
    )
}
