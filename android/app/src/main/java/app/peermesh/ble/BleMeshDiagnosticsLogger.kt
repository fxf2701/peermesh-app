package app.peermesh.ble

import android.content.Context
import app.peermesh.OutboundBatchDispatcher
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.OutboundBatchEntity
import app.peermesh.data.db.entity.OutboundBatchLane
import app.peermesh.data.db.entity.OutboundBatchStatus
import app.peermesh.data.db.entity.OutboundBatchPeerDeliveryState
import app.peermesh.data.debug.ActivityLog
import app.peermesh.transport.adapter.MeshTransportDiagnosticsListener

class BleMeshDiagnosticsLogger(
    context: Context,
) : MeshTransportDiagnosticsListener {
    private val appContext = context.applicationContext
    private val throughputLock = Any()
    private var throughputLastLogAtMs = 0L
    private var throughputTxBytes = 0L
    private var throughputRxChunkBytes = 0L
    private var throughputDeliverBytes = 0L
    private var throughputWriteAttempts = 0
    private var throughputWriteOk = 0
    private var throughputWriteFail = 0
    private var throughputConnectFail = 0
    private var throughputDisconnect = 0
    private var throughput133 = 0
    private var lastNearbyTotal = 0

    override fun onNearbyForegroundDiagnostic(label: String, message: String) {
        ActivityLog.add(label = label, source = "nearby_fg", message = message)
    }

    private fun touchTransportProgress(nowMs: Long = System.currentTimeMillis()) {
        BleMeshStatus.lastTransportProgressAtMs = nowMs
        BleMeshStatus.notifyChanged()
    }

    private fun notifyStatusChanged() {
        BleMeshStatus.notifyChanged()
    }

    override fun onMeshStart(stage: String, reason: String?) {
        val now = System.currentTimeMillis()
        BleMeshStatus.lastMeshStartStage = stage
        BleMeshStatus.lastMeshStartReason = reason
        if (stage == "called" || stage == "started") {
            BleMeshStatus.lastMeshStartAtMs = now
        }
        if (stage == "started") {
            BleMeshStatus.lastTransportStartAtMs = now
            BleMeshStatus.lastTransportProgressAtMs = 0L
            BleMeshStatus.lastGattConnectedAtMs = 0L
            BleMeshStatus.lastStallDetectedAtMs = 0L
        }
        notifyStatusChanged()
        ActivityLog.add(
            label = "Mesh transport start",
            source = "mesh",
            message = "stage=$stage reason=${reason ?: "none"}",
        )
    }

    override fun onScanSettingsUpdate(running: Boolean, reason: String) {
        ActivityLog.add(
            label = "BLE scan settings update",
            source = "mesh",
            message = "running=$running reason=$reason",
        )
    }

    override fun onScanStart(
        scanMode: Int,
        reportDelayMs: Long,
        filterEnabled: Boolean,
        useManufacturerData: Boolean,
    ) {
        BleMeshStatus.lastScanStartAtMs = System.currentTimeMillis()
        BleMeshStatus.lastScanStartSkippedReason = null
        notifyStatusChanged()
        ActivityLog.add(
            label = "BLE scan start",
            source = "mesh",
            message = "scan_mode=$scanMode report_delay_ms=$reportDelayMs filter_enabled=$filterEnabled use_mfg=$useManufacturerData",
        )
    }

    override fun onScanStartSkipped(reason: String) {
        BleMeshStatus.lastScanStartSkippedAtMs = System.currentTimeMillis()
        BleMeshStatus.lastScanStartSkippedReason = reason
        notifyStatusChanged()
        ActivityLog.add(
            label = "BLE scan start skipped",
            source = "mesh",
            message = "reason=$reason",
        )
    }

    override fun onBleCandidateGraceUsed(
        deviceId: String,
        address: String,
        seenAgeMs: Long,
        activeAgeMs: Long,
    ) {
        ActivityLog.add(
            label = "BLE candidate grace used",
            source = "mesh",
            message = "id=$deviceId addr=$address seen_age_ms=$seenAgeMs active_age_ms=$activeAgeMs",
        )
    }

    override fun onBleTargetSelectEmpty(
        candidateCount: Int,
        mainTargetCount: Int,
        penaltyFilteredCount: Int,
        nearbyCount: Int,
        reason: String,
    ) {
        ActivityLog.add(
            label = "BLE target select empty",
            source = "mesh",
            message = "candidate_count=$candidateCount main_target_count=$mainTargetCount penalty_filtered_count=$penaltyFilteredCount nearby=$nearbyCount reason=$reason",
        )
    }

    override fun onBlePeerFairness(
        candidateCount: Int,
        mainTargetCount: Int,
        availableCount: Int,
        selected: String,
        hasPendingDataTraffic: Boolean,
    ) {
        ActivityLog.add(
            label = "BLE peer fairness",
            source = "mesh",
            message = "candidate_count=$candidateCount main_target_count=$mainTargetCount available_count=$availableCount has_pending_data=$hasPendingDataTraffic selected=$selected",
        )
    }

    override fun onCandidatePoolSnapshot(
        sessionPeers: String,
        fresh: Int,
        grace: Int,
        canonical: Int,
        mainPool: Int,
        potentialPool: Int,
    ) {
        ActivityLog.add(
            label = "CANDIDATE_POOL_SNAPSHOT",
            source = "mesh",
            message =
                "session_peers=$sessionPeers fresh=$fresh grace=$grace canonical=$canonical main_pool=$mainPool potential_pool=$potentialPool",
        )
    }

    override fun onTargetSelectionDecision(
        available: Int,
        effectiveAvailable: Int,
        probeUsed: Boolean,
        selectedIds: String,
        rejectReasonByPeer: String,
    ) {
        ActivityLog.add(
            label = "TARGET_SELECTION_DECISION",
            source = "mesh",
            message =
                "available=$available effective_available=$effectiveAvailable probe_used=$probeUsed selected_ids=$selectedIds reject_reason_by_peer=$rejectReasonByPeer",
        )
    }

    override fun onBlePreferredTargetFallback(
        preferredIds: String,
        preferredResultCount: Int,
        fallbackSelectedIds: String,
        reason: String,
    ) {
        ActivityLog.add(
            label = "BLE target fallback preferred",
            source = "mesh",
            message =
                "preferred_ids=$preferredIds preferred_result_count=$preferredResultCount fallback_selected_ids=$fallbackSelectedIds reason=$reason",
        )
    }

    override fun onBudgetSummary(address: String, sent: Int, windowMs: Long, limit: Int) = Unit

    override fun onBudgetExhausted(address: String, pauseMs: Long) = Unit

    override fun onBudgetViolation(address: String, sent: Int, limit: Int) = Unit

    override fun onAdaptiveDutyState(
        state: String,
        scanMode: Int,
        reportDelayMs: Long,
        advertiseMode: Int,
        advertiseTxPower: Int,
    ) {
        BleMeshStatus.lastAdaptiveState = state
        BleMeshStatus.lastAdaptiveStateAtMs = System.currentTimeMillis()
        notifyStatusChanged()
        if (state != "idle") return
        ActivityLog.add(
            label = "Adaptive duty",
            source = "mesh",
            message = "state=$state scan_mode=$scanMode report_delay_ms=$reportDelayMs advertise_mode=$advertiseMode tx_power=$advertiseTxPower",
        )
    }

    override fun onMtuNegotiation(address: String, success: Boolean, mtu: Int, chunkBytes: Int?) {
        ActivityLog.add(
            label = "MTU negotiation",
            source = "mesh",
            message = "addr=$address mtu=$mtu status=${if (success) "ok" else "fail"} chunk_bytes=${chunkBytes ?: -1}",
        )
    }

    override fun onConnectBackoff(address: String, failures: Int, delayMs: Long, reason: String) {
        ActivityLog.add(
            label = "GATT connect backoff",
            source = "mesh",
            message = "addr=$address failures=$failures delay_ms=$delayMs reason=$reason",
        )
    }

    override fun onAddressCooldownSet(
        address: String,
        deviceId: String?,
        durationMs: Long,
        sourceReason: String,
        existingRemainingMs: Long,
        hasNearbyRoomControlWork: Boolean,
    ) {
        ActivityLog.add(
            label = "GATT address cooldown set",
            source = "mesh",
            message =
                "addr=$address device_id=${deviceId ?: "unknown"} duration_ms=$durationMs source_reason=$sourceReason existing_remaining_ms=$existingRemainingMs nearby_control=$hasNearbyRoomControlWork",
        )
    }

    override fun onBackpressureTriggered(pendingBytes: Int, nearbyCount: Int) = Unit

    override fun onForwardDedupeSkip(gid: String, eventId: String) = Unit

    override fun onRelayForwardDecision(
        kind: String,
        accepted: Boolean,
        relayEligible: Boolean,
        reason: String,
    ) {
        ActivityLog.add(
            label = "BLE relay decision",
            source = "mesh",
            message = "kind=$kind accepted=$accepted relay_eligible=$relayEligible reason=$reason",
        )
    }

    override fun onRelayCompensateQueued(
        relayKey: String,
        summary: String,
        targetAddress: String,
        attempt: Int,
    ) {
        ActivityLog.add(
            label = "BLE relay compensate queued",
            source = "mesh",
            message = "key=$relayKey target=$targetAddress attempt=$attempt $summary",
        )
    }

    override fun onRelayCompensateSkipped(
        relayKey: String,
        summary: String,
        reason: String,
        attempt: Int,
    ) {
        ActivityLog.add(
            label = "BLE relay compensate skipped",
            source = "mesh",
            message = "key=$relayKey attempt=$attempt reason=$reason $summary",
        )
    }

    override fun onBatchForwardQueued(
        batchId: String,
        targetAddress: String,
        attempt: Int,
        delayMs: Long,
    ) {
        ActivityLog.add(
            label = "BLE batch forward queued",
            source = "mesh",
            message = "batch_id=$batchId target=$targetAddress attempt=$attempt delay_ms=$delayMs",
        )
    }

    override fun onBatchForwardDelivered(
        batchId: String,
        deviceId: String,
        attempt: Int,
    ) {
        ActivityLog.add(
            label = "BLE batch forward delivered",
            source = "mesh",
            message = "batch_id=$batchId device=$deviceId attempt=$attempt",
        )
    }

    override fun onBatchForwardFailed(
        batchId: String,
        deviceId: String,
        reason: String,
        attempt: Int,
    ) {
        ActivityLog.add(
            label = "BLE batch forward failed",
            source = "mesh",
            message = "batch_id=$batchId device=$deviceId attempt=$attempt reason=$reason",
        )
    }

    override fun onBatchForwardSkipped(
        batchId: String,
        reason: String,
        attempt: Int,
        delayMs: Long,
    ) {
        ActivityLog.add(
            label = "BLE batch forward skipped",
            source = "mesh",
            message = "batch_id=$batchId attempt=$attempt delay_ms=$delayMs reason=$reason",
        )
    }

    override fun onBatchForwardDecision(
        batchId: String,
        accepted: Boolean,
        relayEligible: Boolean,
        candidateDeviceIds: List<String>,
        throttleAllowed: Boolean,
        reason: String,
    ) {
        ActivityLog.add(
            label = "BLE batch forward decision",
            source = "mesh",
            message =
                "batch_id=$batchId accepted=$accepted relay_eligible=$relayEligible candidates=${candidateDeviceIds.joinToString(",")} throttle_allowed=$throttleAllowed reason=$reason",
        )
    }

    override fun onDelayedRelayScheduled(
        batchId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        delayMs: Long,
    ) {
        ActivityLog.add(
            label = "BLE delayed relay scheduled",
            source = "mesh",
            message = "batch_id=$batchId source=$sourceDeviceId target=$targetDeviceId delay_ms=$delayMs",
        )
    }

    override fun onDelayedRelayDeferred(
        batchId: String,
        targetDeviceId: String,
        deferMs: Long,
        pending: Int,
    ) {
        ActivityLog.add(
            label = "BLE delayed relay deferred",
            source = "mesh",
            message = "batch_id=$batchId target=$targetDeviceId defer_ms=$deferMs pending=$pending",
        )
    }

    override fun onDelayedRelayFired(
        batchId: String,
        targetDeviceId: String,
        queued: Boolean,
    ) {
        ActivityLog.add(
            label = "BLE delayed relay fired",
            source = "mesh",
            message = "batch_id=$batchId target=$targetDeviceId queued=$queued",
        )
    }

    override fun onDelayedRelayDropped(
        batchId: String,
        targetDeviceId: String,
        reason: String,
    ) {
        ActivityLog.add(
            label = "BLE delayed relay dropped",
            source = "mesh",
            message = "batch_id=$batchId target=$targetDeviceId reason=$reason",
        )
    }

    override fun onRelayBatchSeedPending(
        batchId: String,
        gid: String?,
        payload: ByteArray,
        eventCount: Int,
        firstEventCreatedT: Long,
        lastEventCreatedT: Long,
        deviceIds: List<String>,
        nowMs: Long,
    ) {
        if (deviceIds.isEmpty()) return
        val db = AppDatabaseProvider.get(appContext)
        db.runInTransaction {
            ensureRelayBatchExists(
                db = db,
                batchId = batchId,
                gid = gid,
                payload = payload,
                eventCount = eventCount,
                firstEventCreatedT = firstEventCreatedT,
                lastEventCreatedT = lastEventCreatedT,
                nowMs = nowMs,
            )
            deviceIds.forEach { deviceId ->
                db.outboundBatchDao().markPeerPending(batchId, deviceId, nowMs)
            }
        }
    }

    override fun selectRelayBatchTargetDeviceIds(
        batchId: String,
        candidateDeviceIds: List<String>,
        nowMs: Long,
    ): List<String> {
        if (candidateDeviceIds.isEmpty()) return emptyList()
        val db = AppDatabaseProvider.get(appContext)
        val byDeviceId = db.outboundBatchDao()
            .listPeerDeliveries(batchId)
            .associateBy { it.device_id }
        return candidateDeviceIds.filter { deviceId ->
            when (byDeviceId[deviceId]?.state) {
                OutboundBatchPeerDeliveryState.PENDING -> true
                OutboundBatchPeerDeliveryState.FAILED_RETRYABLE -> {
                    val nextRetryAt = byDeviceId[deviceId]?.next_retry_at
                    nextRetryAt == null || nextRetryAt <= nowMs
                }
                else -> false
            }
        }
    }

    private fun ensureRelayBatchExists(
        db: app.peermesh.data.db.AppDatabase,
        batchId: String,
        gid: String?,
        payload: ByteArray,
        eventCount: Int,
        firstEventCreatedT: Long,
        lastEventCreatedT: Long,
        nowMs: Long,
    ) {
        if (db.outboundBatchDao().findBatch(batchId) != null) return
        db.outboundBatchDao().insertBatch(
            OutboundBatchEntity(
                batch_id = batchId,
                gid = gid ?: "__relay__",
                created_at = nowMs,
                updated_at = nowMs,
                status = OutboundBatchStatus.SENT,
                lane = OutboundBatchLane.BACKLOG,
                priority = 0,
                attempt_count = 0,
                last_error = null,
                locked_at = null,
                sent_at = nowMs,
                payload = payload,
                event_count = eventCount,
                first_event_created_t = firstEventCreatedT,
                last_event_created_t = lastEventCreatedT,
                source = "relay_seed",
            ),
        )
    }

    override fun onScanStats(callbacks: Long, records: Long, matched: Long) {
        BleMeshStatus.lastScanCallbackAtMs = System.currentTimeMillis()
        notifyStatusChanged()
        ActivityLog.add(
            label = "BLE scan callback",
            source = "mesh",
            message = "callbacks=$callbacks records=$records matched=$matched",
        )
    }

    override fun onScanAutoRecover(reason: String, idleMs: Long, scanStarted: Boolean) {
        BleMeshStatus.lastAutoRecoverAtMs = System.currentTimeMillis()
        notifyStatusChanged()
        ActivityLog.add(
            label = "BLE scan auto-recover",
            source = "mesh",
            message = "reason=$reason idle_ms=$idleMs scan_started=$scanStarted",
        )
    }

    override fun onBatchScanResults(batchCount: Int, records: Long, matched: Long) = Unit

    override fun onGattRecvChunk(address: String, bytes: Int) {
        touchTransportProgress()
        recordThroughput(rxChunkBytes = bytes.toLong())
        ActivityLog.add(
            label = "GATT recv chunk",
            source = "mesh",
            message = "addr=$address bytes=$bytes",
        )
    }

    override fun onReassemblyProgress(
        address: String,
        chunkId: Int?,
        index: Int,
        total: Int,
        received: Int,
        complete: Boolean,
        checksumOk: Boolean,
        reason: String,
    ) {
        val shouldLog = complete || !checksumOk
        if (!shouldLog) return
        ActivityLog.add(
            label = "GATT reassembly",
            source = "mesh",
            message = "addr=$address chunk=${chunkId ?: -1} idx=$index recv=$received/$total complete=$complete crc=$checksumOk reason=$reason",
        )
    }

    override fun onGattReassembled(address: String, bytes: Int) {
        ActivityLog.add(
            label = "GATT reassembly",
            source = "mesh",
            message = "addr=$address bytes=$bytes",
        )
    }

    override fun onDtnControlEnvelopeDetected(address: String, type: String, bytes: Int) {
        ActivityLog.add(
            label = "DTN control envelope detected",
            source = "dtn",
            message = "addr=$address type=$type bytes=$bytes",
        )
    }

    override fun onDtnControlHandleResult(
        address: String,
        type: String,
        handled: Boolean,
        responseCount: Int,
        sentCount: Int,
    ) {
        ActivityLog.add(
            label = "DTN control handle result",
            source = "dtn",
            message = "addr=$address type=$type handled=$handled responses=$responseCount sent=$sentCount",
        )
    }

    override fun onDtnControlWireStart(
        address: String,
        type: String,
        payloadBytes: Int,
        totalChunks: Int,
    ) {
        ActivityLog.add(
            label = "DTN control wire start",
            source = "dtn",
            message = "addr=$address type=$type bytes=$payloadBytes chunks=$totalChunks",
        )
    }

    override fun onDtnControlWireComplete(
        address: String,
        type: String,
        payloadBytes: Int,
        totalChunks: Int,
    ) {
        ActivityLog.add(
            label = "DTN control wire complete",
            source = "dtn",
            message = "addr=$address type=$type bytes=$payloadBytes chunks=$totalChunks",
        )
    }

    override fun onDtnControlWireFailed(
        address: String,
        type: String,
        reason: String,
        payloadBytes: Int,
        totalChunks: Int,
    ) {
        ActivityLog.add(
            label = "DTN control wire failed",
            source = "dtn",
            message = "addr=$address type=$type reason=$reason bytes=$payloadBytes chunks=$totalChunks",
        )
    }

    override fun onDtnControlQueueState(
        address: String,
        type: String,
        stage: String,
        pending: Int,
        staged: Int,
        held: Int,
        hasActive: Boolean,
        reason: String?,
    ) {
        ActivityLog.add(
            label = "DTN control queue state",
            source = "dtn",
            message =
                "addr=$address type=$type stage=$stage pending=$pending staged=$staged held=$held active=$hasActive reason=${reason ?: "none"}",
        )
    }

    override fun onDtnControlQueueDecision(
        address: String,
        selectedType: String?,
        queueDtnTypes: String,
        queueSize: Int,
        reason: String,
    ) {
        ActivityLog.add(
            label = "DTN control queue decision",
            source = "dtn",
            message = "addr=$address selected=${selectedType ?: "none"} queue_dtn=$queueDtnTypes queue_size=$queueSize reason=$reason",
        )
    }

    override fun onGattDeliver(bytes: Int) {
        touchTransportProgress()
        recordThroughput(deliverBytes = bytes.toLong())
        ActivityLog.add(
            label = "GATT deliver envelope",
            source = "mesh",
            message = "bytes=$bytes",
        )
    }

    override fun onGattWriteOk(address: String) {
        touchTransportProgress()
        recordThroughput(writeOk = 1)
    }

    override fun onBleSendProgress(
        itemId: String,
        deviceId: String,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
    ) {
        ActivityLog.add(
            label = "BLE send progress",
            source = "mesh",
            message = "item=$itemId device=$deviceId chunks=$writtenChunks/$totalChunks bytes=$writtenBytes/$payloadBytes",
        )
    }

    override fun onBleSendComplete(
        itemId: String,
        deviceId: String,
        totalChunks: Int,
        payloadBytes: Int,
    ) {
        ActivityLog.add(
            label = "BLE send complete",
            source = "mesh",
            message = "item=$itemId device=$deviceId chunks=$totalChunks/$totalChunks bytes=$payloadBytes/$payloadBytes",
        )
    }

    override fun onBleSendFailed(
        itemId: String,
        deviceId: String,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
        reason: String,
    ) {
        ActivityLog.add(
            label = "BLE send failed",
            source = "mesh",
            message = "item=$itemId device=$deviceId reason=$reason chunks=$writtenChunks/$totalChunks bytes=$writtenBytes/$payloadBytes",
        )
    }

    override fun onBleBatchSendProgress(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
    ) {
        ActivityLog.add(
            label = "BLE batch send progress",
            source = "mesh",
            message = "batch_id=$batchId device=$deviceId chunks=$writtenChunks/$totalChunks bytes=$writtenBytes/$payloadBytes event_ids=${eventIds.joinToString(",")}",
        )
    }

    override fun onBleBatchTargetQueued(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
    ) {
        OutboundBatchDispatcher.onBatchTargetQueued(batchId, deviceId)
    }

    override fun onBleBatchTargetPending(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        reason: String,
    ) {
        ActivityLog.add(
            label = "APP batch target pending",
            source = "mesh",
            message = "batch_id=$batchId device=$deviceId reason=$reason event_ids=${eventIds.joinToString(",")}",
        )
        OutboundBatchDispatcher.onBatchTargetPending(batchId, deviceId)
    }

    override fun onBleBatchSendComplete(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        totalChunks: Int,
        payloadBytes: Int,
    ) {
        ActivityLog.add(
            label = "BLE batch send complete",
            source = "mesh",
            message = "batch_id=$batchId device=$deviceId chunks=$totalChunks/$totalChunks bytes=$payloadBytes/$payloadBytes event_ids=${eventIds.joinToString(",")}",
        )
        OutboundBatchDispatcher.onBatchSendComplete(batchId, deviceId)
    }

    override fun onBleBatchSendFailed(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
        reason: String,
    ) {
        ActivityLog.add(
            label = "BLE batch send failed",
            source = "mesh",
            message = "batch_id=$batchId device=$deviceId reason=$reason chunks=$writtenChunks/$totalChunks bytes=$writtenBytes/$payloadBytes event_ids=${eventIds.joinToString(",")}",
        )
        OutboundBatchDispatcher.onBatchSendFailed(batchId, deviceId, reason)
    }

    override fun onOpaqueRetryQueued(
        capsuleId: String,
        batchId: String?,
        kind: String,
        targetCount: Int,
    ) {
        ActivityLog.add(
            label = "DTN opaque retry queued",
            source = "dtn",
            message = "capsule_id=$capsuleId batch_id=${batchId ?: "none"} kind=$kind targets=$targetCount",
        )
    }

    override fun onOpaqueRetryQueuedState(
        capsuleId: String,
        batchId: String?,
        deviceId: String,
        deliveryStateSummary: String?,
    ) {
        ActivityLog.add(
            label = "DTN opaque retry queued_state",
            source = "dtn",
            message = "capsule_id=$capsuleId batch_id=${batchId ?: "none"} device=$deviceId state=${deliveryStateSummary ?: "none"}",
        )
    }

    override fun onOpaqueRetrySkipped(
        capsuleId: String,
        batchId: String?,
        kind: String,
        reason: String,
        targetCount: Int,
    ) {
        ActivityLog.add(
            label = "DTN opaque retry skipped",
            source = "dtn",
            message = "capsule_id=$capsuleId batch_id=${batchId ?: "none"} kind=$kind reason=$reason targets=$targetCount",
        )
    }

    override fun onOpaqueRetryStore(
        capsuleId: String?,
        batchId: String?,
        kind: String?,
        reason: String,
        payloadSha256: String?,
        sourcePeerId: String?,
        payloadBytes: Long?,
    ) {
        ActivityLog.add(
            label = "DTN opaque retry store",
            source = "dtn",
            message = "capsule_id=${capsuleId ?: "none"} batch_id=${batchId ?: "none"} kind=${kind ?: "unknown"} reason=$reason payload_sha256=${payloadSha256 ?: "none"} source_peer_id=${sourcePeerId ?: "unknown"} payload_bytes=${payloadBytes ?: -1}",
        )
    }

    override fun onOpaqueRetryMarkForwarded(batchId: String, capsuleCount: Int) {
        ActivityLog.add(
            label = "DTN opaque retry mark_forwarded",
            source = "dtn",
            message = "batch_id=$batchId capsules=$capsuleCount",
        )
    }

    override fun onOpaqueRetrySendComplete(
        batchId: String,
        deviceId: String,
        capsuleCount: Int,
    ) {
        ActivityLog.add(
            label = "DTN opaque retry send_complete",
            source = "dtn",
            message = "batch_id=$batchId device=$deviceId capsules=$capsuleCount",
        )
    }

    override fun onOpaqueRetryDeleted(
        batchId: String,
        capsuleCount: Int,
        reason: String,
    ) {
        ActivityLog.add(
            label = "DTN opaque retry delete",
            source = "dtn",
            message = "batch_id=$batchId capsules=$capsuleCount reason=$reason",
        )
    }

    override fun onOpaqueRetryFailed(
        batchId: String,
        deviceId: String,
        reason: String,
        writtenChunks: Int,
        totalChunks: Int,
        capsuleCount: Int,
    ) {
        ActivityLog.add(
            label = "DTN opaque retry retry_failed",
            source = "dtn",
            message = "batch_id=$batchId device=$deviceId reason=$reason chunks=$writtenChunks/$totalChunks capsules=$capsuleCount",
        )
    }

    override fun onOpaqueRetryPreMarkProbe(
        stage: String,
        batchId: String,
        deviceId: String,
        candidateDeviceId: String,
        mode: String,
        capsuleCount: Int,
        probeUnavailable: String?,
    ) {
        ActivityLog.add(
            label = "DTN opaque retry pre_mark_probe",
            source = "dtn",
            message =
                "s=$stage b=$batchId d=$deviceId c=$candidateDeviceId m=$mode n=$capsuleCount pu=${probeUnavailable ?: "none"}",
        )
    }

    override fun onOpaqueRetryPreMarkResult(
        stage: String,
        batchId: String,
        deviceId: String,
        candidateDeviceId: String,
        mode: String,
        capsuleCount: Int,
        eligible: Int,
        missingCapsule: Int,
        alreadyDelivered: Int,
        failedFinal: Int,
        eligibleButZero: Int,
        probeUnavailable: String?,
    ) {
        ActivityLog.add(
            label = "DTN opaque retry pre_mark_result",
            source = "dtn",
            message =
                "s=$stage b=$batchId d=$deviceId c=$candidateDeviceId m=$mode n=$capsuleCount mk=$eligible mc=$missingCapsule ad=$alreadyDelivered ff=$failedFinal ez=$eligibleButZero pu=${probeUnavailable ?: "none"}",
        )
    }

    override fun onOpaqueRetryBookkeepingProbe(
        stage: String,
        batchId: String,
        deviceId: String,
        deviceIdCandidates: List<String>,
        rememberedCapsules: Int,
        marked: Int,
        mode: String,
        preMarkReasonSummary: String?,
        deliveryStateSummary: String?,
    ) {
        ActivityLog.add(
            label = "DTN opaque retry bookkeeping_probe",
            source = "dtn",
            message =
                "stage=$stage batch_id=$batchId device=$deviceId candidates=${deviceIdCandidates.joinToString(",")} remembered=$rememberedCapsules marked=$marked mode=$mode summary=${preMarkReasonSummary ?: "none"} state=${deliveryStateSummary ?: "none"}",
        )
    }

    override fun onBleBatchInProgressStuckWatchdog(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
        progressAgeMs: Long,
    ) {
        ActivityLog.add(
            label = "BLE batch in-progress stuck watchdog",
            source = "mesh",
            message = "batch_id=$batchId device=$deviceId progress_age_ms=$progressAgeMs chunks=$writtenChunks/$totalChunks bytes=$writtenBytes/$payloadBytes event_ids=${eventIds.joinToString(",")}",
        )
    }

    override fun onBleBatchInProgressStuckSnapshot(
        batchId: String,
        deviceId: String,
        queuedCount: Int,
        stagedCount: Int,
        hasHeld: Boolean,
        hasActivePacket: Boolean,
        waitingDevices: Int,
        connectCapBlocked: Boolean,
    ) {
        ActivityLog.add(
            label = "BLE batch in-progress stuck snapshot",
            source = "mesh",
            message = "batch_id=$batchId device=$deviceId queued=$queuedCount staged=$stagedCount held=$hasHeld active_packet=$hasActivePacket waiting_devices=$waitingDevices connect_cap_blocked=$connectCapBlocked",
        )
    }

    override fun onMeshChunkGeometryUpdate(
        scope: String,
        itemId: String?,
        batchId: String?,
        deviceId: String,
        oldTotalChunks: Int,
        newTotalChunks: Int,
        packetTotalChunks: Int,
    ) {
        ActivityLog.add(
            label = "mesh chunk geometry update",
            source = "mesh",
            message = "scope=$scope item=${itemId ?: "none"} batch_id=${batchId ?: "none"} device=$deviceId total_chunks=$oldTotalChunks=>$newTotalChunks packet_total=$packetTotalChunks",
        )
    }

    override fun onMeshStalePacketDropped(
        batchId: String,
        deviceId: String,
        address: String,
        sourceQueue: String,
        dropped: Int,
        currentAttemptSeq: Long,
    ) {
        ActivityLog.add(
            label = "mesh stale packet dropped",
            source = "mesh",
            message = "batch_id=$batchId device=$deviceId addr=$address source=$sourceQueue dropped=$dropped attempt=$currentAttemptSeq",
        )
    }

    override fun onMeshActivePacketReleased(
        address: String,
        reason: String,
        itemId: String,
        batchId: String?,
        deviceId: String,
        isAck: Boolean,
        payloadBytes: Int,
        totalChunks: Int,
        attemptSeq: Long?,
    ) {
        ActivityLog.add(
            label = "mesh active packet released",
            source = "mesh",
            message = "addr=$address reason=$reason item=$itemId batch_id=${batchId ?: "none"} device=$deviceId ack=$isAck payload_bytes=$payloadBytes total_chunks=$totalChunks attempt=${attemptSeq ?: -1L}",
        )
    }

    override fun onBleBatchStateTransition(
        batchId: String,
        deviceId: String,
        transition: String,
        reason: String?,
        attemptSeq: Long,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
        queuedCount: Int,
        stagedCount: Int,
        hasHeld: Boolean,
        hasActivePacket: Boolean,
        waitingDevices: Int,
        connectCapBlocked: Boolean,
    ) {
        ActivityLog.add(
            label = "BLE batch state transition",
            source = "mesh",
            message = "batch_id=$batchId device=$deviceId transition=$transition reason=${reason ?: "none"} attempt=$attemptSeq chunks=$writtenChunks/$totalChunks bytes=$writtenBytes/$payloadBytes queued=$queuedCount staged=$stagedCount held=$hasHeld active_packet=$hasActivePacket waiting_devices=$waitingDevices connect_cap_blocked=$connectCapBlocked",
        )
    }

    override fun onQueuedSend(targets: Int) {
        touchTransportProgress()
    }

    override fun onQueuedSendTargets(addresses: String) {
        ActivityLog.add(
            label = "Queued BLE targets",
            source = "mesh",
            message = addresses,
        )
    }

    override fun onLocalAdapterAddress(address: String) = Unit

    override fun onGattConnectFailed(address: String, reason: String, failures: Int) {
        recordThroughput(connectFail = 1)
        ActivityLog.add(
            label = "GATT connect failed",
            source = "mesh",
            message = "addr=$address failures=$failures reason=$reason",
        )
    }

    override fun onGattConnectSkipped(
        requestedAddress: String,
        selectedAddress: String,
        reason: String,
        state: Int,
        nearby: Int,
    ) {
        ActivityLog.add(
            label = "GATT connect skipped",
            source = "mesh",
            message = "requested=$requestedAddress selected=$selectedAddress reason=$reason state=$state nearby=$nearby",
        )
    }

    override fun onGattConnected(address: String) {
        val nowMs = System.currentTimeMillis()
        BleMeshStatus.lastGattConnectedAtMs = nowMs
        touchTransportProgress(nowMs)
        ActivityLog.add(
            label = "GATT connected",
            source = "mesh",
            message = "addr=$address",
        )
    }

    override fun onGattServicesDiscovered(address: String) {
        touchTransportProgress()
        ActivityLog.add(
            label = "GATT services discovered",
            source = "mesh",
            message = "addr=$address",
        )
    }

    override fun onBleConnectCapBlocked(cap: Int, active: Int, connecting: Int, waiting: Int) {
        ActivityLog.add(
            label = "BLE connect cap blocked",
            source = "mesh",
            message = "cap=$cap active=$active connecting=$connecting waiting=$waiting",
        )
    }

    override fun onBleConnectCapBlockedSnapshot(summary: String) {
        ActivityLog.add(
            label = "BLE connect cap snapshot",
            source = "mesh",
            message = summary,
        )
    }

    override fun onGattDisconnectReason(
        address: String,
        origin: String,
        reason: String,
        status: Int?,
        newState: Int?,
    ) {
        val is133 = status == 133 || reason.contains("status_133")
        recordThroughput(disconnect = 1, gatt133 = if (is133) 1 else 0)
        val statusPart = status?.toString() ?: "na"
        val statePart = newState?.toString() ?: "na"
        ActivityLog.add(
            label = "GATT disconnect reason",
            source = "mesh",
            message = "addr=$address origin=$origin reason=$reason status=$statusPart newState=$statePart",
        )
    }

    override fun onGattServerStarted() {
        ActivityLog.add(
            label = "GATT server started",
            source = "mesh",
            message = "ok=true",
        )
    }

    override fun onGattServerStartFailed() {
        ActivityLog.add(
            label = "GATT server start failed",
            source = "mesh",
            message = "ok=false",
        )
    }

    override fun onGattServerAlreadyRunning() = Unit

    override fun onGattAddServiceFailed() {
        ActivityLog.add(
            label = "GATT addService failed",
            source = "mesh",
            message = "ok=false",
        )
    }

    override fun onAdvertiseStarted() {
        BleMeshStatus.lastAdvertiseStartedAtMs = System.currentTimeMillis()
        notifyStatusChanged()
        ActivityLog.add(
            label = "BLE advertise started",
            source = "mesh",
            message = "ok=true",
        )
    }

    override fun onAdvertiseFailed(errorCode: Int) {
        ActivityLog.add(
            label = "BLE advertise failed",
            source = "mesh",
            message = "code=$errorCode",
        )
    }

    override fun onAdvertisedDeviceId(id: String) = Unit

    override fun onGattConnectTimeout(address: String) {
        ActivityLog.add(
            label = "GATT connect timeout",
            source = "mesh",
            message = "addr=$address",
        )
    }

    override fun onGattStalled(address: String, pending: Int) {
        ActivityLog.add(
            label = "GATT stalled",
            source = "mesh",
            message = "addr=$address pending=$pending",
        )
    }

    override fun onGattHardReset(reason: String) {
        ActivityLog.add(
            label = "GATT hard reset",
            source = "mesh",
            message = reason,
        )
    }

    override fun onGattHardResetSkipped(nearby: Int) = Unit

    override fun onGattWriteFailed(address: String, status: Int) {
        recordThroughput(writeFail = 1, gatt133 = if (status == 133) 1 else 0)
        ActivityLog.add(
            label = "GATT write failed",
            source = "mesh",
            message = "addr=$address status=$status",
        )
    }

    override fun onGattWriteAttempt(address: String, bytes: Int) {
        recordThroughput(txBytes = bytes.toLong(), writeAttempt = 1)
    }

    override fun onGattWriteStart(address: String, ok: Boolean) = Unit

    override fun onGattWriteRejected(address: String, reason: String, status: Int) {
        ActivityLog.add(
            label = "GATT write rejected",
            source = "mesh",
            message = "addr=$address reason=$reason status=$status",
        )
    }

    override fun onGattWriteNextSkipped(
        address: String,
        reason: String,
        state: Int,
        pending: Int,
        nowMs: Long,
        lastProgressMs: Long?,
        connectStartedMs: Long?,
    ) {
        ActivityLog.add(
            label = "GATT write skipped",
            source = "mesh",
            message = "addr=$address reason=$reason state=$state pending=$pending now_ms=$nowMs last_progress_ms=${lastProgressMs ?: -1} connect_started_ms=${connectStartedMs ?: -1}",
        )
    }

    override fun onGattPostCleanup(
        address: String,
        reason: String,
        mode: String,
        pending: Int,
        lastProgressAgeMs: Long,
        callSite: String?,
    ) {
        ActivityLog.add(
            label = "GATT cleanup scheduled",
            source = "mesh",
            message = "addr=$address reason=$reason mode=$mode pending=$pending last_progress_age_ms=$lastProgressAgeMs call_site=${callSite ?: "unknown"}",
        )
    }

    override fun onMeshModeChanged(mode: String, reason: String) {
        ActivityLog.add(
            label = "BLE mesh mode",
            source = "mesh",
            message = "mode=$mode reason=$reason",
        )
    }

    override fun onAdvertiseRetry(attempt: Int, delayMs: Long) {
        ActivityLog.add(
            label = "BLE advertise retry",
            source = "mesh",
            message = "attempt=$attempt delay_ms=$delayMs",
        )
    }

    override fun onGattServerConnection(address: String, status: Int, state: Int) = Unit

    override fun onGattServiceAdded(status: Int) = Unit

    override fun onBleMfgMatch(
        address: String,
        rssi: Int,
        length: Int,
        hex: String,
        matchedDeviceId: String?,
    ) {
        ActivityLog.add(
            label = "BLE mfg match",
            source = "mesh",
            message = "addr=$address rssi=$rssi length=$length device_id=${matchedDeviceId ?: "unknown"}",
        )
    }

    override fun onBleMfgSkip(
        address: String,
        rssi: Int,
        reason: String,
        length: Int,
        hex: String?,
    ) {
        ActivityLog.add(
            label = "BLE mfg skip",
            source = "mesh",
            message = "addr=$address rssi=$rssi reason=$reason length=$length",
        )
    }

    override fun onBleDeviceIdMapped(
        address: String,
        deviceId: String,
        previousId: String?,
        rssi: Int,
    ) = Unit

    override fun onNearbyRoomAdvertisePayload(
        manufacturerDataBytes: Int,
        baseBytes: Int,
        nearbyPayloadBytes: Int,
        hasNearbyPayload: Boolean,
        manufacturerDataHex: String?,
        nearbyPayloadHex: String?,
    ) {
        ActivityLog.add(
            label = "Nearby room advertise payload",
            source = "nearby",
            message =
                "mfg_bytes=$manufacturerDataBytes base_bytes=$baseBytes nearby_payload_bytes=$nearbyPayloadBytes has_payload=$hasNearbyPayload mfg_hex=${manufacturerDataHex ?: "none"} nearby_hex=${nearbyPayloadHex ?: "none"}",
        )
    }

    override fun onNearbyRoomAdvertisementRefreshRejected(
        running: Boolean,
        meshMode: String,
        reason: String,
    ) {
        ActivityLog.add(
            label = "Nearby room advertisement refresh rejected",
            source = "nearby",
            message = "running=$running mesh_mode=$meshMode reason=$reason",
        )
    }

    override fun onNearbyRoomDiscoveryPayload(
        address: String,
        deviceId: String?,
        manufacturerDataBytes: Int,
        baseBytes: Int,
        nearbyPayloadBytes: Int,
        hasNearbyPayload: Boolean,
        listenerPresent: Boolean,
        manufacturerDataHex: String?,
        nearbyPayloadHex: String?,
    ) {
        ActivityLog.add(
            label = "Nearby room discovery payload",
            source = "nearby",
            message =
                "addr=$address device_id=${deviceId ?: "unknown"} mfg_bytes=$manufacturerDataBytes base_bytes=$baseBytes nearby_payload_bytes=$nearbyPayloadBytes has_payload=$hasNearbyPayload listener_present=$listenerPresent mfg_hex=${manufacturerDataHex ?: "none"} nearby_hex=${nearbyPayloadHex ?: "none"}",
        )
    }

    override fun onNearbyRoomSelfSuppressed(
        address: String,
        matchedDeviceId: String,
        localDeviceId: String,
        reason: String,
    ) {
        ActivityLog.add(
            label = "Nearby room self suppressed",
            source = "nearby",
            message = "addr=$address matched_device_id=$matchedDeviceId local_device_id=$localDeviceId reason=$reason",
        )
    }

    override fun onDiscoveryBurst(
        stage: String,
        durationMs: Long,
        scanMode: Int,
        reportDelayMs: Long,
        token: Long,
        reason: String?,
    ) {
        ActivityLog.add(
            label = "Nearby rooms discovery burst",
            source = "nearby",
            message =
                "stage=$stage duration_ms=$durationMs scan_mode=$scanMode report_delay_ms=$reportDelayMs token=$token reason=${reason ?: "none"}",
        )
    }

    override fun onBleTargetSelected(
        deviceId: String,
        address: String,
        previousAddress: String?,
        rssi: Int,
        seenAtMs: Long,
    ) {
        ActivityLog.add(
            label = "BLE target selected",
            source = "mesh",
            message = "id=$deviceId addr=$address prev=${previousAddress ?: "none"} rssi=$rssi seen_at_ms=$seenAtMs",
        )
    }

    override fun onBleTargetPinned(deviceId: String, address: String, ageMs: Long, rssi: Int) {
        ActivityLog.add(
            label = "BLE target pinned",
            source = "mesh",
            message = "id=$deviceId addr=$address age_ms=$ageMs rssi=$rssi",
        )
    }

    override fun onBleTxTargetMismatch(
        deviceId: String,
        targetAddress: String,
        latestAddress: String,
        rssi: Int,
        seenAtMs: Long,
    ) {
        ActivityLog.add(
            label = "BLE tx target mismatch",
            source = "mesh",
            message = "id=$deviceId target=$targetAddress latest=$latestAddress rssi=$rssi seenAt=$seenAtMs",
        )
    }

    override fun onBleRxTargetMismatch(
        address: String,
        deviceId: String,
        targetAddress: String,
    ) {
        ActivityLog.add(
            label = "BLE rx target mismatch",
            source = "mesh",
            message = "addr=$address id=$deviceId target=$targetAddress",
        )
    }

    override fun onPipelineLabelEnqueue(address: String, label: String) {
        ActivityLog.add(
            label = "PIPELINE label enqueue",
            source = "mesh",
            message = "addr=$address label=$label",
        )
    }

    override fun onPipelineLabelExecute(address: String, label: String) {
        ActivityLog.add(
            label = "PIPELINE label execute",
            source = "mesh",
            message = "addr=$address label=$label",
        )
    }

    override fun onPipelineLabelRequeue(address: String) {
        ActivityLog.add(
            label = "PIPELINE label requeue",
            source = "mesh",
            message = "addr=$address",
        )
    }

    override fun onPipelineDrainIdle() {
        ActivityLog.add(
            label = "PIPELINE drain idle",
            source = "mesh",
            message = "queue=empty",
        )
    }

    override fun onPipelineConnectBackoffGate(address: String, delayMs: Long) {
        ActivityLog.add(
            label = "PIPELINE connect backoff gate",
            source = "mesh",
            message = "addr=$address delay_ms=$delayMs",
        )
    }

    override fun onPipelineEnqueueDropAll(address: String) {
        ActivityLog.add(
            label = "PIPELINE enqueue drop all",
            source = "mesh",
            message = "addr=$address",
        )
    }

    override fun onPipelineStagedToPending(
        address: String,
        deviceId: String?,
        staged: Int,
        accepted: Int,
        rejected: Int,
        pendingBefore: Int,
        pendingAfter: Int,
        firstItemId: String?,
        rejectedItems: String,
    ) {
        ActivityLog.add(
            label = "PIPELINE staged to pending",
            source = "mesh",
            message = "addr=$address device_id=${deviceId ?: "unknown"} staged=$staged accepted=$accepted rejected=$rejected pending_before=$pendingBefore pending_after=$pendingAfter first_item=${firstItemId ?: "unknown"} rejected_items=$rejectedItems",
        )
    }

    override fun onPipelineLabelsChanged(address: String, action: String, label: String, labels: String) {
        ActivityLog.add(
            label = "PIPELINE labels changed",
            source = "mesh",
            message = "addr=$address action=$action label=$label labels=$labels",
        )
    }

    override fun onDispatchJobPulled(
        address: String,
        batchId: String,
        deviceId: String,
        reason: String,
    ) {
        ActivityLog.add(
            label = "DISPATCH job pulled",
            source = "mesh",
            message = "addr=$address batch_id=$batchId device_id=$deviceId reason=$reason",
        )
    }

    override fun onDispatchJobPullRejected(
        address: String,
        batchId: String,
        deviceId: String,
        reason: String,
    ) {
        ActivityLog.add(
            label = "DISPATCH job pull rejected",
            source = "mesh",
            message = "addr=$address batch_id=$batchId device_id=$deviceId reason=$reason",
        )
    }

    override fun onDispatchJobTerminal(
        address: String,
        batchId: String,
        deviceId: String,
        reason: String,
    ) {
        ActivityLog.add(
            label = "DISPATCH job terminal",
            source = "mesh",
            message = "addr=$address batch_id=$batchId device_id=$deviceId reason=$reason",
        )
    }

    override fun onDispatchJobPullSkipped(
        address: String,
        deviceId: String?,
        reason: String,
    ) {
        ActivityLog.add(
            label = "DISPATCH job pull skipped",
            source = "mesh",
            message = "addr=$address device_id=${deviceId ?: "unknown"} reason=$reason",
        )
    }

    override fun onDispatchNoJobStreak(
        deviceId: String,
        streak: Int,
        suppressedUntilMs: Long,
        lastReason: String,
    ) {
        ActivityLog.add(
            label = "DISPATCH_NO_JOB_STREAK",
            source = "mesh",
            message =
                "device_id=$deviceId streak=$streak suppressed_until_ms=$suppressedUntilMs last_reason=$lastReason",
        )
    }

    override fun onNearbyCountBreakdown(
        nearbyTotal: Int,
        sessionNearby: Int,
        nonSessionNearby: Int,
    ) {
        val previousNearbyTotal = lastNearbyTotal
        lastNearbyTotal = nearbyTotal
        BleMeshStatus.lastNearbyTotal = nearbyTotal
        BleMeshStatus.lastNearbyTotalAtMs = System.currentTimeMillis()
        notifyStatusChanged()
        if (previousNearbyTotal <= 0 && nearbyTotal > 0) {
            OutboundBatchDispatcher.onNearbyPeerDetected(appContext)
        }
        ActivityLog.add(
            label = "NEARBY_COUNT_BREAKDOWN",
            source = "mesh",
            message =
                "nearby_total=$nearbyTotal session_nearby=$sessionNearby non_session_nearby=$nonSessionNearby",
        )
    }

    override fun onDispatchJobPullAttempt(
        address: String,
        deviceId: String?,
        reason: String,
    ) {
        ActivityLog.add(
            label = "DISPATCH pull attempt",
            source = "mesh",
            message = "addr=$address device_id=${deviceId ?: "unknown"} reason=$reason",
        )
    }

    override fun onDispatchJobTerminalState(
        address: String?,
        batchId: String?,
        deviceId: String,
        result: String,
        reason: String,
        pulledBatchId: String?,
        pulledDeviceId: String?,
    ) {
        ActivityLog.add(
            label = "DISPATCH pull terminal state",
            source = "mesh",
            message =
                "addr=${address ?: "unknown"} device_id=$deviceId batch_id=${batchId ?: "unknown"} result=$result reason=$reason pulled_batch_id=${pulledBatchId ?: "none"} pulled_device_id=${pulledDeviceId ?: "none"}",
        )
    }

    private fun recordThroughput(
        txBytes: Long = 0L,
        rxChunkBytes: Long = 0L,
        deliverBytes: Long = 0L,
        writeAttempt: Int = 0,
        writeOk: Int = 0,
        writeFail: Int = 0,
        connectFail: Int = 0,
        disconnect: Int = 0,
        gatt133: Int = 0,
    ) {
        val now = System.currentTimeMillis()
        var snapshot: ThroughputSnapshot? = null
        synchronized(throughputLock) {
            throughputTxBytes += txBytes
            throughputRxChunkBytes += rxChunkBytes
            throughputDeliverBytes += deliverBytes
            throughputWriteAttempts += writeAttempt
            throughputWriteOk += writeOk
            throughputWriteFail += writeFail
            throughputConnectFail += connectFail
            throughputDisconnect += disconnect
            throughput133 += gatt133

            if (now - throughputLastLogAtMs >= THROUGHPUT_LOG_INTERVAL_MS) {
                snapshot = ThroughputSnapshot(
                    txBytes = throughputTxBytes,
                    rxChunkBytes = throughputRxChunkBytes,
                    deliverBytes = throughputDeliverBytes,
                    writeAttempts = throughputWriteAttempts,
                    writeOk = throughputWriteOk,
                    writeFail = throughputWriteFail,
                    connectFail = throughputConnectFail,
                    disconnect = throughputDisconnect,
                    gatt133 = throughput133,
                    windowMs = now - throughputLastLogAtMs,
                )
                throughputTxBytes = 0L
                throughputRxChunkBytes = 0L
                throughputDeliverBytes = 0L
                throughputWriteAttempts = 0
                throughputWriteOk = 0
                throughputWriteFail = 0
                throughputConnectFail = 0
                throughputDisconnect = 0
                throughput133 = 0
                throughputLastLogAtMs = now
            }
        }
        val snap = snapshot ?: return
        ActivityLog.add(
            label = "BLE throughput snapshot",
            source = "mesh",
            message = "win=${snap.windowMs} tx=${snap.txBytes} rx=${snap.rxChunkBytes} del=${snap.deliverBytes} " +
                "wa=${snap.writeAttempts} ok=${snap.writeOk} wf=${snap.writeFail} " +
                "cf=${snap.connectFail} dc=${snap.disconnect} e133=${snap.gatt133}",
        )
    }

    private data class ThroughputSnapshot(
        val txBytes: Long,
        val rxChunkBytes: Long,
        val deliverBytes: Long,
        val writeAttempts: Int,
        val writeOk: Int,
        val writeFail: Int,
        val connectFail: Int,
        val disconnect: Int,
        val gatt133: Int,
        val windowMs: Long,
    )
}

private const val THROUGHPUT_LOG_INTERVAL_MS = 10_000L
