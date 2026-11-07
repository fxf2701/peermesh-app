package app.peermesh.data.map.processing

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.data.db.entity.EventEntity
import kotlinx.serialization.encodeToString

/** Centralizes durable event persistence so reducer and stored ordering inputs cannot diverge. */
internal object MapEventEntityFactory {
    fun create(request: MapEventProcessingRequest): EventEntity {
        val core = request.core
        return EventEntity(
            gid = request.gid,
            event_id = core.id,
            type = core.type,
            source = request.source,
            created_t = core.time.t,
            time_lt = core.time.lt ?: 0L,
            actor_did = core.actor.did,
            ref_root = core.ref.root,
            ref_parent = core.ref.parent,
            ref_target = core.ref.target,
            ttl_s = core.meta?.ttl_s,
            payload_json = CanonicalJson.json.encodeToString(EventCore.serializer(), core),
            inserted_at = request.insertedAtSeconds,
        )
    }
}
