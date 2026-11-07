package app.peermesh.data.map.replay.m2d4

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.data.map.processing.MapEventProcessor

/** Test-only durable-log authority. The returned order is the requested replay order. */
internal fun interface DurableMapReplayFixtureSource {
    fun load(): List<DurableMapReplayFixture>
}

/** Immutable accepted durable event content plus non-semantic persistence metadata. */
internal data class DurableMapReplayFixture(
    val gid: String,
    val core: EventCore,
    val originalSource: String,
    val originalInsertedAtSeconds: Long,
) {
    companion object {
        /**
         * Reuses the exact serialized durable core and rejects a contradictory durable row.
         * No semantic field is reconstructed from a "similar" payload.
         */
        fun from(entity: EventEntity): DurableMapReplayFixture {
            val core = CanonicalJson.json.decodeFromString(EventCore.serializer(), entity.payload_json)
            require(
                core.id == entity.event_id && core.type == entity.type &&
                    core.actor.did == entity.actor_did && core.time.t == entity.created_t &&
                    (core.time.lt ?: 0L) == entity.time_lt && core.ref.root == entity.ref_root &&
                    core.ref.parent == entity.ref_parent && core.ref.target == entity.ref_target &&
                    core.meta?.ttl_s == entity.ttl_s,
            ) { "durable row contradicts serialized EventCore: ${entity.event_id}" }
            return DurableMapReplayFixture(entity.gid, core, entity.source, entity.inserted_at)
        }
    }
}

/** One isolated Room graph. Its processor remains the only map transaction owner. */
internal interface M2D4ReplayEnvironment : AutoCloseable {
    val processor: MapEventProcessor
    val snapshotReader: MapReplaySnapshotReader
}

/** Snapshot readers omit Room row IDs and arrival-only metadata. */
internal fun interface MapReplaySnapshotReader {
    fun read(gid: String): MapReplaySemanticSnapshot
}

internal data class MapReplaySemanticSnapshot(
    val canonicalTargets: List<CanonicalTargetSnapshot>,
    val domainStates: List<DomainStateSnapshot>,
    val actions: List<ActionHistorySnapshot>,
)

internal data class CanonicalTargetSnapshot(
    val itemType: String,
    val itemId: String,
    val canonicalContent: Map<String, String?>,
)

internal data class DomainStateSnapshot(
    val itemType: String,
    val itemId: String,
    val semanticContent: Map<String, String?>,
)

internal data class ActionHistorySnapshot(
    val eventId: String,
    val actionId: String,
    val itemType: String,
    val itemId: String,
    val semanticContent: Map<String, String?>,
)

/** Only represents the real boundary available without changing production code. */
internal fun interface M2D4FailureHook {
    fun beforeProcessor(fixture: DurableMapReplayFixture)

    companion object {
        val None = M2D4FailureHook { }
    }
}

internal data class M2D4ReplayEvidence(
    val results: List<MapEventProcessingResult>,
    val snapshotsByGroup: Map<String, MapReplaySemanticSnapshot>,
) {
    fun snapshot(gid: String): MapReplaySemanticSnapshot = requireNotNull(snapshotsByGroup[gid])
}
