package app.peermesh.data.map.processing

import app.peermesh.core.map.MapDomainPayloadContract
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.core.map.MapItemEventCodec
import app.peermesh.core.map.MapItemInteractionEvent
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.core.validation.EventValidator
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemPersistenceRecordValidator
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.MapItemStateOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.db.LegacyMapItemWindowBackfill
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.PersistenceWriteResult
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.db.entity.MapItemDomainStateEntity
import app.peermesh.data.db.entity.PendingCanonicalMapEventEntity
import app.peermesh.data.db.entity.PendingCanonicalMapEventState
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.map.MapItemEntityMappers
import app.peermesh.data.map.MapItemIdentity
import app.peermesh.data.map.MapItemMaterializationSnapshot
import app.peermesh.data.map.MapItemWindows
import app.peermesh.data.reducer.CanonicalMapReductionResult
import app.peermesh.data.reducer.MapEventReducerDispatcher
import app.peermesh.data.reducer.MapEventReductionDispatchResult
import app.peermesh.data.reducer.DedicatedMapActionReductionResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.peermesh.core.json.CanonicalJson
import app.peermesh.data.map.materialization.RoomMapItemActionProjectionDeriver
import app.peermesh.data.map.replay.DefaultPendingActionSemanticEvaluator
import app.peermesh.data.map.replay.PendingReplayTarget
import app.peermesh.data.map.replay.RoomTargetPendingReplayCoordinator
import app.peermesh.data.map.replay.RoomTargetProjectionRefresher
import app.peermesh.data.membership.GroupMembershipPolicy
import kotlinx.serialization.decodeFromString

/** Sole Room transaction owner for M2C1. Production wiring is intentionally deferred to M2C4. */
internal class RoomMapEventProcessor(
    private val db: AppDatabase,
    private val canonicalPolicy: CanonicalMapEventPolicy,
    private val dispatcher: MapEventReducerDispatcher,
) : MapEventProcessor {
    private val pendingPersistence: PendingMapActionPersistence = RoomPendingMapActionPersistence(
        db.mapItemActionDao(), Sha256MapEventFingerprint, DefaultMapItemActionEntityFactory,
    )
    private val pendingReplay = RoomTargetPendingReplayCoordinator(
        db.mapItemActionDao(), db.eventsDao(), db.mapItemDomainStateDao(), db.mapDao(),
        DefaultPendingActionSemanticEvaluator,
        RoomTargetProjectionRefresher(
            RoomMapItemActionProjectionDeriver(db.mapItemActionDao()), db.mapItemDomainStateDao(),
        ),
    )
    override fun process(request: MapEventProcessingRequest): MapEventProcessingResult {
        val core = request.core
        if (request.gid.isBlank() || request.source.isBlank() ||
            (request.source == LOCAL_SOURCE && request.localActorDid.isNullOrBlank()) ||
            (!isDedicated(core.type) && !EventValidator.validate(core))
        ) {
            return MapEventProcessingResult.Rejected(core.id.ifBlank { null }, MapEventRejectionReason.INVALID_REQUEST)
        }
        if (!EventTypes.isMap(core.type)) return MapEventProcessingResult.Rejected(core.id, MapEventRejectionReason.UNSUPPORTED_MAP_EVENT)
        if (!isDedicated(core.type) && !isCanonical(core.type)) return MapEventProcessingResult.Rejected(core.id, MapEventRejectionReason.UNSUPPORTED_MAP_EVENT)

        val result = try {
            db.runInTransaction<MapEventProcessingResult> { processInTransaction(request) }
        } catch (abort: TransactionAbort) {
            abort.result
        } catch (cause: Throwable) {
            MapEventProcessingResult.PersistenceFailure(core.id, cause)
        }
        return result
    }

    private fun processInTransaction(request: MapEventProcessingRequest): MapEventProcessingResult {
        if (isDedicated(request.core.type)) return processDedicatedInTransaction(request)
        val core = request.core
        val eventEntity = MapEventEntityFactory.create(request)
        val existingEvent = db.eventsDao().findById(request.gid, core.id)
        val pendingCanonical = db.pendingCanonicalMapEventDao().find(request.gid, core.id)
        val isPendingReplay = existingEvent != null &&
            pendingCanonical?.materialization_state == PendingCanonicalMapEventState.PENDING_MISSING_CANONICAL &&
            sameWireEvent(existingEvent, eventEntity)
        if (existingEvent != null && !isPendingReplay) {
            return if (sameWireEvent(existingEvent, eventEntity)) MapEventProcessingResult.ExactDuplicate(core.id)
            else MapEventProcessingResult.ConflictingDuplicate(core.id)
        }
        validateLocalParticipation(request)?.let { return it }
        val removed = db.removedMemberDao().count(request.gid, core.actor.did) > 0
        if (removed || db.userProfileDao().find(request.gid, core.actor.did) == null) {
            return rejected(core.id, MapEventRejectionReason.ACTOR_NOT_MEMBER)
        }

        val identity = identity(core) ?: return rejected(core.id, MapEventRejectionReason.INVALID_REQUEST)
        val marker = db.mapDao().findMarker(request.gid, identity.second)
        val area = db.mapDao().findArea(request.gid, identity.second)
        if ((identity.first == MapItemType.MARKER && area != null) || (identity.first == MapItemType.AREA && marker != null)) {
            return rejected(core.id, MapEventRejectionReason.TARGET_TYPE_CONFLICT)
        }
        val canonicalExists = marker != null || area != null
        if (core.type.endsWith(".ADD") && canonicalExists) return rejected(core.id, MapEventRejectionReason.CONFLICTING_ADD)
        if (!core.type.endsWith(".ADD") && !canonicalExists) {
            return if (request.source == LOCAL_SOURCE || isPendingReplay) {
                rejected(core.id, MapEventRejectionReason.OWNER_REQUIRED)
            } else {
                deferMissingCanonical(request, identity, eventEntity)
            }
        }
        if (core.type.endsWith(".UPD") && (marker?.status == "deleted" || area?.status == "deleted")) {
            return rejected(core.id, MapEventRejectionReason.TARGET_DELETED)
        }

        val canonicalOwner = marker?.actor_did ?: area?.actor_did
        if (!core.type.endsWith(".ADD") && canonicalOwner != core.actor.did) {
            when (LegacyContribution.parse(core)) {
                is LegacyContribution.ParseResult.Valid -> Unit
                LegacyContribution.ParseResult.Absent -> return rejected(core.id, MapEventRejectionReason.OWNER_REQUIRED)
                LegacyContribution.ParseResult.Invalid -> return rejected(core.id, MapEventRejectionReason.INVALID_LEGACY_CONTRIBUTION)
            }
            if (!core.type.endsWith(".UPD")) {
                return rejected(core.id, MapEventRejectionReason.OWNER_REQUIRED)
            }
        }

        val stateEntity = db.mapItemDomainStateDao().find(request.gid, persisted(identity.first), identity.second)
        var snapshot = stateEntity?.let {
            when (val parsed = MapItemEntityMappers.snapshot(it)) {
                is PersistenceParseResult.Success -> parsed.value
                else -> return MapEventProcessingResult.InvalidPersistedState(core.id, parsed.toString())
            }
        }
        if (!core.type.endsWith(".ADD") && stateEntity == null) {
            // Defensive read-only compatibility fallback: M2C1 does not repair/materialize a
            // missing domain row. Future M2C2/M2C4 processing still requires persisted state.
            val kind = marker?.kind ?: area?.kind
            val category = MapItemKindCatalog.categoryFor(kind)
            val canonicalCreation = marker?.created_t ?: area?.created_t ?: return MapEventProcessingResult.InvalidPersistedState(core.id, "missing_canonical_creation_time")
            val legacyWindows = LegacyMapItemWindowBackfill.derive(canonicalCreation, marker?.expires_at ?: area?.expires_at)
            if (category == null || legacyWindows.temporalOrigin == MapItemTemporalOrigin.LEGACY_UNRESOLVED.persistedValue) {
                return MapEventProcessingResult.InvalidPersistedState(core.id, "missing_domain_state")
            }
            snapshot = MapItemMaterializationSnapshot(
                request.gid, MapItemIdentity(identity.first, identity.second), category,
                MapItemCategoryOrigin.LEGACY_KIND_DERIVED, null, marker?.actor_did ?: area!!.actor_did,
                null, MapItemStateOrigin.LEGACY_ROW_BACKFILL, if (category == MapItemCategory.SITUATION) MapSituationLifecycleStatus.ACTIVE else null,
                MapItemWindows(legacyWindows.windows.expiresAtSeconds, legacyWindows.windows.ownerEditUntilSeconds, legacyWindows.windows.interactionUntilSeconds),
                app.peermesh.data.db.MapItemTemporalOrigin.entries.first { it.persistedValue == legacyWindows.temporalOrigin },
                null, null, null, null, null, 0, 0,
            )
        }
        if (snapshot != null) {
            val canonicalExpires = marker?.expires_at ?: area?.expires_at
            val canonicalOwner = marker?.actor_did ?: area?.actor_did
            val canonicalKind = marker?.kind ?: area?.kind
            val categoryFromKind = MapItemKindCatalog.categoryFor(canonicalKind)
            if (snapshot.ownerDid != canonicalOwner || snapshot.windows.expiresAt != canonicalExpires ||
                (snapshot.category != null && categoryFromKind != null && snapshot.category != categoryFromKind) ||
                snapshot.identity.itemId != identity.second ||
                snapshot.identity.itemType != identity.first) {
                return MapEventProcessingResult.InvalidPersistedState(core.id, "canonical_domain_state_mismatch")
            }
        }
        val payload = core.data as JsonObject
        if (core.type.endsWith(".UPD") && payload.keys.any { it in setOf(
                MapDomainPayloadContract.ACTION_FIELD, MapDomainPayloadContract.ACTION_ID_FIELD,
                MapDomainPayloadContract.ACTION_TIMESTAMP_FIELD, MapDomainPayloadContract.LIFECYCLE_STATUS_FIELD,
                MapDomainPayloadContract.UPDATE_TYPE_FIELD, MapDomainPayloadContract.ACTION_NOTE_FIELD,
            ) }) return rejected(core.id, MapEventRejectionReason.INVALID_REQUEST)
        val updateKind = string(payload, "kind")
        if (core.type.endsWith(".UPD") && updateKind != null) {
            val compatibleType = if (identity.first == MapItemType.MARKER) MapItemKindCatalog.supportsMarker(updateKind) else MapItemKindCatalog.supportsArea(updateKind)
            if (!compatibleType || (snapshot?.category != null && MapItemKindCatalog.categoryFor(updateKind) != snapshot.category)) {
                return rejected(core.id, MapEventRejectionReason.TARGET_TYPE_CONFLICT)
            }
        }
        val decision = canonicalPolicy.evaluate(request.gid, core, snapshot, request.localActorDid)
        if (decision is CanonicalMapEventPolicyDecision.Reject) return rejected(core.id, decision.reason)

        if (!isPendingReplay && db.eventsDao().insert(eventEntity) == -1L) {
            throw TransactionAbort(MapEventProcessingResult.ConflictingDuplicate(core.id))
        }
        val dispatched = dispatcher.route(request.gid, core, decision)
        var canonicalApplied = false
        var contributionApplied = false
        when (dispatched) {
            is MapEventReductionDispatchResult.Canonical -> when (val result = dispatched.result) {
                CanonicalMapReductionResult.Applied -> canonicalApplied = true
                CanonicalMapReductionResult.LegacyContributionApplied -> contributionApplied = true
                CanonicalMapReductionResult.NoChange -> Unit
                is CanonicalMapReductionResult.Rejected -> throw TransactionAbort(
                    MapEventProcessingResult.Rejected(core.id, reason(result.reason)),
                )
            }
            else -> throw TransactionAbort(rejected(core.id, MapEventRejectionReason.UNSUPPORTED_MAP_EVENT))
        }
        if (core.type.endsWith(".ADD")) {
            val canonicalCreation = if (identity.first == MapItemType.MARKER) db.mapDao().findMarker(request.gid, identity.second)?.created_t
                else db.mapDao().findArea(request.gid, identity.second)?.created_t
            val canonicalExpires = if (identity.first == MapItemType.MARKER) db.mapDao().findMarker(request.gid, identity.second)?.expires_at
                else db.mapDao().findArea(request.gid, identity.second)?.expires_at
            if (canonicalCreation == null) throw IllegalStateException("canonical ADD row missing after reduction")
            insertDomainState(request, identity, canonicalCreation, canonicalExpires)
            replayPendingCanonical(request.gid, identity, core.id)
            pendingReplay.replay(PendingReplayTarget(request.gid, identity.first, identity.second))
        }
        val result = if (canonicalApplied || contributionApplied) MapEventProcessingResult.Accepted(core.id)
        else MapEventProcessingResult.AcceptedNoChange(core.id)
        if (isPendingReplay) {
            db.pendingCanonicalMapEventDao().transitionPending(
                request.gid,
                core.id,
                PendingCanonicalMapEventState.APPLIED,
                null,
                request.insertedAtSeconds,
            )
        }
        return result
    }

    private fun deferMissingCanonical(
        request: MapEventProcessingRequest,
        identity: Pair<MapItemType, String>,
        eventEntity: EventEntity,
    ): MapEventProcessingResult {
        val dao = db.pendingCanonicalMapEventDao()
        val now = request.insertedAtSeconds
        dao.deleteExpiredPending(now)
        dao.deleteTerminalBefore(now - TERMINAL_RETENTION_SECONDS)
        if (dao.countPendingTarget(request.gid, persisted(identity.first), identity.second) >= MAX_PENDING_PER_TARGET ||
            dao.countPendingGroup(request.gid) >= MAX_PENDING_PER_GROUP
        ) {
            return rejected(request.core.id, MapEventRejectionReason.TARGET_NOT_FOUND)
        }
        if (db.eventsDao().insert(eventEntity) == -1L) {
            throw TransactionAbort(MapEventProcessingResult.ConflictingDuplicate(request.core.id))
        }
        val pending = PendingCanonicalMapEventEntity(
            gid = request.gid,
            event_id = request.core.id,
            item_type = persisted(identity.first),
            item_id = identity.second,
            root_event_id = request.core.ref.root,
            event_t = request.core.time.t,
            event_lt = request.core.time.lt ?: 0L,
            actor_did = request.core.actor.did,
            event_fingerprint = Sha256MapEventFingerprint.sha256(request.core),
            materialization_state = PendingCanonicalMapEventState.PENDING_MISSING_CANONICAL,
            rejection_reason = null,
            inserted_at = now,
            updated_at = now,
            expires_at = now + PENDING_RETENTION_SECONDS,
            replay_attempt_count = 0,
        )
        if (dao.insertIgnore(pending) == -1L) {
            throw TransactionAbort(MapEventProcessingResult.ConflictingDuplicate(request.core.id))
        }
        return MapEventProcessingResult.AcceptedDeferred(request.core.id, request.core.ref.root)
    }

    private fun replayPendingCanonical(
        gid: String,
        identity: Pair<MapItemType, String>,
        canonicalAddEventId: String,
    ) {
        val dao = db.pendingCanonicalMapEventDao()
        val pending = dao.findPendingTarget(gid, persisted(identity.first), identity.second)
        pending.forEach { candidate ->
            val now = System.currentTimeMillis() / 1000L
            if (candidate.root_event_id != null && candidate.root_event_id != canonicalAddEventId) {
                dao.transitionPending(
                    gid,
                    candidate.event_id,
                    PendingCanonicalMapEventState.REJECTED,
                    MapEventRejectionReason.TARGET_TYPE_CONFLICT.persistedValue,
                    now,
                )
                return@forEach
            }
            val stored = db.eventsDao().findById(gid, candidate.event_id)
            val core = stored?.let {
                runCatching { CanonicalJson.json.decodeFromString<EventCore>(it.payload_json) }.getOrNull()
            }
            if (core == null || identity(core) != identity ||
                Sha256MapEventFingerprint.sha256(core) != candidate.event_fingerprint
            ) {
                dao.transitionPending(
                    gid,
                    candidate.event_id,
                    PendingCanonicalMapEventState.REJECTED,
                    MapEventRejectionReason.INVALID_PERSISTED_STATE.persistedValue,
                    now,
                )
                return@forEach
            }
            val result = process(
                MapEventProcessingRequest(
                    gid = gid,
                    core = core,
                    source = stored.source,
                    insertedAtSeconds = now,
                ),
            )
            if (result is MapEventProcessingResult.Rejected ||
                result is MapEventProcessingResult.InvalidPersistedState ||
                result is MapEventProcessingResult.PersistenceFailure
            ) {
                val reason = when (result) {
                    is MapEventProcessingResult.Rejected -> result.reason.persistedValue
                    is MapEventProcessingResult.InvalidPersistedState -> MapEventRejectionReason.INVALID_PERSISTED_STATE.persistedValue
                    else -> null
                }
                if (reason != null) {
                    dao.transitionPending(
                        gid,
                        candidate.event_id,
                        PendingCanonicalMapEventState.REJECTED,
                        reason,
                        now,
                    )
                }
            }
        }
    }

    private fun processDedicatedInTransaction(request: MapEventProcessingRequest): MapEventProcessingResult {
        val core = request.core
        val eventEntity = MapEventEntityFactory.create(request)
        val existingEvent = db.eventsDao().findById(request.gid, core.id)
        if (existingEvent != null) {
            return classifyExistingDedicatedPair(request, MapItemEventCodec.decode(core))
        }
        validateLocalParticipation(request)?.let { return it }
        if (!EventValidator.validate(core)) return rejected(core.id, MapEventRejectionReason.INVALID_REQUEST)
        val interaction = MapItemEventCodec.decode(core)
        if (interaction == null) return rejected(core.id, MapEventRejectionReason.INVALID_ACTION_PAYLOAD)

        val removed = db.removedMemberDao().count(request.gid, core.actor.did) > 0
        if (removed || db.userProfileDao().find(request.gid, core.actor.did) == null) {
            return rejected(core.id, MapEventRejectionReason.ACTOR_NOT_MEMBER)
        }

        val actionDao = db.mapItemActionDao()
        val actionId = interaction.actionId()
        if (actionDao.findByActionId(request.gid, actionId) != null || actionDao.findByEventId(request.gid, core.id) != null) {
            return MapEventProcessingResult.InvalidPersistedState(core.id, "action_exists_without_durable_event")
        }

        val targetId = interaction.itemId()
        val marker = db.mapDao().findMarker(request.gid, targetId)
        val area = db.mapDao().findArea(request.gid, targetId)
        if ((interaction.itemType == MapItemType.MARKER && area != null) ||
            (interaction.itemType == MapItemType.AREA && marker != null)
        ) return rejected(core.id, MapEventRejectionReason.TARGET_TYPE_CONFLICT)
        val correctTargetExists = if (interaction.itemType == MapItemType.MARKER) marker != null else area != null
        // Preserve the accepted M2C2 target/context validation order. Pending candidates have no
        // canonical context by design, so only that branch inserts before action persistence.
        if (correctTargetExists) loadDedicatedContext(request, interaction)

        if (db.eventsDao().insert(eventEntity) == -1L) {
            throw TransactionAbort(MapEventProcessingResult.InvalidPersistedState(core.id, "durable_event_insert_lost"))
        }
        if (!correctTargetExists) {
            return when (val result = pendingPersistence.persist(
                PendingMapActionPersistenceRequest(request.gid, core, interaction, interaction.itemType, targetId),
            )) {
                // An action can be durably accepted while its canonical ADD is still absent.
                // Report that state explicitly so the ingest boundary retains the action and
                // starts dependency repair instead of publishing an ordinary map-sync success.
                PendingMapActionPersistenceResult.Inserted -> core.ref.root
                    ?.takeIf { it.isNotBlank() }
                    ?.let { MapEventProcessingResult.AcceptedDeferred(core.id, it) }
                    // Older peers can legitimately lack ref.root. Preserve the M2 pending row
                    // rather than advertising a repair request which has no event-ID target.
                    ?: MapEventProcessingResult.Accepted(core.id)
                PendingMapActionPersistenceResult.ExactDuplicate -> throw TransactionAbort(
                    MapEventProcessingResult.InvalidPersistedState(core.id, "action_insert_ignored_after_new_durable_event"),
                )
                PendingMapActionPersistenceResult.ConflictingDuplicate -> throw TransactionAbort(
                    MapEventProcessingResult.InvalidPersistedState(core.id, "conflicting_action_after_new_durable_event"),
                )
                is PendingMapActionPersistenceResult.InvalidPersistedState -> throw TransactionAbort(
                    MapEventProcessingResult.InvalidPersistedState(core.id, result.detail),
                )
            }
        }
        pendingReplay.replayBeforeLiveAction(PendingReplayTarget(request.gid, interaction.itemType, targetId))
        // Replay may establish the earliest irreversible resolution cutoff and update projection.
        // Reload target-dependent context before classifying the live action.
        val context = loadDedicatedContext(request, interaction)
        return when (val dispatched = dispatcher.route(request.gid, core, null, context)) {
            is MapEventReductionDispatchResult.DedicatedAction -> when (val result = dispatched.result) {
                DedicatedMapActionReductionResult.AppliedInserted,
                DedicatedMapActionReductionResult.HistoricalAppliedInserted,
                -> MapEventProcessingResult.Accepted(core.id)
                DedicatedMapActionReductionResult.ExactDuplicate -> throw TransactionAbort(
                    MapEventProcessingResult.InvalidPersistedState(core.id, "action_insert_ignored_after_new_durable_event"),
                )
                DedicatedMapActionReductionResult.ConflictingDuplicate -> throw TransactionAbort(
                    MapEventProcessingResult.ConflictingDuplicate(core.id),
                )
                is DedicatedMapActionReductionResult.Rejected -> throw TransactionAbort(rejected(core.id, result.reason))
                is DedicatedMapActionReductionResult.InvalidPersistedState -> throw TransactionAbort(
                    MapEventProcessingResult.InvalidPersistedState(core.id, result.detail),
                )
                DedicatedMapActionReductionResult.DeferredToM2C2 -> throw IllegalStateException("dedicated action reducer is not enabled")
            }
            else -> throw IllegalStateException("dedicated action routed to wrong reducer")
        }
    }

    private fun classifyExistingDedicatedPair(
        request: MapEventProcessingRequest,
        interaction: MapItemInteractionEvent?,
    ): MapEventProcessingResult {
        // Durable duplicate identity is classified against immutable wire-derived action content;
        // current membership, deletion, windows, and projection state cannot change that identity.
        val storedEvent = db.eventsDao().findById(request.gid, request.core.id)
            ?: return MapEventProcessingResult.InvalidPersistedState(request.core.id, "durable_event_missing")
        val byEvent = db.mapItemActionDao().findByEventId(request.gid, storedEvent.event_id)
        val stored = byEvent ?: return MapEventProcessingResult.InvalidPersistedState(
            request.core.id, "durable_event_action_pair_missing_or_inconsistent",
    )

        val byAction = db.mapItemActionDao().findByActionId(request.gid, stored.action_id)
        when (val pair = DedicatedMapActionPersistedPairValidator.validate(
            request.gid, stored, byAction, byEvent, storedEvent,
        )) {
            PersistedActionPairValidationResult.Valid -> Unit
            is PersistedActionPairValidationResult.InvalidPersistedState ->
                return MapEventProcessingResult.InvalidPersistedState(request.core.id, pair.detail)
        }
        if (interaction == null) return MapEventProcessingResult.ConflictingDuplicate(request.core.id)
        val expected = when (val result = DefaultMapItemActionEntityFactory.create(
            MapItemActionPersistenceInput(request.gid, request.core, interaction),
            Sha256MapEventFingerprint.sha256(request.core),
            MapItemActionInitialState.PENDING_TARGET,
        )) {
            is PersistenceParseResult.Success -> result.value
            else -> return MapEventProcessingResult.ConflictingDuplicate(request.core.id)
        }
        if (stored.event_fingerprint != expected.event_fingerprint) return MapEventProcessingResult.ConflictingDuplicate(request.core.id)
        return if (stored.sameImmutableActionAs(expected)) MapEventProcessingResult.ExactDuplicate(request.core.id)
        else MapEventProcessingResult.InvalidPersistedState(request.core.id, "immutable_action_row_contradiction")
    }

    private fun validateLocalParticipation(request: MapEventProcessingRequest): MapEventProcessingResult? {
        val localActorDid = request.localActorDid?.takeIf { it.isNotBlank() } ?: return null
        if (GroupMembershipPolicy.shouldRejectForRemovedLocalMember(db, request.gid, request.core, localActorDid)) {
            return rejected(request.core.id, MapEventRejectionReason.LOCAL_MEMBER_REMOVED)
        }
        if (db.userProfileDao().find(request.gid, localActorDid) == null) {
            return rejected(request.core.id, MapEventRejectionReason.ACTOR_NOT_MEMBER)
        }
        return null
    }

    private fun loadDedicatedContext(
        request: MapEventProcessingRequest,
        interaction: MapItemInteractionEvent,
    ): DedicatedMapActionContext {
        val targetId = request.core.ref.target
            ?: throw TransactionAbort(rejected(request.core.id, MapEventRejectionReason.INVALID_REQUEST))
        val marker = db.mapDao().findMarker(request.gid, targetId)
        val area = db.mapDao().findArea(request.gid, targetId)
        if ((interaction.itemType == MapItemType.MARKER && area != null) ||
            (interaction.itemType == MapItemType.AREA && marker != null)
        ) throw TransactionAbort(rejected(request.core.id, MapEventRejectionReason.TARGET_TYPE_CONFLICT))
        val canonical = if (interaction.itemType == MapItemType.MARKER) marker else area
        if (canonical == null) throw TransactionAbort(rejected(request.core.id, MapEventRejectionReason.TARGET_NOT_FOUND))
        val deleted = marker?.status == "deleted" || area?.status == "deleted"
        if (deleted) throw TransactionAbort(rejected(request.core.id, MapEventRejectionReason.TARGET_DELETED))
        val state = db.mapItemDomainStateDao().find(request.gid, persisted(interaction.itemType), targetId)
            ?: throw TransactionAbort(MapEventProcessingResult.InvalidPersistedState(request.core.id, "missing_domain_state"))
        val snapshot = when (val parsed = MapItemEntityMappers.snapshot(state)) {
            is PersistenceParseResult.Success -> parsed.value
            else -> throw TransactionAbort(MapEventProcessingResult.InvalidPersistedState(request.core.id, parsed.toString()))
        }
        val canonicalOwner = marker?.actor_did ?: area!!.actor_did
        val canonicalExpires = marker?.expires_at ?: area?.expires_at
        if (!DedicatedMapActionTargetSnapshotValidator.isConsistent(
                request.gid, interaction.itemType, targetId, canonicalOwner,
                marker?.kind ?: area?.kind, canonicalExpires, snapshot,
            )) throw TransactionAbort(MapEventProcessingResult.InvalidPersistedState(request.core.id, "canonical_domain_state_mismatch"))
        val earliestResolution = db.mapItemActionDao()
            .findEarliestAppliedOfType(
                request.gid,
                persisted(interaction.itemType),
                targetId,
                app.peermesh.data.db.MapItemPersistenceValues.ActionType.UPDATE_STATUS,
            )
            ?.let { MapActionOrderKey(it.action_t, it.action_lt, it.actor_did, it.event_id) }
        return DedicatedMapActionContext(
            gid = request.gid,
            event = request.core,
            interaction = interaction,
            target = DedicatedMapActionTarget(true, false),
            snapshot = snapshot,
            localActorDid = request.localActorDid,
            earliestAppliedResolutionOrder = earliestResolution,
        )
    }

    private fun insertDomainState(request: MapEventProcessingRequest, identity: Pair<MapItemType, String>, canonicalCreation: Long, canonicalExpires: Long?) {
        val core = request.core
        val data = core.data as JsonObject
        val explicit = long(data, MapDomainPayloadContract.EXTENSION_VERSION_FIELD) == 1L
        val kind = string(data, "kind")
        val legacyKindSupported = when (identity.first) {
            MapItemType.MARKER -> MapItemKindCatalog.supportsMarker(kind)
            MapItemType.AREA -> MapItemKindCatalog.supportsArea(kind)
        }
        val category = if (explicit) {
            MapItemCategory.fromWireValue(string(data, MapDomainPayloadContract.CATEGORY_FIELD))
        } else {
            MapItemKindCatalog.categoryFor(kind).takeIf { legacyKindSupported }
        }
        val source = if (explicit) MapInformationSource.fromWireValue(string(data, MapDomainPayloadContract.SOURCE_FIELD)) else null
        val legacyResult = if (!explicit) LegacyMapItemWindowBackfill.derive(canonicalCreation, canonicalExpires) else null
        val legacyWindows = legacyResult?.windows
        if (!explicit && canonicalExpires != null && legacyWindows?.expiresAtSeconds == null) {
            throw IllegalStateException("invalid legacy temporal state")
        }
        val expiresAt = canonicalExpires
        val ownerEditUntil = if (explicit) long(data, "owner_edit_until") else legacyWindows?.ownerEditUntilSeconds
        val interactionUntil = if (explicit) long(data, "interaction_until") else legacyWindows?.interactionUntilSeconds
        val order = MapEventOrderingPolicy.orderKey(core)
        val entity = MapItemDomainStateEntity(
            request.gid, persisted(identity.first), identity.second, category?.wireValue,
            when { explicit -> MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1.persistedValue; category != null -> MapItemCategoryOrigin.LEGACY_KIND_DERIVED.persistedValue; else -> MapItemCategoryOrigin.UNRESOLVED.persistedValue },
            source?.wireValue, if (explicit) 1 else null,
            if (explicit) MapItemStateOrigin.EXPLICIT_DOMAIN_V1.persistedValue else MapItemStateOrigin.LEGACY_ROW_BACKFILL.persistedValue,
            core.actor.did, core.id, order.timestampSeconds, order.lamport, order.actorDid,
            if (category == MapItemCategory.SITUATION) MapSituationLifecycleStatus.ACTIVE.wireValue else null,
            expiresAt, ownerEditUntil, interactionUntil,
            when { explicit -> MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1.persistedValue; legacyResult != null -> legacyResult.temporalOrigin; category == null -> MapItemTemporalOrigin.LEGACY_UNRESOLVED.persistedValue; else -> MapItemTemporalOrigin.LEGACY_NO_EXPIRY.persistedValue },
            0, 0, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        )
        when (val result = db.mapItemDomainStateDao().insertIgnore(entity)) {
            is PersistenceWriteResult.Success -> if (result.value == -1L) throw IllegalStateException("domain state insert lost")
            is PersistenceWriteResult.Rejected -> throw IllegalStateException("invalid domain state: ${result.failure}")
        }
    }

    private fun sameWireEvent(left: EventEntity, right: EventEntity): Boolean =
        left.event_id == right.event_id && left.type == right.type && left.created_t == right.created_t &&
            left.time_lt == right.time_lt && left.actor_did == right.actor_did && left.ref_root == right.ref_root &&
            left.ref_parent == right.ref_parent && left.ref_target == right.ref_target && left.ttl_s == right.ttl_s &&
            sameCanonicalJson(left.payload_json, right.payload_json)

    private fun sameCanonicalJson(left: String, right: String): Boolean = try {
        CanonicalJson.canonicalize(CanonicalJson.json.parseToJsonElement(left)) ==
            CanonicalJson.canonicalize(CanonicalJson.json.parseToJsonElement(right))
    } catch (_: IllegalArgumentException) {
        left == right
    }

    private fun identity(core: app.peermesh.core.model.EventCore): Pair<MapItemType, String>? {
        val data = core.data as? JsonObject ?: return null
        val marker = core.type.contains("MARKER")
        val id = core.ref.target ?: string(data, if (marker) "marker_id" else "area_id") ?: core.id.takeIf { core.type.endsWith(".ADD") }
        return id?.let { (if (marker) MapItemType.MARKER else MapItemType.AREA) to it }
    }
    private fun string(data: JsonObject, key: String) = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun long(data: JsonObject, key: String) = (data[key] as? JsonPrimitive)?.content?.toLongOrNull()
    private fun persisted(type: MapItemType) = if (type == MapItemType.MARKER) "marker" else "area"
    private fun rejected(id: String, reason: MapEventRejectionReason) = MapEventProcessingResult.Rejected(id, reason)
    private fun reason(value: String) = MapEventRejectionReason.entries.firstOrNull { it.persistedValue == value } ?: MapEventRejectionReason.INVALID_PERSISTED_STATE
    private fun isCanonical(type: String) = type in setOf(EventTypes.MAP_MARKER_ADD, EventTypes.MAP_MARKER_UPD, EventTypes.MAP_MARKER_DEL, EventTypes.MAP_AREA_ADD, EventTypes.MAP_AREA_UPD, EventTypes.MAP_AREA_DEL)
    private fun isDedicated(type: String) = type in setOf(EventTypes.MAP_ITEM_CONFIRM, EventTypes.MAP_ITEM_SITUATION_UPDATE, EventTypes.MAP_ITEM_SITUATION_STATUS, EventTypes.MAP_ITEM_ACKNOWLEDGE, EventTypes.MAP_ITEM_NOTE)

    private companion object {
        const val LOCAL_SOURCE = "local"
        const val MAX_PENDING_PER_TARGET = 32
        const val MAX_PENDING_PER_GROUP = 512
        const val PENDING_RETENTION_SECONDS = 7L * 24L * 60L * 60L
        const val TERMINAL_RETENTION_SECONDS = 24L * 60L * 60L
    }

    private class TransactionAbort(val result: MapEventProcessingResult) : RuntimeException()
}
