package app.peermesh.data.map.convergence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.peermesh.core.model.EncPayload
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventEnvelope
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.ingest.EventIngestor
import app.peermesh.data.map.local.LocalMapEventWriter
import app.peermesh.data.map.local.LocalMapEventWriterFactory
import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.local.LocalMapWriteRequest
import app.peermesh.core.json.CanonicalJson
import app.peermesh.data.map.processing.MapEventProcessor
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.data.map.processing.RoomMapEventProcessorFactory
import org.junit.Assert.assertEquals

/**
 * M2C4C test-only harness.
 *
 * Codex implementation rules:
 * - keep every helper in androidTest source;
 * - call the production LocalMapEventWriter and EventIngestor seams;
 * - use two independent databases for first-delivery equivalence;
 * - use one shared database only for deliberate cross-source duplicate sequences;
 * - never compare source, arrival time, event_dedupe rows, logs, or notifications as semantic state;
 * - never add a production architecture layer solely for these tests.
 */
internal class MapEventConvergenceFixture(
    private val gid: String = DEFAULT_GID,
) : AutoCloseable {
    val localDb: AppDatabase = newDatabase()
    val remoteDb: AppDatabase = newDatabase()

    val localWriter: LocalMapEventWriter = LocalMapEventWriterFactory.lazy(localDb)
    val remoteWriter: LocalMapEventWriter = LocalMapEventWriterFactory.lazy(remoteDb)
    private val localRecordingProcessor = RecordingProcessor(RoomMapEventProcessorFactory.lazy(localDb))
    private val remoteRecordingProcessor = RecordingProcessor(RoomMapEventProcessorFactory.lazy(remoteDb))
    val remoteIngestor: EventIngestor = EventIngestor(remoteDb, mapEventProcessor = remoteRecordingProcessor)
    private val localDbRemoteIngestor = EventIngestor(localDb, mapEventProcessor = localRecordingProcessor)
    val lastRemoteResult: MapEventProcessingResult? get() = remoteRecordingProcessor.lastResult

    fun seedEquivalentMembers(vararg actorDids: String) {
        actorDids.forEach { did ->
            seedMember(localDb, did)
            seedMember(remoteDb, did)
        }
    }

    fun processLocallyEquivalent(
        core: EventCore,
        localActorDid: String = core.actor.did,
        insertedAtSeconds: Long = core.time.t,
    ): LocalMapWriteOutcome = localWriter.write(
        LocalMapWriteRequest(
            gid = gid,
            core = core,
            localActorDid = localActorDid,
            insertedAtSeconds = insertedAtSeconds,
        ),
    )

    /**
     * EventIngestor intentionally returns Boolean. Tests needing the exact processor result should
     * use a test-only recording/delegating MapEventProcessor graph without changing production API.
     */
    fun processRemotely(
        core: EventCore,
        source: String = DEFAULT_REMOTE_SOURCE,
    ): Boolean = remoteIngestor.ingest(
        EventEnvelope(
            v = 1,
            gid = gid,
            mid = core.id,
            ts = core.time.t,
            ttl = DEFAULT_ENVELOPE_TTL_SECONDS,
            enc = EncPayload("m2c4c-test-ciphertext"),
        ),
        core,
        source,
    )

    fun processRemotelyDetailed(core: EventCore, source: String = DEFAULT_REMOTE_SOURCE): RemoteProcessingEvidence {
        remoteRecordingProcessor.reset()
        val accepted = processRemotely(core, source)
        return RemoteProcessingEvidence(accepted, remoteRecordingProcessor.lastRequest, remoteRecordingProcessor.lastResult, remoteRecordingProcessor.invocationCount)
    }

    fun processLocallyOnRemoteDb(core: EventCore, localActorDid: String = core.actor.did): LocalMapWriteOutcome =
        remoteWriter.write(LocalMapWriteRequest(gid, core, localActorDid, core.time.t))

    fun processRemotelyOnLocalDbDetailed(core: EventCore, source: String = DEFAULT_REMOTE_SOURCE): RemoteProcessingEvidence {
        localRecordingProcessor.reset()
        val accepted = localDbRemoteIngestor.ingest(envelope(core), core, source)
        return RemoteProcessingEvidence(accepted, localRecordingProcessor.lastRequest, localRecordingProcessor.lastResult, localRecordingProcessor.invocationCount)
    }

    fun processRemotelyOnRemoteDbDetailed(core: EventCore, source: String = DEFAULT_REMOTE_SOURCE): RemoteProcessingEvidence =
        processRemotelyDetailed(core, source)

    private fun envelope(core: EventCore) = EventEnvelope(1, gid, core.id, core.time.t, DEFAULT_ENVELOPE_TTL_SECONDS, enc = EncPayload("m2c4c-test-ciphertext"))

    fun captureLocalTarget(target: MapTargetIdentity): MapTargetSnapshot =
        capturePersistedState(localDb, target)

    fun captureRemoteTarget(target: MapTargetIdentity): MapTargetSnapshot =
        capturePersistedState(remoteDb, target)

    override fun close() {
        localDb.close()
        remoteDb.close()
    }

    private fun newDatabase(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun seedMember(db: AppDatabase, did: String) {
        db.userProfileDao().upsert(
            UserProfileEntity(
                gid = gid,
                actor_did = did,
                display_name = did,
                avatar_id = null,
                updated_at = 1,
                last_event_id = "profile-$did",
                last_event_t = 1,
                last_event_lt = 0,
                last_event_actor = did,
            ),
        )
    }

    private companion object {
        const val DEFAULT_GID = "g"
        const val DEFAULT_REMOTE_SOURCE = "mesh"
        const val DEFAULT_ENVELOPE_TTL_SECONDS = 60
    }
}

internal data class RemoteProcessingEvidence(
    val accepted: Boolean,
    val request: MapEventProcessingRequest?,
    val result: MapEventProcessingResult?,
    val invocationCount: Int,
)

private class RecordingProcessor(private val delegate: MapEventProcessor) : MapEventProcessor {
    var lastRequest: MapEventProcessingRequest? = null
        private set
    var lastResult: MapEventProcessingResult? = null
        private set
    var invocationCount: Int = 0
        private set

    fun reset() { lastRequest = null; lastResult = null; invocationCount = 0 }

    override fun process(request: MapEventProcessingRequest): MapEventProcessingResult {
        invocationCount++
        lastRequest = request
        return delegate.process(request).also { lastResult = it }
    }
}

internal data class MapTargetIdentity(
    val gid: String,
    val itemType: String,
    val itemId: String,
)

/**
 * Normalized semantic snapshot. Fill these records from current Room entities/queries.
 * Keep unstable transport/arrival metadata out of the snapshot.
 */
internal data class MapTargetSnapshot(
    val durableEvents: List<DurableEventSnapshot>,
    val canonicalItem: CanonicalItemSnapshot?,
    val domainState: DomainStateSnapshot?,
    val actions: List<ActionSnapshot>,
    val legacy: LegacyCompatibilitySnapshot?,
)

internal data class DurableEventSnapshot(
    val gid: String,
    val eventId: String,
    val eventType: String,
    val actorDid: String,
    val timestamp: Long,
    val lamport: Long,
    val targetId: String?,
    val rootRef: String?,
    val parentRef: String?,
    val ttlSeconds: Long?,
    val canonicalPayload: String,
)

internal data class CanonicalItemSnapshot(
    val itemType: String,
    val itemId: String,
    val ownerDid: String?,
    val status: String?,
    val canonicalValues: Map<String, String?>,
    val canonicalOrder: OrderTupleSnapshot?,
)

internal data class DomainStateSnapshot(
    val itemType: String,
    val itemId: String,
    val ownerDid: String?,
    val category: String?,
    val categoryOrigin: String,
    val domainVersion: Int?,
    val stateOrigin: String,
    val creatorInformationSource: String?,
    val temporalOrigin: String,
    val canonicalAdd: OrderTupleSnapshot?,
    val lifecycle: String?,
    val expiresAt: Long?,
    val ownerEditUntil: Long?,
    val interactionUntil: Long?,
    val latestConfirmation: OrderTupleSnapshot?,
    val latestSituationUpdate: OrderTupleSnapshot?,
    val latestLifecycle: OrderTupleSnapshot?,
    val latestMaterialChange: OrderTupleSnapshot?,
    val confirmationActorCount: Int,
    val acknowledgementActorCount: Int,
)

internal data class ActionSnapshot(
    val actionId: String,
    val eventId: String,
    val eventType: String,
    val targetType: String,
    val targetId: String,
    val actionType: String,
    val actorDid: String,
    val order: OrderTupleSnapshot,
    val fingerprint: String,
    val materializationState: String,
    val rejectionReason: String?,
    val semanticValues: Map<String, String?>,
)

internal data class OrderTupleSnapshot(
    val timestamp: Long,
    val lamport: Long,
    val actorDid: String,
    val eventId: String,
)

internal data class LegacyCompatibilitySnapshot(
    val confidence: Int?,
    val contributionCount: Int,
    val removalState: Map<String, String?>,
)

internal fun capturePersistedState(
    db: AppDatabase,
    target: MapTargetIdentity,
): MapTargetSnapshot {
    val events = db.openHelper.readableDatabase.query(
        "SELECT gid,event_id,type,actor_did,created_t,time_lt,ref_target,ref_root,ref_parent,ttl_s,payload_json FROM events " +
            "WHERE gid=? AND (ref_target=? OR event_id IN (SELECT event_id FROM map_item_actions WHERE gid=? AND item_id=?)) " +
            "ORDER BY created_t,time_lt,actor_did,event_id",
        arrayOf(target.gid, target.itemId, target.gid, target.itemId),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(DurableEventSnapshot(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5),
                c.stringOrNull(6), c.stringOrNull(7), c.stringOrNull(8), if (c.isNull(9)) null else c.getLong(9), canonicalJson(c.getString(10)),
            ))
        }
    }
    val canonical = if (target.itemType == "marker") db.mapDao().findMarker(target.gid, target.itemId)?.let { m ->
        CanonicalItemSnapshot("marker", m.marker_id, m.actor_did, m.status, mapOf(
            "layer" to m.layer_id, "lat" to m.lat.toString(), "lon" to m.lon.toString(),
            "radius" to m.radius_m?.toString(), "label" to m.label, "note" to m.note,
            "kind" to m.kind, "confidence" to m.confidence.toString(), "expiresAt" to m.expires_at?.toString(),
            "removalRequestedT" to m.removal_requested_t?.toString(), "removalActor" to m.removal_requested_actor,
            "removalReason" to m.removal_reason, "removalCancelT" to m.removal_cancel_t?.toString(),
        ), OrderTupleSnapshot(m.last_event_t, m.last_event_lt, m.last_event_actor, m.last_event_id))
    } else db.mapDao().findArea(target.gid, target.itemId)?.let { a ->
        CanonicalItemSnapshot("area", a.area_id, a.actor_did, a.status, mapOf(
            "layer" to a.layer_id, "geometry" to canonicalJson(a.geometry_json), "label" to a.label,
            "note" to a.note, "kind" to a.kind, "confidence" to a.confidence.toString(),
            "expiresAt" to a.expires_at?.toString(), "removalRequestedT" to a.removal_requested_t?.toString(),
            "removalActor" to a.removal_requested_actor, "removalReason" to a.removal_reason,
            "removalCancelT" to a.removal_cancel_t?.toString(),
        ), OrderTupleSnapshot(a.last_event_t, a.last_event_lt, a.last_event_actor, a.last_event_id))
    }
    val state = db.mapItemDomainStateDao().find(target.gid, target.itemType, target.itemId)?.let { s ->
        DomainStateSnapshot(s.item_type, s.item_id, s.owner_did, s.category, s.category_origin, s.domain_version, s.state_origin, s.creator_information_source, s.temporal_origin,
            order(s.canonical_add_t, s.canonical_add_lt, s.canonical_add_actor, s.canonical_add_event_id), s.situation_lifecycle,
            s.expires_at, s.owner_edit_until, s.interaction_until,
            order(s.latest_confirmation_t, s.latest_confirmation_lt, s.latest_confirmation_actor, s.latest_confirmation_event_id),
            order(s.latest_update_t, s.latest_update_lt, s.latest_update_actor, s.latest_update_event_id),
            order(s.latest_lifecycle_t, s.latest_lifecycle_lt, s.latest_lifecycle_actor, s.latest_lifecycle_event_id),
            order(s.latest_material_t, s.latest_material_lt, s.latest_material_actor, s.latest_material_event_id),
            s.confirmation_actor_count, s.acknowledgement_actor_count)
    }
    val actions = db.openHelper.readableDatabase.query(
        "SELECT action_id,event_id,event_type,item_type,item_id,action_type,actor_did,action_t,action_lt,event_fingerprint,materialization_state,rejection_reason,map_domain_v,information_source,action_note,update_disposition,target_lifecycle " +
            "FROM map_item_actions WHERE gid=? AND item_type=? AND item_id=? ORDER BY action_t,action_lt,actor_did,event_id",
        arrayOf(target.gid, target.itemType, target.itemId),
    ).use { c -> buildList {
        while (c.moveToNext()) add(ActionSnapshot(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), OrderTupleSnapshot(c.getLong(7), c.getLong(8), c.getString(6), c.getString(1)), c.getString(9), c.getString(10), c.stringOrNull(11), mapOf("domainVersion" to c.getString(12), "informationSource" to c.stringOrNull(13), "note" to c.stringOrNull(14), "updateDisposition" to c.stringOrNull(15), "targetLifecycle" to c.stringOrNull(16))))
    } }
    val votes = db.mapItemVoteDao().listVotesForItem(target.gid, target.itemType, target.itemId, 10_000, 0)
    val legacy = canonical?.let { LegacyCompatibilitySnapshot(
        it.canonicalValues["confidence"]?.toIntOrNull(), votes.size,
        it.canonicalValues.filterKeys { key -> key.startsWith("removal") },
    ) }
    return MapTargetSnapshot(events, canonical, state, actions, legacy)
}

internal fun assertEquivalent(
    local: MapTargetSnapshot,
    remote: MapTargetSnapshot,
) {
    assertEquals("durable event semantics", local.durableEvents, remote.durableEvents)
    assertEquals("canonical item semantics", local.canonicalItem, remote.canonicalItem)
    assertEquals("domain projection semantics", local.domainState, remote.domainState)
    assertEquals("immutable action semantics", local.actions, remote.actions)
    assertEquals("legacy compatibility semantics", local.legacy, remote.legacy)
}

private fun canonicalJson(value: String): String =
    CanonicalJson.canonicalize(CanonicalJson.json.parseToJsonElement(value))

private fun android.database.Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

private fun order(t: Long?, lt: Long?, actor: String?, event: String?): OrderTupleSnapshot? =
    if (t == null || lt == null || actor == null || event == null) null else OrderTupleSnapshot(t, lt, actor, event)
