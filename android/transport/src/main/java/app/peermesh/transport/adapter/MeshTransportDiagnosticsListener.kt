package app.peermesh.transport.adapter

interface MeshTransportDiagnosticsListener {
    fun onNearbyForegroundDiagnostic(label: String, message: String) = Unit
    fun onMeshStart(stage: String, reason: String? = null)
    fun onScanSettingsUpdate(running: Boolean, reason: String)
    fun onScanStart(
        scanMode: Int,
        reportDelayMs: Long,
        filterEnabled: Boolean,
        useManufacturerData: Boolean,
    )
    fun onScanStartSkipped(reason: String)
    fun onBleCandidateGraceUsed(
        deviceId: String,
        address: String,
        seenAgeMs: Long,
        activeAgeMs: Long,
    )
    fun onBleTargetSelectEmpty(
        candidateCount: Int,
        mainTargetCount: Int,
        penaltyFilteredCount: Int,
        nearbyCount: Int,
        reason: String,
    )
    fun onBlePeerFairness(
        candidateCount: Int,
        mainTargetCount: Int,
        availableCount: Int,
        selected: String,
        hasPendingDataTraffic: Boolean,
    ) = Unit
    fun onCandidatePoolSnapshot(
        sessionPeers: String,
        fresh: Int,
        grace: Int,
        canonical: Int,
        mainPool: Int,
        potentialPool: Int,
    ) = Unit
    fun onTargetSelectionDecision(
        available: Int,
        effectiveAvailable: Int,
        probeUsed: Boolean,
        selectedIds: String,
        rejectReasonByPeer: String,
    ) = Unit
    fun onBlePreferredTargetFallback(
        preferredIds: String,
        preferredResultCount: Int,
        fallbackSelectedIds: String,
        reason: String,
    ) = Unit
    fun onBleDeviceIdMapped(address: String, deviceId: String, previousId: String?, rssi: Int)
    fun onNearbyRoomAdvertisePayload(
        manufacturerDataBytes: Int,
        baseBytes: Int,
        nearbyPayloadBytes: Int,
        hasNearbyPayload: Boolean,
        manufacturerDataHex: String?,
        nearbyPayloadHex: String?,
    ) = Unit
    fun onNearbyRoomAdvertisementRefreshRejected(
        running: Boolean,
        meshMode: String,
        reason: String,
    ) = Unit
    fun onNearbyRoomDiscoveryPayload(
        address: String,
        deviceId: String?,
        manufacturerDataBytes: Int,
        baseBytes: Int,
        nearbyPayloadBytes: Int,
        hasNearbyPayload: Boolean,
        listenerPresent: Boolean,
        manufacturerDataHex: String?,
        nearbyPayloadHex: String?,
    ) = Unit
    fun onNearbyRoomSelfSuppressed(
        address: String,
        matchedDeviceId: String,
        localDeviceId: String,
        reason: String,
    ) = Unit
    fun onDiscoveryBurst(
        stage: String,
        durationMs: Long,
        scanMode: Int,
        reportDelayMs: Long,
        token: Long,
        reason: String? = null,
    ) = Unit
    fun onBleTargetSelected(
        deviceId: String,
        address: String,
        previousAddress: String?,
        rssi: Int,
        seenAtMs: Long,
    )
    fun onBleTargetPinned(deviceId: String, address: String, ageMs: Long, rssi: Int)
    fun onBleTxTargetMismatch(
        deviceId: String,
        targetAddress: String,
        latestAddress: String,
        rssi: Int,
        seenAtMs: Long,
    )
    fun onBleRxTargetMismatch(address: String, deviceId: String, targetAddress: String)
    fun onBudgetSummary(address: String, sent: Int, windowMs: Long, limit: Int)
    fun onBudgetExhausted(address: String, pauseMs: Long)
    fun onBudgetViolation(address: String, sent: Int, limit: Int)
    fun onAdaptiveDutyState(
        state: String,
        scanMode: Int,
        reportDelayMs: Long,
        advertiseMode: Int,
        advertiseTxPower: Int,
    )
    fun onMtuNegotiation(address: String, success: Boolean, mtu: Int, chunkBytes: Int?)
    fun onConnectBackoff(address: String, failures: Int, delayMs: Long, reason: String)
    fun onAddressCooldownSet(
        address: String,
        deviceId: String?,
        durationMs: Long,
        sourceReason: String,
        existingRemainingMs: Long,
        hasNearbyRoomControlWork: Boolean,
    ) = Unit
    fun onBackpressureTriggered(pendingBytes: Int, nearbyCount: Int)
    fun onForwardDedupeSkip(gid: String, eventId: String)
    fun onRelayForwardDecision(
        kind: String,
        accepted: Boolean,
        relayEligible: Boolean,
        reason: String,
    ) = Unit
    fun onRelayCompensateQueued(relayKey: String, summary: String, targetAddress: String, attempt: Int)
    fun onRelayCompensateSkipped(relayKey: String, summary: String, reason: String, attempt: Int)
    fun onBatchForwardQueued(
        batchId: String,
        targetAddress: String,
        attempt: Int,
        delayMs: Long,
    ) = Unit
    fun onBatchForwardDelivered(
        batchId: String,
        deviceId: String,
        attempt: Int,
    ) = Unit
    fun onBatchForwardFailed(
        batchId: String,
        deviceId: String,
        reason: String,
        attempt: Int,
    ) = Unit
    fun onBatchForwardSkipped(
        batchId: String,
        reason: String,
        attempt: Int,
        delayMs: Long,
    ) = Unit
    fun onBatchForwardDecision(
        batchId: String,
        accepted: Boolean,
        relayEligible: Boolean,
        candidateDeviceIds: List<String>,
        throttleAllowed: Boolean,
        reason: String,
    ) = Unit

    fun onDelayedRelayScheduled(
        batchId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        delayMs: Long,
    ) = Unit

    fun onDelayedRelayDeferred(
        batchId: String,
        targetDeviceId: String,
        deferMs: Long,
        pending: Int,
    ) = Unit

    fun onDelayedRelayFired(
        batchId: String,
        targetDeviceId: String,
        queued: Boolean,
    ) = Unit

    fun onDelayedRelayDropped(
        batchId: String,
        targetDeviceId: String,
        reason: String,
    ) = Unit
    fun onRelayBatchSeedPending(
        batchId: String,
        gid: String?,
        payload: ByteArray,
        eventCount: Int,
        firstEventCreatedT: Long,
        lastEventCreatedT: Long,
        deviceIds: List<String>,
        nowMs: Long,
    ) = Unit
    fun selectRelayBatchTargetDeviceIds(
        batchId: String,
        candidateDeviceIds: List<String>,
        nowMs: Long,
    ): List<String> = candidateDeviceIds
    fun onScanStats(callbacks: Long, records: Long, matched: Long)
    fun onScanAutoRecover(reason: String, idleMs: Long, scanStarted: Boolean)
    fun onBatchScanResults(batchCount: Int, records: Long, matched: Long)
    fun onGattRecvChunk(address: String, bytes: Int)
    fun onReassemblyProgress(
        address: String,
        chunkId: Int?,
        index: Int,
        total: Int,
        received: Int,
        complete: Boolean,
        checksumOk: Boolean,
        reason: String,
    )
    fun onGattReassembled(address: String, bytes: Int)
    fun onDtnControlEnvelopeDetected(address: String, type: String, bytes: Int)
    fun onDtnControlHandleResult(
        address: String,
        type: String,
        handled: Boolean,
        responseCount: Int,
        sentCount: Int,
    )
    fun onDtnControlWireStart(address: String, type: String, payloadBytes: Int, totalChunks: Int)
    fun onDtnControlWireComplete(address: String, type: String, payloadBytes: Int, totalChunks: Int)
    fun onDtnControlWireFailed(
        address: String,
        type: String,
        reason: String,
        payloadBytes: Int,
        totalChunks: Int,
    )
    fun onDtnControlQueueState(
        address: String,
        type: String,
        stage: String,
        pending: Int,
        staged: Int,
        held: Int,
        hasActive: Boolean,
        reason: String?,
    )
    fun onDtnControlQueueDecision(
        address: String,
        selectedType: String?,
        queueDtnTypes: String,
        queueSize: Int,
        reason: String,
    )
    fun onGattDeliver(bytes: Int)
    fun onGattWriteOk(address: String)
    fun onBleSendProgress(
        itemId: String,
        deviceId: String,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
    ) = Unit
    fun onBleSendComplete(
        itemId: String,
        deviceId: String,
        totalChunks: Int,
        payloadBytes: Int,
    ) = Unit
    fun onBleSendFailed(
        itemId: String,
        deviceId: String,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
        reason: String,
    ) = Unit
    fun onBleBatchSendProgress(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
    ) = Unit
    fun onBleBatchTargetQueued(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
    ) = Unit
    fun onBleBatchTargetPending(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        reason: String,
    ) = Unit
    fun onBleBatchSendComplete(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        totalChunks: Int,
        payloadBytes: Int,
    ) = Unit
    fun onBleBatchSendFailed(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
        reason: String,
    ) = Unit
    fun onOpaqueRetryQueued(
        capsuleId: String,
        batchId: String?,
        kind: String,
        targetCount: Int,
    ) = Unit
    fun onOpaqueRetryQueuedState(
        capsuleId: String,
        batchId: String?,
        deviceId: String,
        deliveryStateSummary: String?,
    ) = Unit
    fun onOpaqueRetrySkipped(
        capsuleId: String,
        batchId: String?,
        kind: String,
        reason: String,
        targetCount: Int,
    ) = Unit
    fun onOpaqueRetryStore(
        capsuleId: String?,
        batchId: String?,
        kind: String?,
        reason: String,
        payloadSha256: String?,
        sourcePeerId: String?,
        payloadBytes: Long?,
    ) = Unit
    fun onOpaqueRetryMarkForwarded(
        batchId: String,
        capsuleCount: Int,
    ) = Unit
    fun onOpaqueRetrySendComplete(
        batchId: String,
        deviceId: String,
        capsuleCount: Int,
    ) = Unit
    fun onOpaqueRetryDeleted(
        batchId: String,
        capsuleCount: Int,
        reason: String,
    ) = Unit
    fun onOpaqueRetryFailed(
        batchId: String,
        deviceId: String,
        reason: String,
        writtenChunks: Int,
        totalChunks: Int,
        capsuleCount: Int,
    ) = Unit
    fun onOpaqueRetryPreMarkProbe(
        stage: String,
        batchId: String,
        deviceId: String,
        candidateDeviceId: String,
        mode: String,
        capsuleCount: Int,
        probeUnavailable: String? = null,
    ) = Unit
    fun onOpaqueRetryPreMarkResult(
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
        probeUnavailable: String? = null,
    ) = Unit
    fun onOpaqueRetryBookkeepingProbe(
        stage: String,
        batchId: String,
        deviceId: String,
        deviceIdCandidates: List<String>,
        rememberedCapsules: Int,
        marked: Int,
        mode: String,
        preMarkReasonSummary: String?,
        deliveryStateSummary: String?,
    ) = Unit
    fun onBleBatchInProgressStuckWatchdog(
        batchId: String,
        deviceId: String,
        eventIds: List<String>,
        writtenChunks: Int,
        totalChunks: Int,
        writtenBytes: Int,
        payloadBytes: Int,
        progressAgeMs: Long,
    ) = Unit
    fun onBleBatchInProgressStuckSnapshot(
        batchId: String,
        deviceId: String,
        queuedCount: Int,
        stagedCount: Int,
        hasHeld: Boolean,
        hasActivePacket: Boolean,
        waitingDevices: Int,
        connectCapBlocked: Boolean,
    ) = Unit
    fun onMeshChunkGeometryUpdate(
        scope: String,
        itemId: String?,
        batchId: String?,
        deviceId: String,
        oldTotalChunks: Int,
        newTotalChunks: Int,
        packetTotalChunks: Int,
    ) = Unit
    fun onMeshStalePacketDropped(
        batchId: String,
        deviceId: String,
        address: String,
        sourceQueue: String,
        dropped: Int,
        currentAttemptSeq: Long,
    ) = Unit
    fun onMeshActivePacketReleased(
        address: String,
        reason: String,
        itemId: String,
        batchId: String?,
        deviceId: String,
        isAck: Boolean,
        payloadBytes: Int,
        totalChunks: Int,
        attemptSeq: Long?,
    ) = Unit
    fun onBleBatchStateTransition(
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
    ) = Unit
    fun onQueuedSend(targets: Int)
    fun onQueuedSendTargets(addresses: String)
    fun onLocalAdapterAddress(address: String)
    fun onGattConnectFailed(address: String, reason: String, failures: Int)
    fun onGattConnectSkipped(
        requestedAddress: String,
        selectedAddress: String,
        reason: String,
        state: Int,
        nearby: Int,
    )
    fun onGattConnected(address: String)
    fun onGattServicesDiscovered(address: String)
    fun onBleConnectCapBlocked(
        cap: Int,
        active: Int,
        connecting: Int,
        waiting: Int,
    ) = Unit
    fun onBleConnectCapBlockedSnapshot(summary: String) = Unit
    fun onGattDisconnectReason(
        address: String,
        origin: String,
        reason: String,
        status: Int? = null,
        newState: Int? = null,
    )
    fun onGattServerStarted()
    fun onGattServerStartFailed()
    fun onGattServerAlreadyRunning()
    fun onGattAddServiceFailed()
    fun onAdvertiseStarted()
    fun onAdvertiseFailed(errorCode: Int)
    fun onAdvertisedDeviceId(id: String)
    fun onGattConnectTimeout(address: String)
    fun onGattStalled(address: String, pending: Int)
    fun onGattHardReset(reason: String)
    fun onGattHardResetSkipped(nearby: Int)
    fun onGattWriteFailed(address: String, status: Int)
    fun onGattWriteAttempt(address: String, bytes: Int)
    fun onGattWriteStart(address: String, ok: Boolean)
    fun onGattWriteRejected(address: String, reason: String, status: Int)
    fun onGattWriteNextSkipped(
        address: String,
        reason: String,
        state: Int,
        pending: Int,
        nowMs: Long,
        lastProgressMs: Long?,
        connectStartedMs: Long?,
    )
    fun onGattPostCleanup(
        address: String,
        reason: String,
        mode: String,
        pending: Int,
        lastProgressAgeMs: Long,
        callSite: String?,
    )
    fun onMeshModeChanged(mode: String, reason: String)
    fun onAdvertiseRetry(attempt: Int, delayMs: Long)
    fun onGattServerConnection(address: String, status: Int, state: Int)
    fun onGattServiceAdded(status: Int)
    fun onBleMfgMatch(
        address: String,
        rssi: Int,
        length: Int,
        hex: String,
        matchedDeviceId: String?,
    )
    fun onBleMfgSkip(
        address: String,
        rssi: Int,
        reason: String,
        length: Int,
        hex: String?,
    )
    fun onPipelineLabelEnqueue(address: String, label: String)
    fun onPipelineLabelExecute(address: String, label: String)
    fun onPipelineLabelRequeue(address: String)
    fun onPipelineDrainIdle()
    fun onPipelineConnectBackoffGate(address: String, delayMs: Long)
    fun onPipelineEnqueueDropAll(address: String)
    fun onPipelineStagedToPending(
        address: String,
        deviceId: String?,
        staged: Int,
        accepted: Int,
        rejected: Int,
        pendingBefore: Int,
        pendingAfter: Int,
        firstItemId: String?,
        rejectedItems: String,
    )
    fun onPipelineLabelsChanged(address: String, action: String, label: String, labels: String)

    fun onDispatchJobPulled(
        address: String,
        batchId: String,
        deviceId: String,
        reason: String,
    ) = Unit

    fun onDispatchJobPullAttempt(
        address: String,
        deviceId: String?,
        reason: String,
    ) = Unit

    fun onDispatchJobTerminalState(
        address: String?,
        batchId: String?,
        deviceId: String,
        result: String,
        reason: String,
        pulledBatchId: String? = null,
        pulledDeviceId: String? = null,
    ) = Unit

    fun onDispatchJobPullRejected(
        address: String,
        batchId: String,
        deviceId: String,
        reason: String,
    ) = Unit

    fun onDispatchJobTerminal(
        address: String,
        batchId: String,
        deviceId: String,
        reason: String,
    ) = Unit

    fun onDispatchJobPullSkipped(
        address: String,
        deviceId: String?,
        reason: String,
    ) = Unit
    fun onDispatchNoJobStreak(
        deviceId: String,
        streak: Int,
        suppressedUntilMs: Long,
        lastReason: String,
    ) = Unit
    fun onNearbyCountBreakdown(
        nearbyTotal: Int,
        sessionNearby: Int,
        nonSessionNearby: Int,
    ) = Unit
}
