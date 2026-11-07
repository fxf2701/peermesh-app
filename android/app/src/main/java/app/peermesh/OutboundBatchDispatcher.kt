package app.peermesh

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.peermesh.ble.BleMeshRuntime
import app.peermesh.ble.BlePermissionHelper
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.AppBatchEnvelope
import app.peermesh.core.model.AppBatchItemV2
import app.peermesh.core.model.DtnMeta
import app.peermesh.core.model.EventCore
import app.peermesh.core.ttl.TtlHelper
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.OutboundBatchEntity
import app.peermesh.data.db.entity.OutboundBatchEventEntity
import app.peermesh.data.db.entity.OutboundBatchLane
import app.peermesh.data.db.entity.OutboundBatchPeerDeliveryEntity
import app.peermesh.data.db.entity.OutboundBatchPeerDeliveryState
import app.peermesh.data.db.entity.OutboundBatchStatus
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.transport.DtnTransportPolicy
import app.peermesh.security.crypto.AppBatchCrypto
import app.peermesh.security.crypto.EnvelopeCrypto
import app.peermesh.security.crypto.SecureKeyStore
import app.peermesh.transport.ble.BleConfig
import app.peermesh.transport.adapter.MeshSendAdmissionResult
import app.peermesh.transport.adapter.MeshTrafficHint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object OutboundBatchDispatcher {
    private const val DISPATCH_LIMIT = 3
    private const val CLAIM_CANDIDATE_MULTIPLIER = 4
    private const val MAX_BATCH_RETRY_ATTEMPTS = 1
    private const val MAX_BROADCAST_REJECTED_ATTEMPTS = 10
    private const val MAX_PEER_RETRY_ATTEMPTS = 3
    private const val PROCESSING_STALE_MS = 30_000L
    private const val MAX_EVENTS_PER_BATCH = 5
    private const val MAX_BATCH_PAYLOAD_BYTES = 2_400
    private const val BROADCAST_REJECTED_RETRY_DELAY_MS = 30_000L
    private const val NO_TARGET_RETRY_DELAY_MS = 10_000L
    private const val NO_TARGET_RETRY_DELAY_STEP_2_MS = 30_000L
    private const val NO_TARGET_RETRY_DELAY_MAX_MS = 60_000L
    private const val DISPATCH_RETRY_DELAY_MS = OutboundBatchRetryPolicy.DEFAULT_RETRY_DELAY_MS
    private const val MAP_OUTBOUND_ENABLED = true
    private const val TRAIL_OUTBOUND_ENABLED = true
    private const val OTHER_OUTBOUND_ENABLED = true
    private const val SYS_METADATA_MAX_PEER_RETRY_ATTEMPTS = 1
    private const val MAP_BATCH_WINDOW_MS = 2_500L
    private const val OTHER_BATCH_WINDOW_MS = 5_000L
    private const val RECENT_BATCH_WINDOW_SEC = 15 * 60L
    private const val ROTATION_CATCHUP_WINDOW_MS = 5_000L
    private const val ROTATION_CATCHUP_BATCH_QUOTA = 1
    private const val SYS_ACK_DISPATCH_ENABLED = true

    private val worker = Executors.newSingleThreadExecutor(DispatcherThreadFactory())
    private val retryScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(DispatcherThreadFactory())
    private val lock = Any()
    @Volatile private var drainRunning = false
    @Volatile private var appContext: Context? = null
    @Volatile private var delayedRetryScheduled = false
    @Volatile private var delayedRetryReason = ""
    @Volatile private var delayedRetryAtMs = 0L
    @Volatile private var noTargetBackoffUntilMs = 0L
    @Volatile private var noTargetRetryDelayMs = NO_TARGET_RETRY_DELAY_MS
    @Volatile private var mapBatchWindowScheduled = false
    @Volatile private var otherBatchWindowScheduled = false
    private val rotationCatchupUntilByDeviceId = ConcurrentHashMap<String, Long>()
    private val rotationCatchupQuotaByDeviceId = ConcurrentHashMap<String, Int>()

    fun bindContext(context: Context) {
        appContext = context.applicationContext
    }

    fun enqueue(
        context: Context,
        gid: String,
        core: EventCore,
        source: String,
        targetDeviceIds: Set<String> = emptySet(),
    ): Boolean {
        val app = context.applicationContext
        appContext = app
        if (EventTypes.isMap(core.type) && !MAP_OUTBOUND_ENABLED) {
            ActivityLog.add(
                label = "APP batch transport skipped",
                source = source,
                message = "reason=map_outbound_disabled event_id=${core.id} type=${core.type}",
                gid = gid,
                eventId = core.id,
            )
            return true
        }
        if (EventTypes.isLiveTrail(core.type) && !TRAIL_OUTBOUND_ENABLED) {
            ActivityLog.add(
                label = "APP batch transport skipped",
                source = source,
                message = "reason=trail_outbound_disabled event_id=${core.id} type=${core.type}",
                gid = gid,
                eventId = core.id,
            )
            return true
        }
        if (isToggleableOtherOutboundType(core.type) && !OTHER_OUTBOUND_ENABLED) {
            ActivityLog.add(
                label = "APP batch transport skipped",
                source = source,
                message = "reason=other_outbound_disabled event_id=${core.id} type=${core.type}",
                gid = gid,
                eventId = core.id,
            )
            return true
        }
        val keyStore = SecureKeyStore(app)
        val groupKey = keyStore.loadGroupKey(gid) ?: run {
            ActivityLog.add(
                label = "APP batch transport skipped",
                source = source,
                message = "reason=group_key_missing event_id=${core.id}",
                gid = gid,
                eventId = core.id,
            )
            return false
        }
        val item = buildBatchItem(core, groupKey)
        val priority = DtnTransportPolicy.priorityForEventType(core.type)
        val now = System.currentTimeMillis()
        val db = AppDatabaseProvider.get(app)
        var enqueueResult: EnqueueResult? = null
        db.runInTransaction {
            enqueueResult = appendOrInsertBatch(
                db = db,
                gid = gid,
                source = source,
                priority = priority,
                core = core,
                item = item,
                now = now,
                targetDeviceIds = targetDeviceIds,
            )
            if (targetDeviceIds.isNotEmpty()) {
                targetDeviceIds.forEach { deviceId ->
                    db.outboundBatchDao().markPeerPending(enqueueResult!!.batchId, deviceId, now)
                }
            }
        }
        val result = enqueueResult ?: return false
        ActivityLog.add(
            label = "APP batch bypass",
            source = source,
            message = "path=persistent_outbound_queue mode=${result.mode} batch_id=${result.batchId} explicit_targets=${targetDeviceIds.size} event_ids=${result.eventIds.joinToString(",")}",
        )
        if (EventTypes.isPriorityControl(core.type)) {
            ActivityLog.add(
                label = "Membership control outbound enqueued",
                source = source,
                message = "gid=$gid type=${core.type} batch_id=${result.batchId} targets=${targetDeviceIds.size} event_id=${core.id}",
                gid = gid,
                eventId = core.id,
            )
        }
        val mesh = BleMeshRuntime.get(app)
        val nearbyCount = runCatching { mesh.nearbyDeviceCount() }.getOrDefault(0)
        if (nearbyCount > 0) {
            resetNoTargetBackoff()
            if (EventTypes.isMap(core.type)) {
                scheduleMapBatchWindow(app, reason = "map_enqueue_window")
            } else if (EventTypes.isLiveTrail(core.type)) {
                kick(app, reason = "trail_enqueue")
            } else if (EventTypes.isPriorityControl(core.type)) {
                kick(app, reason = "membership_control_enqueue")
            } else if (shouldWindowOther(core.type)) {
                scheduleOtherBatchWindow(app, reason = "other_enqueue_window")
            } else {
                kick(app, reason = "enqueue")
            }
        } else if (source != SysEventWriter.SOURCE_SYS_METADATA) {
            scheduleNoTargetRetry(app)
        }
        return true
    }

    fun onNearbyPeerDetected(context: Context) {
        SysEventWriter.syncActiveGroupMetadata(context.applicationContext, reason = "nearby_peer_detected")
        resetNoTargetBackoff()
        kick(context.applicationContext, reason = "nearby_peer_detected")
    }

    fun kick(context: Context, reason: String) {
        appContext = context.applicationContext
        delayedRetryScheduled = false
        delayedRetryReason = ""
        delayedRetryAtMs = 0L
        synchronized(lock) {
            if (drainRunning) return
            drainRunning = true
            worker.execute { drainLoop(reason) }
        }
    }

    fun onBatchTargetQueued(batchId: String, deviceId: String) {
        if (isFallbackAddressId(deviceId)) {
            ActivityLog.add(
                label = "APP batch target skipped",
                source = "mesh",
                message = "batch_id=$batchId device_id=$deviceId reason=fallback_address_id",
            )
            return
        }
        val app = appContext ?: return
        worker.execute {
            val db = AppDatabaseProvider.get(app)
            val now = System.currentTimeMillis()
            val batch = db.outboundBatchDao().findBatch(batchId) ?: return@execute
            db.runInTransaction {
                db.outboundBatchDao().markPeerQueued(batchId, deviceId, now)
                if (batch.status != OutboundBatchStatus.PROCESSING) {
                    db.outboundBatchDao().updateStatusForBatches(
                        batchIds = listOf(batchId),
                        status = OutboundBatchStatus.PROCESSING,
                        lockedAt = now,
                        updatedAt = now,
                    )
                }
            }
        }
    }

    fun onBatchTargetPending(batchId: String, deviceId: String) {
        if (isFallbackAddressId(deviceId)) {
            ActivityLog.add(
                label = "APP batch target skipped",
                source = "mesh",
                message = "batch_id=$batchId device_id=$deviceId reason=fallback_address_id",
            )
            return
        }
        val app = appContext ?: return
        worker.execute {
            val db = AppDatabaseProvider.get(app)
            val now = System.currentTimeMillis()
            val batch = db.outboundBatchDao().findBatch(batchId) ?: return@execute
            db.runInTransaction {
                db.outboundBatchDao().markPeerPending(batchId, deviceId, now)
                if (batch.status != OutboundBatchStatus.PROCESSING) {
                    db.outboundBatchDao().updateStatusForBatches(
                        batchIds = listOf(batchId),
                        status = OutboundBatchStatus.PROCESSING,
                        lockedAt = now,
                        updatedAt = now,
                    )
                }
            }
        }
    }

    fun onBatchSendComplete(batchId: String, deviceId: String) {
        if (isFallbackAddressId(deviceId)) {
            ActivityLog.add(
                label = "APP batch target skipped",
                source = "mesh",
                message = "batch_id=$batchId device_id=$deviceId reason=fallback_address_id_complete",
            )
            return
        }
        val app = appContext ?: return
        worker.execute {
            completeDispatchJob(app, batchId, deviceId)
            kick(app, reason = "batch_complete")
        }
    }

    fun claimNextDispatchJob(
        context: Context,
        deviceId: String? = null,
        allowAck: Boolean = true,
    ): DispatchJob? {
        val app = context.applicationContext
        appContext = app
        val now = System.currentTimeMillis()
        val db = AppDatabaseProvider.get(app)

        val dispatchableStatuses = listOf(
            OutboundBatchStatus.FAILED_RETRYABLE,
            OutboundBatchStatus.QUEUED,
        )

        val selected = db.outboundBatchDao().selectDispatchCandidatesByLane(
            statuses = dispatchableStatuses,
            lane = OutboundBatchLane.FRESH,
            limit = DISPATCH_LIMIT * CLAIM_CANDIDATE_MULTIPLIER,
        ).ifEmpty {
            db.outboundBatchDao().selectDispatchCandidatesByLane(
                statuses = dispatchableStatuses,
                lane = OutboundBatchLane.BACKLOG,
                limit = DISPATCH_LIMIT * CLAIM_CANDIDATE_MULTIPLIER,
            )
        }

        val ordered = selected
            .filter { batch -> SYS_ACK_DISPATCH_ENABLED || !batch.isAckBatch() }
            .dataBeforeOldestAck(allowAck)

        for (batch in ordered) {
            val dueTargets = db.outboundBatchDao()
                .listPeerDeviceIdsByStateDue(
                    batch.batch_id,
                    OutboundBatchPeerDeliveryState.FAILED_RETRYABLE,
                    now,
                )

            val pendingTargets = db.outboundBatchDao()
                .listPeerDeviceIdsByState(
                    batch.batch_id,
                    OutboundBatchPeerDeliveryState.PENDING,
                )

            val targetCandidates = LinkedHashSet<String>().apply {
                addAll(dueTargets)
                addAll(pendingTargets)
            }

            val chosenDeviceId = when {
                deviceId != null && targetCandidates.contains(deviceId) -> deviceId
                deviceId != null -> null
                else -> targetCandidates.firstOrNull()
            } ?: continue

            val job = buildDispatchJob(db, batch, chosenDeviceId)

            db.runInTransaction {
                db.outboundBatchDao().markPeerQueued(batch.batch_id, chosenDeviceId, now)
                if (batch.status != OutboundBatchStatus.PROCESSING) {
                    db.outboundBatchDao().updateStatusForBatches(
                        batchIds = listOf(batch.batch_id),
                        status = OutboundBatchStatus.PROCESSING,
                        lockedAt = now,
                        updatedAt = now,
                    )
                }
            }

            ActivityLog.add(
                label = "APP dispatch job claimed",
                source = batch.source,
                message = "batch_id=${job.batchId} device_id=${job.deviceId} lane=${job.lane} event_ids=${job.eventIds.joinToString(",")}",
                gid = batch.gid,
            )
            if (batch.isAckBatch()) {
                ActivityLog.add(
                    label = "ACK batch dispatch claimed",
                    source = batch.source,
                    message = "batch_id=${job.batchId} device_id=${job.deviceId} lane=${job.lane} event_ids=${job.eventIds.joinToString(",")}",
                    gid = batch.gid,
                )
            }

            return job
        }

        return null
    }

    fun onBatchSendFailed(batchId: String, deviceId: String, reason: String) {
        if (isFallbackAddressId(deviceId)) {
            ActivityLog.add(
                label = "APP batch target skipped",
                source = "mesh",
                message = "batch_id=$batchId device_id=$deviceId reason=fallback_address_id_failed original_reason=$reason",
            )
            return
        }
        val app = appContext ?: return
        worker.execute {
            val retryDelayMs = retryDelayForFailure(reason)
            failDispatchJob(
                context = app,
                batchId = batchId,
                deviceId = deviceId,
                reason = reason,
                retryDelayMs = retryDelayMs,
            )
            // Kick now in case another due job can use the lane, then guarantee
            // another wake at this failed peer's persisted next_retry_at.
            kick(app, reason = "batch_failed")
            scheduleRejectedRetry(
                context = app,
                reason = "batch_failed_retry",
                delayMs = retryDelayMs,
            )
            ActivityLog.add(
                label = "APP dispatch retry wake scheduled",
                source = "mesh",
                message = "batch_id=$batchId device_id=$deviceId delay_ms=$retryDelayMs failure=$reason",
            )
        }
    }

    private fun retryDelayForFailure(reason: String): Long =
        OutboundBatchRetryPolicy.retryDelayMs(reason)

    fun completeDispatchJob(
        context: Context,
        batchId: String,
        deviceId: String,
    ) {
        val app = context.applicationContext
        appContext = app
        val db = AppDatabaseProvider.get(app)
        val now = System.currentTimeMillis()
        val batch = db.outboundBatchDao().findBatch(batchId) ?: return

        db.runInTransaction {
            db.outboundBatchDao().markPeerDelivered(batchId, deviceId, now)
            reconcileBatchAfterPeerUpdate(db, batch, now, "dispatch_job_complete")
        }

        ActivityLog.add(
            label = "APP dispatch job completed",
            source = batch.source,
            message = "batch_id=$batchId device_id=$deviceId",
            gid = batch.gid,
        )
        if (batch.isAckBatch()) {
            ActivityLog.add(
                label = "ACK batch dispatch completed",
                source = batch.source,
                message = "batch_id=$batchId device_id=$deviceId",
                gid = batch.gid,
            )
        }
    }

    fun failDispatchJob(
        context: Context,
        batchId: String,
        deviceId: String,
        reason: String,
        retryDelayMs: Long = DISPATCH_RETRY_DELAY_MS,
    ) {
        val app = context.applicationContext
        appContext = app
        val db = AppDatabaseProvider.get(app)
        val now = System.currentTimeMillis()
        if (reason.contains("device_id_rotated")) {
            noteRotationCatchup(deviceId, now)
        }
        val batch = db.outboundBatchDao().findBatch(batchId) ?: return

        db.runInTransaction {
            db.outboundBatchDao().markPeerFailed(
                batchId = batchId,
                deviceId = deviceId,
                now = now,
                reason = reason,
                maxAttempts = maxPeerAttemptsFor(batch),
                retryDelayMs = retryDelayMs,
            )
            reconcileBatchAfterPeerUpdate(db, batch, now, "dispatch_job_failed")
        }

        ActivityLog.add(
            label = "APP dispatch job failed",
            source = batch.source,
            message = "batch_id=$batchId device_id=$deviceId retry_delay_ms=$retryDelayMs reason=$reason",
            gid = batch.gid,
        )
        if (batch.isAckBatch()) {
            ActivityLog.add(
                label = "ACK batch dispatch failed",
                source = batch.source,
                message = "batch_id=$batchId device_id=$deviceId retry_delay_ms=$retryDelayMs reason=$reason",
                gid = batch.gid,
            )
        }
    }

    private fun appendOrInsertBatch(
        db: app.peermesh.data.db.AppDatabase,
        gid: String,
        source: String,
        priority: Int,
        core: EventCore,
        item: PreparedBatchItem,
        now: Long,
        targetDeviceIds: Set<String>,
    ): EnqueueResult {
        val dao = db.outboundBatchDao()
        val candidate = dao.findLatestAppendCandidate(
            gid = gid,
            source = source,
            priority = priority,
            lane = OutboundBatchLane.FRESH,
        )
        if (
            targetDeviceIds.isEmpty() &&
            !EventTypes.isPriorityControl(core.type) &&
            candidate != null &&
            candidate.event_count < MAX_EVENTS_PER_BATCH
        ) {
            val existingEvents = dao.listBatchEvents(candidate.batch_id)
            val appended = buildReplacementBatch(candidate, existingEvents, gid, core, item, now)
            if (appended != null) {
                dao.replaceBatchGraph(candidate.batch_id, appended.batch, appended.events)
                return EnqueueResult(
                    batchId = appended.batch.batch_id,
                    eventIds = appended.prepared.eventIds,
                    mode = "append",
                )
            }
        }
        val prepared = buildBatchPayload(
            gid = gid,
            items = listOf(item),
            senderDeviceId = localSenderDeviceId(),
        )
        val batch = OutboundBatchEntity(
            batch_id = prepared.batchId,
            gid = gid,
            created_at = now,
            updated_at = now,
            status = OutboundBatchStatus.QUEUED,
            lane = OutboundBatchLane.FRESH,
            priority = priority,
            attempt_count = 0,
            last_error = null,
            locked_at = null,
            sent_at = null,
            payload = prepared.payload,
            event_count = 1,
            first_event_created_t = core.time.t,
            last_event_created_t = core.time.t,
            source = source,
        )
        val events = listOf(
            OutboundBatchEventEntity(
                batch_id = prepared.batchId,
                event_id = core.id,
                created_t = core.time.t,
                ordinal_in_batch = 0,
            )
        )
        dao.insertBatchGraph(batch, events)
        return EnqueueResult(batchId = prepared.batchId, eventIds = prepared.eventIds, mode = "new")
    }

    private fun buildReplacementBatch(
        candidate: OutboundBatchEntity,
        existingEvents: List<OutboundBatchEventEntity>,
        gid: String,
        core: EventCore,
        item: PreparedBatchItem,
        now: Long,
    ): ReplacementBatch? {
        val extracted = extractBatchItems(candidate.payload) ?: return null
        val senderDeviceId = extracted.senderDeviceId ?: localSenderDeviceId()
        val prepared = buildBatchPayloadOrNull(
            gid = gid,
            items = extracted.items + item,
            senderDeviceId = senderDeviceId,
            senderRelayId = extracted.senderRelayId ?: localSenderDeviceId(),
        ) ?: return null
        val updatedEvents = buildList(existingEvents.size + 1) {
            existingEvents.forEachIndexed { index, event ->
                add(event.copy(batch_id = prepared.batchId, ordinal_in_batch = index))
            }
            add(
                OutboundBatchEventEntity(
                    batch_id = prepared.batchId,
                    event_id = core.id,
                    created_t = core.time.t,
                    ordinal_in_batch = existingEvents.size,
                )
            )
        }
        val updatedBatch = candidate.copy(
            batch_id = prepared.batchId,
            updated_at = now,
            payload = prepared.payload,
            event_count = updatedEvents.size,
            last_event_created_t = core.time.t,
            lane = OutboundBatchLane.FRESH,
        )
        return ReplacementBatch(updatedBatch, updatedEvents, prepared)
    }

    private fun drainLoop(reason: String) {
        try {
            val app = appContext ?: return
            if (!isBleReady(app) || !hasBlePermissions(app)) return
            val db = AppDatabaseProvider.get(app)
            recoverStaleProcessing(db)
            val mesh = BleMeshRuntime.get(app)
            val nearbyBeforeClaim = runCatching { mesh.nearbyDeviceCount() }.getOrDefault(0)
            if (nearbyBeforeClaim > 0) {
                resetNoTargetBackoff()
            }
            val claimed = claimNextBatches(
                db = db,
                allowAck = !mesh.hasPendingDataTraffic(),
            )
            if (claimed.isEmpty()) {
                scheduleNextPeerRetryIfNeeded(app, db)
                return
            }
            for ((index, batch) in claimed.withIndex()) {
                if (!isBleReady(app) || !hasBlePermissions(app)) {
                    db.outboundBatchDao().markRetryable(
                        batchId = batch.batch_id,
                        status = OutboundBatchStatus.QUEUED,
                        lane = batch.lane,
                        updatedAt = System.currentTimeMillis(),
                        lastError = "ble_not_ready",
                        attemptCount = batch.attempt_count,
                    )
                    continue
                }
                val eventIds = db.outboundBatchDao().listBatchEvents(batch.batch_id).map { it.event_id }
                ActivityLog.add(
                    label = "APP batch emit",
                    source = batch.source,
                    message = "batch_id=${batch.batch_id} count=${batch.event_count} payload_bytes=${batch.payload.size} item_sizes= event_ids=${eventIds.joinToString(",")}",
                    gid = batch.gid,
                )
                ActivityLog.add(
                    label = "APP batch bypass",
                    source = batch.source,
                    message = "path=persistent_outbound_queue batch_id=${batch.batch_id} reason=$reason event_ids=${eventIds.joinToString(",")}",
                    gid = batch.gid,
                )
                val targetDeviceIds = dispatchTargetDeviceIds(db, batch.batch_id)
                val targetMode = if (targetDeviceIds.isEmpty()) "auto" else "explicit"

                ActivityLog.add(
                    label = "APP batch dispatch target select",
                    source = batch.source,
                    message = "batch_id=${batch.batch_id} mode=$targetMode targets=${targetDeviceIds.joinToString(",").ifBlank { "none" }}",
                    gid = batch.gid,
                )

                val admission = if (targetDeviceIds.isEmpty()) {
                    mesh.admitEnvelopeWithHint(batch.payload, MeshTrafficHint.DEFAULT)
                } else {
                    mesh.admitEnvelopeToDeviceIdsWithHint(batch.payload, targetDeviceIds, MeshTrafficHint.DEFAULT)
                }
                if (admission is MeshSendAdmissionResult.Enqueued) {
                    noTargetBackoffUntilMs = 0L
                    if (targetDeviceIds.isNotEmpty()) {
                        consumeRotationCatchupQuota(targetDeviceIds)
                    }
                    ActivityLog.add(
                        label = "APP batch transport queued",
                        source = batch.source,
                        message = "batch_id=${batch.batch_id} reason=$reason targets=${targetDeviceIds.joinToString(",").ifBlank { "auto" }} event_ids=${eventIds.joinToString(",")}",
                        gid = batch.gid,
                    )
                    logEventTransportOutcome(
                        source = batch.source,
                        gid = batch.gid,
                        eventIds = eventIds,
                        status = "sent",
                        detail = "reason=$reason targets=${targetDeviceIds.joinToString(",").ifBlank { "auto" }}",
                    )
                    if (batch.isAckBatch()) {
                        ActivityLog.add(
                            label = "ACK batch transport queued",
                            source = batch.source,
                            message = "batch_id=${batch.batch_id} reason=$reason targets=${targetDeviceIds.joinToString(",").ifBlank { "auto" }} event_ids=${eventIds.joinToString(",")}",
                            gid = batch.gid,
                        )
                    }
                } else if (admission is MeshSendAdmissionResult.DeferredForeground) {
                    val deferredAt = System.currentTimeMillis()
                    db.outboundBatchDao().markRetryable(
                        batchId = batch.batch_id,
                        status = OutboundBatchStatus.QUEUED,
                        lane = OutboundBatchLane.BACKLOG,
                        updatedAt = deferredAt,
                        lastError = "nearby_foreground",
                        attemptCount = batch.attempt_count,
                    )
                    ActivityLog.add(
                        label = "APP batch dispatch deferred",
                        source = batch.source,
                        message = "batch_id=${batch.batch_id} reason=nearby_foreground source_retained=true retry_consumed=false future_resume_path=true event_ids=${eventIds.joinToString(",")}",
                        gid = batch.gid,
                    )
                    releaseClaimedRemainder(db, claimed.drop(index + 1))
                    return
                } else {
                    val failedAt = System.currentTimeMillis()
                    val nextAttemptCount = batch.attempt_count + 1
                    val rejectRetryExhausted =
                        !batch.isSysMetadataBatch() &&
                            nextAttemptCount >= MAX_BROADCAST_REJECTED_ATTEMPTS
                    if (batch.isSysMetadataBatch() || rejectRetryExhausted) {
                        db.outboundBatchDao().markFinal(
                            batchId = batch.batch_id,
                            status = OutboundBatchStatus.FAILED_FINAL,
                            updatedAt = failedAt,
                            lastError = "broadcast_rejected",
                            attemptCount = nextAttemptCount,
                        )
                    } else {
                        db.outboundBatchDao().markRetryable(
                            batchId = batch.batch_id,
                            status = OutboundBatchStatus.QUEUED,
                            lane = OutboundBatchLane.BACKLOG,
                            updatedAt = failedAt,
                            lastError = "broadcast_rejected",
                            attemptCount = nextAttemptCount,
                        )
                    }
                    ActivityLog.add(
                        label = "APP batch transport skipped",
                        source = batch.source,
                        message = "batch_id=${batch.batch_id} reason=$reason skip_reason=broadcast_rejected event_ids=${eventIds.joinToString(",")}",
                        gid = batch.gid,
                    )
                    val fanoutTarget = if (targetDeviceIds.isEmpty()) -1 else targetDeviceIds.size
                    ActivityLog.add(
                        label = "BROADCAST_REJECT_DECISION",
                        source = batch.source,
                        message =
                            "batch_id=${batch.batch_id} event_count=${batch.event_count} fanout_sent=0 fanout_target=$fanoutTarget attempt=$nextAttemptCount decision=${
                                when {
                                    batch.isSysMetadataBatch() -> "drop_no_retry"
                                    rejectRetryExhausted -> "drop_retry_exhausted"
                                    else -> "requeue"
                                }
                            }",
                        gid = batch.gid,
                    )
                    if (batch.isSysMetadataBatch() || rejectRetryExhausted) {
                        logEventTransportOutcome(
                            source = batch.source,
                            gid = batch.gid,
                            eventIds = eventIds,
                            status = "final_failed",
                            detail = "reason=broadcast_rejected attempt=$nextAttemptCount",
                        )
                        continue
                    }
                    val nearbyCount = runCatching { mesh.nearbyDeviceCount() }.getOrDefault(0)
                    if (nearbyCount <= 0) {
                        scheduleNoTargetRetry(app)
                    } else {
                        resetNoTargetBackoff()
                        scheduleRejectedRetry(
                            context = app,
                            reason = "broadcast_rejected",
                            delayMs = BROADCAST_REJECTED_RETRY_DELAY_MS,
                        )
                    }
                    if (reason == "peer_retry_due") {
                        // Do not abort the whole drain pass on a retry-lane reject.
                        // Continue to process the remaining claimed batches for salvage.
                        continue
                    }
                    releaseClaimedRemainder(db, claimed.drop(index + 1))
                    return
                }
            }
        } finally {
            synchronized(lock) {
                drainRunning = false
            }
        }
    }

    private fun claimNextBatches(
        db: app.peermesh.data.db.AppDatabase,
        allowAck: Boolean,
    ): List<OutboundBatchEntity> {
        val now = System.currentTimeMillis()
        val recentCutoffSec = (now / 1000L) - RECENT_BATCH_WINDOW_SEC
        var claimed = emptyList<OutboundBatchEntity>()
        db.runInTransaction {
            val dispatchableStatuses = listOf(OutboundBatchStatus.FAILED_RETRYABLE, OutboundBatchStatus.QUEUED)
            val activeStatuses = listOf(
                OutboundBatchStatus.FAILED_RETRYABLE,
                OutboundBatchStatus.QUEUED,
                OutboundBatchStatus.PROCESSING,
            )
            val hasUnflushedRecentBatches = db.outboundBatchDao()
                .countActiveBatchesCreatedAfter(
                    statuses = activeStatuses,
                    createdAfterInclusive = recentCutoffSec,
                ) > 0
            val catchupBatch = findRotationCatchupBatch(
                db = db,
                statuses = dispatchableStatuses,
                now = now,
                minCreatedSec = if (hasUnflushedRecentBatches) recentCutoffSec else null,
            )
            val freshSelected = db.outboundBatchDao().selectDispatchCandidatesByLane(
                statuses = dispatchableStatuses,
                lane = OutboundBatchLane.FRESH,
                limit = DISPATCH_LIMIT * CLAIM_CANDIDATE_MULTIPLIER,
            )
            val backlogSelected = db.outboundBatchDao().selectDispatchCandidatesByLane(
                    statuses = dispatchableStatuses,
                    lane = OutboundBatchLane.BACKLOG,
                    limit = DISPATCH_LIMIT * CLAIM_CANDIDATE_MULTIPLIER,
                )
                .filter { batch ->
                    !hasUnflushedRecentBatches || batch.last_event_created_t >= recentCutoffSec
                }
            val selected = freshSelected + backlogSelected
            val ordered = buildList {
                if (catchupBatch != null) add(catchupBatch)
                addAll(selected)
            }
            val eligibleBatches = ordered
                .distinctBy { it.batch_id }
                .filter { batch -> isBatchReadyForDispatch(db, batch.batch_id, now) }
            val nonAckEligible = eligibleBatches.filterNot { it.isAckBatch() }
            val ackEligible = if (SYS_ACK_DISPATCH_ENABLED && allowAck) {
                eligibleBatches.filter { it.isAckBatch() }.oldestAckFirst()
            } else {
                emptyList()
            }
            val hasAckInTransport = db.outboundBatchDao().countBatchesByStatusAndPriority(
                status = OutboundBatchStatus.PROCESSING,
                priority = DtnTransportPolicy.PRIORITY_ACK_BACKGROUND,
            ) > 0
            val hasDataInTransport = db.outboundBatchDao().countBatchesByStatusExcludingPriority(
                status = OutboundBatchStatus.PROCESSING,
                priority = DtnTransportPolicy.PRIORITY_ACK_BACKGROUND,
            ) > 0
            val claimedBatches = run {
                val candidates = if (nonAckEligible.isNotEmpty()) {
                    nonAckEligible
                } else if (!allowAck || hasAckInTransport || hasDataInTransport) {
                    emptyList()
                } else {
                    ackEligible.take(1)
                }
                val baseline = reserveChatSlotIfMapDominates(candidates.take(DISPATCH_LIMIT), candidates)
                if (baseline.isEmpty()) {
                    baseline
                } else {
                    val hasQueuedInBaseline = baseline.any { it.status == OutboundBatchStatus.QUEUED }
                    if (hasQueuedInBaseline) {
                        baseline
                    } else {
                        val queuedCandidate = candidates.firstOrNull { it.status == OutboundBatchStatus.QUEUED }
                        if (queuedCandidate == null) {
                            baseline
                        } else {
                            val adjusted = baseline.toMutableList()
                            adjusted[adjusted.lastIndex] = queuedCandidate
                            ActivityLog.add(
                                label = "APP dispatch fairness reserve",
                                source = "mesh",
                                message =
                                    "replace_last_with_queued=true queued_batch_id=${queuedCandidate.batch_id} dropped_batch_id=${baseline.last().batch_id} limit=$DISPATCH_LIMIT",
                            )
                            adjusted.distinctBy { it.batch_id }.take(DISPATCH_LIMIT)
                        }
                    }
                }
            }
            if (claimedBatches.isEmpty()) {
                claimed = emptyList()
                return@runInTransaction
            }
            db.outboundBatchDao().updateStatusForBatches(
                batchIds = claimedBatches.map { it.batch_id },
                status = OutboundBatchStatus.PROCESSING,
                lockedAt = now,
                updatedAt = now,
            )
            claimed = claimedBatches.map { it.copy(status = OutboundBatchStatus.PROCESSING, locked_at = now, updated_at = now) }
        }
        return claimed
    }

    private fun OutboundBatchEntity.isAckBatch(): Boolean {
        return priority == DtnTransportPolicy.PRIORITY_ACK_BACKGROUND
    }

    private fun OutboundBatchEntity.isSysMetadataBatch(): Boolean {
        return source == SysEventWriter.SOURCE_SYS_METADATA
    }

    private fun maxPeerAttemptsFor(batch: OutboundBatchEntity): Int {
        return if (batch.isSysMetadataBatch()) {
            SYS_METADATA_MAX_PEER_RETRY_ATTEMPTS
        } else {
            MAX_PEER_RETRY_ATTEMPTS
        }
    }

    private fun OutboundBatchEntity.isMapBatch(): Boolean {
        return priority == DtnTransportPolicy.PRIORITY_MAP
    }

    private fun OutboundBatchEntity.isChatBatch(): Boolean {
        return priority == DtnTransportPolicy.PRIORITY_CHAT
    }

    private fun isOtherOutboundType(type: String): Boolean {
        return type != EventTypes.SYS_ACK &&
            !EventTypes.isPriorityControl(type) &&
            !EventTypes.isChat(type) &&
            !EventTypes.isMap(type) &&
            !EventTypes.isLiveTrail(type)
    }

    private fun shouldWindowOther(type: String): Boolean {
        return isToggleableOtherOutboundType(type)
    }

    private fun isToggleableOtherOutboundType(type: String): Boolean {
        if (!isOtherOutboundType(type)) return false
        if (type.startsWith("SYS.ALERT") || type.startsWith("SYS.SAFETY")) return false
        return true
    }

    private fun isFallbackAddressId(deviceId: String): Boolean {
        return deviceId.startsWith("addr:")
    }

    private fun reserveChatSlotIfMapDominates(
        baseline: List<OutboundBatchEntity>,
        candidates: List<OutboundBatchEntity>,
    ): List<OutboundBatchEntity> {
        if (baseline.isEmpty()) return baseline
        if (baseline.any { it.isChatBatch() }) return baseline
        val replacementIndex = baseline.indexOfLast { it.isMapBatch() }
        if (replacementIndex < 0) return baseline
        val chatCandidate = candidates.firstOrNull { candidate ->
            candidate.isChatBatch() && baseline.none { it.batch_id == candidate.batch_id }
        } ?: return baseline
        val adjusted = baseline.toMutableList()
        val dropped = adjusted[replacementIndex]
        adjusted[replacementIndex] = chatCandidate
        ActivityLog.add(
            label = "APP dispatch fairness reserve",
            source = "mesh",
            message =
                "replace_map_with_chat=true chat_batch_id=${chatCandidate.batch_id} dropped_batch_id=${dropped.batch_id} limit=$DISPATCH_LIMIT",
        )
        return adjusted.distinctBy { it.batch_id }.take(DISPATCH_LIMIT)
    }

    private fun List<OutboundBatchEntity>.oldestAckFirst(): List<OutboundBatchEntity> {
        return sortedWith(compareBy<OutboundBatchEntity> { it.created_at }.thenBy { it.batch_id })
    }

    private fun List<OutboundBatchEntity>.dataBeforeOldestAck(allowAck: Boolean): List<OutboundBatchEntity> {
        val data = filterNot { it.isAckBatch() }
        if (data.isNotEmpty() || !allowAck) return data
        return filter { it.isAckBatch() }.sortedWith(
            compareBy<OutboundBatchEntity> { it.created_at }
                .thenBy { it.batch_id },
        )
    }

    private fun buildDispatchJob(
        db: app.peermesh.data.db.AppDatabase,
        batch: OutboundBatchEntity,
        deviceId: String,
    ): DispatchJob {
        val eventIds = db.outboundBatchDao().listBatchEvents(batch.batch_id).map { it.event_id }
        return DispatchJob(
            batchId = batch.batch_id,
            deviceId = deviceId,
            payload = batch.payload,
            eventIds = eventIds,
            source = batch.source,
            gid = batch.gid,
            lane = batch.lane,
            attemptCount = batch.attempt_count,
        )
    }

    private fun dispatchTargetDeviceIds(
        db: app.peermesh.data.db.AppDatabase,
        batchId: String,
    ): Set<String> {
        val now = System.currentTimeMillis()

        val retryableDue = db.outboundBatchDao()
            .listPeerDeviceIdsByStateDue(
                batchId,
                OutboundBatchPeerDeliveryState.FAILED_RETRYABLE,
                now,
            )

        val pending = db.outboundBatchDao()
            .listPeerDeviceIdsByState(
                batchId,
                OutboundBatchPeerDeliveryState.PENDING,
            )

        val fallbackIds = (retryableDue + pending).filter { isFallbackAddressId(it) }.distinct()
        fallbackIds.forEach { fallbackId ->
            db.outboundBatchDao().deletePeerDelivery(batchId, fallbackId)
            ActivityLog.add(
                label = "APP batch target dropped",
                source = "mesh",
                message = "batch_id=$batchId device_id=$fallbackId reason=fallback_address_id",
            )
        }

        return LinkedHashSet<String>().apply {
            addAll(retryableDue.filterNot { isFallbackAddressId(it) })
            addAll(pending.filterNot { isFallbackAddressId(it) })
        }
    }

    private fun isBatchReadyForDispatch(
        db: app.peermesh.data.db.AppDatabase,
        batchId: String,
        now: Long,
    ): Boolean {
        val pendingCount = db.outboundBatchDao()
            .countPeerDeliveriesByState(batchId, OutboundBatchPeerDeliveryState.PENDING)
        if (pendingCount > 0) return true

        val retryableCount = db.outboundBatchDao()
            .countPeerDeliveriesByState(batchId, OutboundBatchPeerDeliveryState.FAILED_RETRYABLE)
        if (retryableCount <= 0) return true

        val dueTargets = db.outboundBatchDao()
            .listPeerDeviceIdsByStateDue(
                batchId,
                OutboundBatchPeerDeliveryState.FAILED_RETRYABLE,
                now,
            )
        return dueTargets.isNotEmpty()
    }

    private fun findRotationCatchupBatch(
        db: app.peermesh.data.db.AppDatabase,
        statuses: List<String>,
        now: Long,
        minCreatedSec: Long?,
    ): OutboundBatchEntity? {
        val activeDeviceIds = activeRotationCatchupDeviceIds(now)
        if (activeDeviceIds.isEmpty()) return null
        val backlogCandidates = db.outboundBatchDao().selectDispatchCandidatesByLane(
            statuses = statuses,
            lane = OutboundBatchLane.BACKLOG,
            limit = DISPATCH_LIMIT * CLAIM_CANDIDATE_MULTIPLIER,
        )
            .filter { batch ->
                minCreatedSec == null || batch.last_event_created_t >= minCreatedSec
            }
        activeDeviceIds.forEach { deviceId ->
            val match = backlogCandidates.firstOrNull { batch ->
                db.outboundBatchDao()
                    .listPeerDeviceIdsByStateDue(batch.batch_id, OutboundBatchPeerDeliveryState.FAILED_RETRYABLE, now)
                    .contains(deviceId)
            }
            if (match != null) return match
        }
        return null
    }

    private fun activeRotationCatchupDeviceIds(now: Long): List<String> {
        val active = mutableListOf<Pair<String, Long>>()
        rotationCatchupUntilByDeviceId.entries.forEach { (deviceId, until) ->
            val quota = rotationCatchupQuotaByDeviceId[deviceId] ?: 0
            if (quota <= 0 || now >= until) {
                rotationCatchupUntilByDeviceId.remove(deviceId)
                rotationCatchupQuotaByDeviceId.remove(deviceId)
            } else {
                active += deviceId to until
            }
        }
        return active.sortedByDescending { it.second }.map { it.first }
    }

    private fun noteRotationCatchup(deviceId: String, now: Long) {
        rotationCatchupUntilByDeviceId[deviceId] = now + ROTATION_CATCHUP_WINDOW_MS
        rotationCatchupQuotaByDeviceId[deviceId] = ROTATION_CATCHUP_BATCH_QUOTA
    }

    private fun consumeRotationCatchupQuota(targetDeviceIds: Set<String>) {
        targetDeviceIds.forEach { deviceId ->
            val remaining = rotationCatchupQuotaByDeviceId[deviceId] ?: return@forEach
            if (remaining <= 1) {
                rotationCatchupQuotaByDeviceId.remove(deviceId)
                rotationCatchupUntilByDeviceId.remove(deviceId)
            } else {
                rotationCatchupQuotaByDeviceId[deviceId] = remaining - 1
            }
        }
    }

    private fun scheduleNextPeerRetryIfNeeded(
        context: Context,
        db: app.peermesh.data.db.AppDatabase,
    ) {
        val now = System.currentTimeMillis()
        val nextRetryAt = db.outboundBatchDao().findEarliestPeerRetryAt() ?: return
        val delayMs = (nextRetryAt - now).coerceAtLeast(500L)
        scheduleRejectedRetry(
            context = context,
            reason = "peer_retry_due",
            delayMs = delayMs,
        )
    }

    private fun releaseClaimedRemainder(
        db: app.peermesh.data.db.AppDatabase,
        remaining: List<OutboundBatchEntity>,
    ) {
        if (remaining.isEmpty()) return
        db.outboundBatchDao().releaseBatches(
            batchIds = remaining.map { it.batch_id },
            status = OutboundBatchStatus.QUEUED,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun reconcileBatchAfterPeerUpdate(
        db: app.peermesh.data.db.AppDatabase,
        batch: OutboundBatchEntity,
        now: Long,
        reason: String,
    ) {
        val peerStates = db.outboundBatchDao().listPeerDeliveries(batch.batch_id)
        if (peerStates.isEmpty()) return
        val inFlight = peerStates.any { it.state == OutboundBatchPeerDeliveryState.IN_FLIGHT }
        val pending = peerStates.any { it.state == OutboundBatchPeerDeliveryState.PENDING }
        val retryable = peerStates.any { it.state == OutboundBatchPeerDeliveryState.FAILED_RETRYABLE }
        val deliveredCount = peerStates.count { it.state == OutboundBatchPeerDeliveryState.DELIVERED }
        val allDelivered = deliveredCount == peerStates.size
        if (inFlight) return

        // For user/app batches, one successful peer delivery is enough to get the
        // batch into the mesh. Keeping source-side peer retries alive after that
        // mostly creates redundant second-hop attempts and local timeout noise.
        if (deliveredCount > 0 && !batch.isSysMetadataBatch()) {
            val eventIds = db.outboundBatchDao().listBatchEvents(batch.batch_id).map { it.event_id }
            db.outboundBatchDao().markSent(batch.batch_id, OutboundBatchStatus.SENT, now, now)
            db.outboundBatchDao().deleteBatch(batch.batch_id)
            ActivityLog.add(
                label = "APP batch transport queued",
                source = batch.source,
                message = "batch_id=${batch.batch_id} reason=first_peer_delivered event_count=${batch.event_count}",
                gid = batch.gid,
            )
            logEventTransportOutcome(
                source = batch.source,
                gid = batch.gid,
                eventIds = eventIds,
                status = "first_peer_delivered",
                detail = "delivered=$deliveredCount pending=${peerStates.count { it.state == OutboundBatchPeerDeliveryState.PENDING }} retryable=${peerStates.count { it.state == OutboundBatchPeerDeliveryState.FAILED_RETRYABLE }}",
            )
            if (batch.isAckBatch()) {
                ActivityLog.add(
                    label = "ACK batch reconcile",
                    source = batch.source,
                    message = "batch_id=${batch.batch_id} state=sent reason=first_peer_delivered delivered=$deliveredCount pending=${peerStates.count { it.state == OutboundBatchPeerDeliveryState.PENDING }} retryable=${peerStates.count { it.state == OutboundBatchPeerDeliveryState.FAILED_RETRYABLE }}",
                    gid = batch.gid,
                )
            }
            return
        }

        if (allDelivered) {
            val eventIds = db.outboundBatchDao().listBatchEvents(batch.batch_id).map { it.event_id }
            db.outboundBatchDao().markSent(batch.batch_id, OutboundBatchStatus.SENT, now, now)
            db.outboundBatchDao().deleteBatch(batch.batch_id)
            ActivityLog.add(
                label = "APP batch transport queued",
                source = batch.source,
                message = "batch_id=${batch.batch_id} reason=transport_complete event_count=${batch.event_count}",
                gid = batch.gid,
            )
            logEventTransportOutcome(
                source = batch.source,
                gid = batch.gid,
                eventIds = eventIds,
                status = "sent",
                detail = "reason=transport_complete delivered=$deliveredCount",
            )
            if (batch.isAckBatch()) {
                ActivityLog.add(
                    label = "ACK batch reconcile",
                    source = batch.source,
                    message = "batch_id=${batch.batch_id} state=sent reason=transport_complete delivered=$deliveredCount pending=0 retryable=0",
                    gid = batch.gid,
                )
            }
            return
        }

        if (batch.isSysMetadataBatch()) {
            db.outboundBatchDao().markFinal(
                batchId = batch.batch_id,
                status = OutboundBatchStatus.FAILED_FINAL,
                updatedAt = now,
                lastError = reason,
                attemptCount = batch.attempt_count,
            )
            logEventTransportOutcome(
                source = batch.source,
                gid = batch.gid,
                eventIds = db.outboundBatchDao().listBatchEvents(batch.batch_id).map { it.event_id },
                status = "final_failed",
                detail = "reason=$reason delivered=$deliveredCount",
            )
            if (batch.isAckBatch()) {
                ActivityLog.add(
                    label = "ACK batch reconcile",
                    source = batch.source,
                    message = "batch_id=${batch.batch_id} state=failed_final reason=$reason delivered=$deliveredCount pending=${peerStates.count { it.state == OutboundBatchPeerDeliveryState.PENDING }} retryable=${peerStates.count { it.state == OutboundBatchPeerDeliveryState.FAILED_RETRYABLE }}",
                    gid = batch.gid,
                )
            }
            return
        }

        if (pending || retryable) {
            db.outboundBatchDao().markRetryable(
                batchId = batch.batch_id,
                status = OutboundBatchStatus.QUEUED,
                lane = OutboundBatchLane.BACKLOG,
                updatedAt = now,
                lastError = reason,
                attemptCount = batch.attempt_count,
            )
            if (batch.isAckBatch()) {
                ActivityLog.add(
                    label = "ACK batch reconcile",
                    source = batch.source,
                    message = "batch_id=${batch.batch_id} state=retryable reason=$reason delivered=$deliveredCount pending=${peerStates.count { it.state == OutboundBatchPeerDeliveryState.PENDING }} retryable=${peerStates.count { it.state == OutboundBatchPeerDeliveryState.FAILED_RETRYABLE }}",
                    gid = batch.gid,
                )
            }
            return
        }

        db.outboundBatchDao().markFinal(
            batchId = batch.batch_id,
            status = OutboundBatchStatus.FAILED_FINAL,
            updatedAt = now,
            lastError = reason,
            attemptCount = batch.attempt_count,
        )
        logEventTransportOutcome(
            source = batch.source,
            gid = batch.gid,
            eventIds = db.outboundBatchDao().listBatchEvents(batch.batch_id).map { it.event_id },
            status = "final_failed",
            detail = "reason=$reason delivered=$deliveredCount pending=0 retryable=0",
        )
        if (batch.isAckBatch()) {
            ActivityLog.add(
                label = "ACK batch reconcile",
                source = batch.source,
                message = "batch_id=${batch.batch_id} state=failed_final reason=$reason delivered=$deliveredCount pending=0 retryable=0",
                gid = batch.gid,
            )
        }
    }

    private fun recoverStaleProcessing(db: app.peermesh.data.db.AppDatabase) {
        val now = System.currentTimeMillis()
        val stale = db.outboundBatchDao().findStaleProcessing(lockedBefore = now - PROCESSING_STALE_MS)
        stale.forEach { batch ->
            val eventIds = db.outboundBatchDao().listBatchEvents(batch.batch_id).map { it.event_id }
            db.outboundBatchDao().markInflightPeersStale(
                batchId = batch.batch_id,
                now = now,
                reason = "processing_timeout",
                maxAttempts = maxPeerAttemptsFor(batch),
                retryDelayMs = BROADCAST_REJECTED_RETRY_DELAY_MS,
            )
            if (batch.isSysMetadataBatch()) {
                db.outboundBatchDao().markFinal(
                    batchId = batch.batch_id,
                    status = OutboundBatchStatus.FAILED_FINAL,
                    updatedAt = now,
                    lastError = "processing_timeout",
                    attemptCount = batch.attempt_count + 1,
                )
                logEventTransportOutcome(
                    source = batch.source,
                    gid = batch.gid,
                    eventIds = eventIds,
                    status = "final_failed",
                    detail = "reason=processing_timeout attempt=${batch.attempt_count + 1}",
                )
                return@forEach
            }
            if (batch.attempt_count < MAX_BATCH_RETRY_ATTEMPTS) {
                db.outboundBatchDao().markRetryable(
                    batchId = batch.batch_id,
                    status = OutboundBatchStatus.FAILED_RETRYABLE,
                    lane = OutboundBatchLane.BACKLOG,
                    updatedAt = now,
                    lastError = "processing_timeout",
                    attemptCount = batch.attempt_count + 1,
                )
            } else {
                db.outboundBatchDao().markFinal(
                    batchId = batch.batch_id,
                    status = OutboundBatchStatus.FAILED_FINAL,
                    updatedAt = now,
                    lastError = "processing_timeout",
                    attemptCount = batch.attempt_count + 1,
                )
                logEventTransportOutcome(
                    source = batch.source,
                    gid = batch.gid,
                    eventIds = eventIds,
                    status = "final_failed",
                    detail = "reason=processing_timeout attempt=${batch.attempt_count + 1}",
                )
                db.outboundBatchDao().deleteBatch(batch.batch_id)
            }
        }
    }

    private fun scheduleRejectedRetry(context: Context, reason: String, delayMs: Long) {
        val boundedDelayMs = delayMs.coerceAtLeast(500L)
        val now = System.currentTimeMillis()
        val scheduledAt = now + boundedDelayMs
        if (delayedRetryScheduled) {
            if (reason == delayedRetryReason && scheduledAt >= delayedRetryAtMs) return
            if (delayedRetryAtMs <= scheduledAt) return
        }
        delayedRetryScheduled = true
        delayedRetryReason = reason
        delayedRetryAtMs = scheduledAt
        retryScheduler.schedule(
            {
                val app = context.applicationContext
                delayedRetryScheduled = false
                delayedRetryReason = ""
                delayedRetryAtMs = 0L
                kick(app, reason)
            },
            boundedDelayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun logEventTransportOutcome(
        source: String,
        gid: String,
        eventIds: List<String>,
        status: String,
        detail: String,
    ) {
        eventIds.distinct().forEach { eventId ->
            ActivityLog.add(
                label = "Event transport outcome",
                source = source,
                message = "status=$status $detail",
                eventId = eventId,
                gid = gid,
            )
        }
    }

    private fun scheduleNoTargetRetry(context: Context) {
        if (delayedRetryScheduled && delayedRetryReason == "no_target_backoff") return
        val now = System.currentTimeMillis()
        val remaining = noTargetBackoffUntilMs - now
        val delayMs = if (remaining > 0L) remaining else noTargetRetryDelayMs
        noTargetBackoffUntilMs = now + delayMs
        noTargetRetryDelayMs = when (noTargetRetryDelayMs) {
            NO_TARGET_RETRY_DELAY_MS -> NO_TARGET_RETRY_DELAY_STEP_2_MS
            else -> NO_TARGET_RETRY_DELAY_MAX_MS
        }
        ActivityLog.add(
            label = "APP no-target backoff",
            source = "mesh",
            message = "delay_ms=$delayMs next_delay_ms=$noTargetRetryDelayMs",
        )
        scheduleRejectedRetry(
            context = context,
            reason = "no_target_backoff",
            delayMs = delayMs,
        )
    }

    private fun resetNoTargetBackoff() {
        noTargetBackoffUntilMs = 0L
        noTargetRetryDelayMs = NO_TARGET_RETRY_DELAY_MS
    }

    private fun scheduleMapBatchWindow(context: Context, reason: String) {
        if (mapBatchWindowScheduled) return
        mapBatchWindowScheduled = true
        ActivityLog.add(
            label = "APP MAP batch window armed",
            source = "mesh",
            message = "delay_ms=$MAP_BATCH_WINDOW_MS reason=$reason",
        )
        retryScheduler.schedule(
            {
                mapBatchWindowScheduled = false
                kick(context.applicationContext, reason)
            },
            MAP_BATCH_WINDOW_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleOtherBatchWindow(context: Context, reason: String) {
        if (otherBatchWindowScheduled) return
        otherBatchWindowScheduled = true
        ActivityLog.add(
            label = "APP OTHER batch window armed",
            source = "mesh",
            message = "delay_ms=$OTHER_BATCH_WINDOW_MS reason=$reason",
        )
        retryScheduler.schedule(
            {
                otherBatchWindowScheduled = false
                kick(context.applicationContext, reason)
            },
            OTHER_BATCH_WINDOW_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun buildBatchItem(core: EventCore, groupKey: ByteArray): PreparedBatchItem {
        val ttlSeconds = transportTtlSeconds(core)
        val dtnMeta = DtnMeta(
            ttl_seconds = ttlSeconds,
            hop_count = 0,
            max_hops = DtnTransportPolicy.DEFAULT_MAX_HOPS,
            priority = DtnTransportPolicy.priorityForEventType(core.type),
        )
        val encrypted = EnvelopeCrypto.encryptCore(
            core = core,
            groupKey = groupKey,
        )
        return PreparedBatchItem(
            mid = core.id,
            ct = encrypted.ct,
            ttl = ttlSeconds,
            dtn = dtnMeta,
        )
    }

    private fun transportTtlSeconds(core: EventCore): Int {
        val ttlSeconds = if (
            EventTypes.isPriorityControl(core.type) ||
            EventTypes.isMap(core.type) ||
            EventTypes.isLiveTrail(core.type)
        ) {
            TtlHelper.resolveTtlSeconds(core.type, core.meta?.ttl_s)
        } else {
            DtnTransportPolicy.DEFAULT_TTL_SECONDS.toLong()
        }
        return ttlSeconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun extractBatchItems(payload: ByteArray): ExtractedBatchPayload? {
        val batch = runCatching {
            CanonicalJson.json.decodeFromString(AppBatchEnvelope.serializer(), payload.decodeToString())
        }.getOrNull() ?: return null
        if (batch.v < 2 || batch.items_v2.isEmpty()) return null
        val ttl = batch.ttl ?: DtnTransportPolicy.DEFAULT_TTL_SECONDS
        val dtn = batch.dtn ?: DtnMeta(
            ttl_seconds = ttl,
            hop_count = 0,
            max_hops = DtnTransportPolicy.DEFAULT_MAX_HOPS,
            priority = 1,
        )
        return ExtractedBatchPayload(
            items = batch.items_v2.map { item ->
                PreparedBatchItem(
                    mid = item.mid,
                    ct = item.ct,
                    ttl = ttl,
                    dtn = dtn,
                )
            },
            senderDeviceId = batch.sender_device_id,
            senderRelayId = batch.sender_relay_id,
        )
    }

    private fun buildBatchPayload(
        gid: String,
        items: List<PreparedBatchItem>,
        senderDeviceId: String?,
        senderRelayId: String? = localSenderDeviceId(),
    ): PreparedBatchPayload {
        return requireNotNull(buildBatchPayloadOrNull(gid, items, senderDeviceId, senderRelayId)) {
            "Batch payload exceeded limit: items=${items.size}"
        }
    }

    private fun buildBatchPayloadOrNull(
        gid: String,
        items: List<PreparedBatchItem>,
        senderDeviceId: String?,
        senderRelayId: String? = localSenderDeviceId(),
    ): PreparedBatchPayload? {
        val ttl = items.maxOfOrNull { it.ttl }
        val dtn = items.maxByOrNull { it.dtn.priority }?.dtn
        val batch = AppBatchEnvelope(
            v = 2,
            ts = System.currentTimeMillis(),
            sender_device_id = senderDeviceId,
            sender_relay_id = senderRelayId,
            gid = gid,
            ttl = ttl,
            dtn = dtn,
            items_v2 = items.map { item -> AppBatchItemV2(mid = item.mid, ct = item.ct) },
        )
        val signed = AppBatchCrypto.sign(batch)
        val payload = CanonicalJson.json.encodeToString(AppBatchEnvelope.serializer(), signed)
            .toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_BATCH_PAYLOAD_BYTES) return null
        val eventIds = items.map { it.mid }
        val batchId = signed.app_sig?.mac?.take(12) ?: "ts:${signed.ts}"
        return PreparedBatchPayload(batchId, payload, eventIds)
    }

    private fun hasBlePermissions(context: Context): Boolean {
        return BlePermissionHelper.requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isBleReady(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
    }

    private data class PreparedBatchPayload(
        val batchId: String,
        val payload: ByteArray,
        val eventIds: List<String>,
    )

    private data class ExtractedBatchPayload(
        val items: List<PreparedBatchItem>,
        val senderDeviceId: String?,
        val senderRelayId: String?,
    )

    private data class PreparedBatchItem(
        val mid: String,
        val ct: String,
        val ttl: Int,
        val dtn: DtnMeta,
    )

    private data class EnqueueResult(
        val batchId: String,
        val eventIds: List<String>,
        val mode: String,
    )

    data class DispatchJob(
        val batchId: String,
        val deviceId: String,
        val payload: ByteArray,
        val eventIds: List<String>,
        val source: String,
        val gid: String,
        val lane: String,
        val attemptCount: Int,
    )

    private data class ReplacementBatch(
        val batch: OutboundBatchEntity,
        val events: List<OutboundBatchEventEntity>,
        val prepared: PreparedBatchPayload,
    )

    private fun localSenderDeviceId(): String? {
        val advertised = BleConfig.advertisedDeviceId ?: return null
        if (advertised.isEmpty()) return null
        return advertised.joinToString("") { "%02X".format(it) }
    }

    private class DispatcherThreadFactory : ThreadFactory {
        private val id = AtomicLong(1)
        override fun newThread(r: Runnable): Thread {
            return Thread(r, "OutboundBatchDispatcher-${id.getAndIncrement()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
    }
}
