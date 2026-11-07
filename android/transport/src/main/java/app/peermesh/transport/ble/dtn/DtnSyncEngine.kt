package app.peermesh.transport.ble.dtn

import java.security.MessageDigest

private const val DIAGNOSTIC_ID_SAMPLE_LIMIT = 5

class DtnSyncEngine(
    private val localPeerId: String,
    private val store: DtnInventoryStore,
    private val diagnostics: DtnDiagnosticsSink? = null,
    private val dataReceiver: DtnDataReceiver? = null,
    private val syncStateStore: DtnSyncStateStore? = null,
    private val localCloudStatusProvider: DtnLocalCloudStatusProvider? = null,
    private val peerHelloObserver: DtnPeerHelloObserver? = null,
    private val carrierManifestStore: DtnCarrierManifestStore? = null,
    private val carrierManifestVerifier: DtnCarrierManifestVerifier = DtnCarrierManifestVerifier { _, _ -> false },
    private val localManifestKeyProvider: DtnLocalManifestKeyProvider? = null,
    private val peerManifestKeyStore: DtnPeerManifestKeyStore? = null,
    private val carrierManifestOfferSource: DtnCarrierManifestOfferSource? = null,
    private val carrierManifestSigner: DtnCarrierManifestSigner? = null,
    private val carrierDataSource: DtnCarrierDataSource? = null,
    private val carrierDataReceiver: DtnCarrierDataReceiver? = null,
    private val repairSuppressionStore: DtnRepairSuppressionStore? = null,
    private val inventoryBuilder: DtnInventoryBuilder = DtnInventoryBuilder(store, repairSuppressionStore),
    private val diffResolver: DtnDiffResolver = DtnDiffResolver(store, repairSuppressionStore),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    data class ForegroundDeferredSessionTask(
        val peerId: String,
        val gid: String,
        val sessionId: String,
        val taskKey: String,
        val packet: DtnControlPacket,
    )

    enum class DeferredResumeClaimResult { CLAIMED, RESUME_IN_PROGRESS, TASK_CHANGED, SESSION_INVALIDATED }
    private enum class SessionTaskType(val priority: Int) {
        DATA(0),
        ACK(1),
        INV_WANT(2),
        INV_HAVE(3),
        MANIFEST_ACCEPT(4),
        MANIFEST_KEY(5),
        MANIFEST_OFFER(6),
    }

    private data class OutstandingWant(
        val eventId: String,
        val expiresAtSeconds: Long,
    )

    private data class SessionKey(
        val peerId: String,
        val gid: String,
        val sessionId: String,
    )

    private data class SessionTask(
        val key: String,
        val type: SessionTaskType,
        val packet: DtnControlPacket,
        val enqueuedAtSeconds: Long,
    )

    private data class SessionTaskState(
        var activeTask: SessionTask? = null,
        var activeDeferredForForeground: Boolean = false,
        var deferredResumeInProgress: Boolean = false,
        val pendingByKey: LinkedHashMap<String, SessionTask> = LinkedHashMap(),
    )

    private data class AdvertisedWindowState(
        val tier: DtnInventoryTier,
        val cursorBefore: DtnSyncCursor?,
        val cursorAfter: DtnSyncCursor?,
        val offeredIds: List<String>,
        val hasMoreInTier: Boolean,
        var requestedIds: Set<String> = emptySet(),
        var pendingAckIds: Set<String> = emptySet(),
    )

    private data class OutboundInventoryState(
        var tierIndex: Int = 0,
        val advertisedCursorsByTier: LinkedHashMap<DtnInventoryTier, DtnSyncCursor> = LinkedHashMap(),
        var activeWindow: AdvertisedWindowState? = null,
    )

    private val outstandingWantsByEventId = LinkedHashMap<String, OutstandingWant>()
    private val outstandingLocalWants = LinkedHashMap<SessionKey, LinkedHashSet<String>>()
    // A dependency root is retried for every newly established peer session.  This is scoped to
    // the session (not globally) so one unavailable peer cannot suppress a later repair source.
    private val dependencyRootsRequestedBySession = LinkedHashMap<SessionKey, LinkedHashSet<String>>()
    private val deferredManifestOffers = LinkedHashMap<SessionKey, DtnControlPacket>()
    private val sessionTaskStates = LinkedHashMap<SessionKey, SessionTaskState>()
    private val outboundInventoryStates = LinkedHashMap<SessionKey, OutboundInventoryState>()

    fun buildHelloPacket(gid: String, sessionId: String, nowSeconds: Long = clockSeconds()): DtnControlPacket {
        return DtnControlPacket(
            type = DtnControlTypes.HELLO,
            peerId = localPeerId,
            gid = gid,
            sessionId = sessionId,
            manifestKey = localManifestKey(),
            cloudStatus = localCloudStatusProvider?.currentStatus(nowSeconds),
            ts = nowSeconds,
        )
    }

    /**
     * Loads unresolved durable dependency roots. This may perform store/Room I/O and therefore
     * must be called from a background thread by runtime callers.
     */
    fun loadDependencyRepairWantIds(gid: String): List<String> {
        return store.listDependencyRepairWants(gid, DEPENDENCY_REPAIR_WANT_LIMIT)
            .asSequence()
            .distinct()
            .take(DEPENDENCY_REPAIR_WANT_LIMIT)
            .toList()
    }

    /**
     * Pure-with-respect-to-storage packet construction for proactive repair. Runtime callers first
     * load [candidateRootIds] off the BLE/main thread, then return to the transport thread to build
     * and reserve the WANT for the exact peer session.
     */
    fun buildDependencyRepairWantPacket(
        remotePeerId: String,
        gid: String,
        sessionId: String,
        candidateRootIds: Collection<String>,
        nowSeconds: Long = clockSeconds(),
    ): DtnControlPacket? {
        val sessionKey = SessionKey(peerId = remotePeerId, gid = gid, sessionId = sessionId)
        val rootIds = selectDependencyRepairRoots(
            sessionKey = sessionKey,
            candidateRootIds = candidateRootIds,
        )
        if (rootIds.isEmpty()) return null

        markOutstandingWant(rootIds, nowSeconds)
        trackOutstandingLocalWant(remotePeerId, gid, sessionId, rootIds)
        return DtnControlPacket(
            type = DtnControlTypes.INV_WANT,
            peerId = localPeerId,
            gid = gid,
            sessionId = sessionId,
            mode = DtnInventoryModes.ID_LIST,
            ids = rootIds,
            ts = nowSeconds,
        )
    }

    fun releaseDependencyRepairWantReservation(
        remotePeerId: String,
        gid: String,
        sessionId: String,
        eventIds: Collection<String>,
    ) {
        if (eventIds.isEmpty()) return
        val sessionKey = SessionKey(peerId = remotePeerId, gid = gid, sessionId = sessionId)
        val requested = dependencyRootsRequestedBySession[sessionKey]
        requested?.removeAll(eventIds.toSet())
        if (requested != null && requested.isEmpty()) {
            dependencyRootsRequestedBySession.remove(sessionKey)
        }
        clearOutstandingLocalWant(remotePeerId, gid, sessionId, eventIds.toList())
        eventIds.distinct().forEach { eventId ->
            val stillOutstandingElsewhere = outstandingLocalWants.values.any { eventId in it }
            if (!stillOutstandingElsewhere) {
                outstandingWantsByEventId.remove(eventId)
            }
        }
    }

    fun onPeerHello(gid: String, sessionId: String): DtnControlPacket {
        return inventoryBuilder.buildHavePacket(
            localPeerId = localPeerId,
            gid = gid,
            sessionId = sessionId,
            nowSeconds = clockSeconds(),
        )
    }

    @Synchronized
    fun onControlPacket(packet: DtnControlPacket): List<DtnControlPacket> {
        if (packet.peerId == localPeerId) return emptyList()
        val responses = when (packet.type) {
            DtnControlTypes.HELLO -> onHello(packet)
            DtnControlTypes.INV_HAVE -> onHave(packet)
            DtnControlTypes.INV_WANT -> onWant(packet)
            DtnControlTypes.DATA -> onData(packet)
            DtnControlTypes.ACK -> onAck(packet)
            DtnControlTypes.MANIFEST_KEY -> onManifestKey(packet)
            DtnControlTypes.MANIFEST_OFFER -> onManifestOffer(packet)
            DtnControlTypes.MANIFEST_ACCEPT -> onManifestAccept(packet)
            else -> emptyList()
        }
        val sessionKey = packet.sessionKeyOrNull()
        if (sessionKey == null || responses.isEmpty()) return responses
        completeActiveTask(sessionKey)
        enqueueSessionTasks(sessionKey, responses, clockSeconds())
        val nextPacket = dequeueNextSessionTask(sessionKey)?.packet ?: return emptyList()
        return listOf(nextPacket)
    }

    @Synchronized
    fun onPeerDisconnected(peerId: String, gid: String? = null, sessionId: String? = null) {
        val matchingSessionKeys = sessionTaskStates.keys
            .filter { key ->
                key.peerId == peerId &&
                    (gid == null || key.gid == gid) &&
                    (sessionId == null || key.sessionId == sessionId)
            }
        matchingSessionKeys.forEach(sessionTaskStates::remove)
        outstandingLocalWants.keys.removeIf { key ->
            key.peerId == peerId &&
                (gid == null || key.gid == gid) &&
                (sessionId == null || key.sessionId == sessionId)
        }
        dependencyRootsRequestedBySession.keys.removeIf { key ->
            key.peerId == peerId &&
                (gid == null || key.gid == gid) &&
                (sessionId == null || key.sessionId == sessionId)
        }
        deferredManifestOffers.keys.removeIf { key ->
            key.peerId == peerId &&
                (gid == null || key.gid == gid) &&
                (sessionId == null || key.sessionId == sessionId)
        }
        outboundInventoryStates.keys.removeIf { key ->
            key.peerId == peerId &&
                (gid == null || key.gid == gid) &&
                (sessionId == null || key.sessionId == sessionId)
        }
    }

    @Synchronized
    fun onSessionTaskCompleted(peerId: String, gid: String, sessionId: String): DtnControlPacket? {
        val sessionKey = SessionKey(peerId = peerId, gid = gid, sessionId = sessionId)
        completeActiveTask(sessionKey)
        return dequeueNextSessionTask(sessionKey)?.packet
    }

    /** Retains the existing DTN session task as the owner while Nearby foreground pauses it. */
    @Synchronized
    fun deferActiveSessionTask(peerId: String, gid: String, sessionId: String): Boolean {
        val state = sessionTaskStates[SessionKey(peerId, gid, sessionId)] ?: return false
        if (state.activeTask == null) return false
        state.activeDeferredForForeground = true
        return true
    }

    @Synchronized
    fun foregroundDeferredSessionTasks(): List<ForegroundDeferredSessionTask> = sessionTaskStates
        .mapNotNull { (key, state) ->
            state.activeTask?.takeIf { state.activeDeferredForForeground }?.let { task ->
                ForegroundDeferredSessionTask(key.peerId, key.gid, key.sessionId, task.key, task.packet)
            }
        }

    @Synchronized
    fun claimForegroundDeferredTask(task: ForegroundDeferredSessionTask): DeferredResumeClaimResult {
        val state = sessionTaskStates[SessionKey(task.peerId, task.gid, task.sessionId)]
            ?: return DeferredResumeClaimResult.SESSION_INVALIDATED
        if (state.activeTask?.key != task.taskKey || !state.activeDeferredForForeground) {
            return DeferredResumeClaimResult.TASK_CHANGED
        }
        if (state.deferredResumeInProgress) return DeferredResumeClaimResult.RESUME_IN_PROGRESS
        state.deferredResumeInProgress = true
        return DeferredResumeClaimResult.CLAIMED
    }

    @Synchronized
    fun completeForegroundDeferredTaskQueued(task: ForegroundDeferredSessionTask): Boolean {
        val state = sessionTaskStates[SessionKey(task.peerId, task.gid, task.sessionId)] ?: return false
        if (state.activeTask?.key != task.taskKey || !state.deferredResumeInProgress) return false
        state.activeDeferredForForeground = false
        state.deferredResumeInProgress = false
        return true
    }

    @Synchronized
    fun releaseForegroundDeferredTaskClaim(task: ForegroundDeferredSessionTask): Boolean {
        val state = sessionTaskStates[SessionKey(task.peerId, task.gid, task.sessionId)] ?: return false
        if (state.activeTask?.key != task.taskKey) return false
        state.deferredResumeInProgress = false
        return true
    }

    private fun onHello(packet: DtnControlPacket): List<DtnControlPacket> {
        val gid = packet.gid ?: return emptyList()
        val sessionId = packet.sessionId ?: return emptyList()
        val now = clockSeconds()
        syncStateStore?.markPeerSeen(packet.peerId, gid, sessionId, now)
        peerHelloObserver?.onPeerHello(
            peerId = packet.peerId,
            gid = gid,
            sessionId = sessionId,
            cloudStatus = packet.cloudStatus,
            seenAtSeconds = now,
        )
        saveManifestKey(packet.peerId, packet.manifestKey, now)
        return listOfNotNull(
            buildNextHaveForPeer(packet.peerId, gid, sessionId, now),
            buildManifestKeyPacket(gid, sessionId, now),
            buildManifestOfferPacketUnlessWantPending(
                remotePeerId = packet.peerId,
                gid = gid,
                sessionId = sessionId,
                nowSeconds = now,
            ),
        )
    }

    private fun selectDependencyRepairRoots(
        sessionKey: SessionKey,
        gid: String,
    ): List<String> {
        return selectDependencyRepairRoots(
            sessionKey = sessionKey,
            candidateRootIds = loadDependencyRepairWantIds(gid),
        )
    }

    private fun selectDependencyRepairRoots(
        sessionKey: SessionKey,
        candidateRootIds: Collection<String>,
    ): List<String> {
        val requestedForSession = dependencyRootsRequestedBySession.getOrPut(sessionKey) { LinkedHashSet() }
        val rootIds = candidateRootIds
            .asSequence()
            .filterNot(requestedForSession::contains)
            .distinct()
            .take(DEPENDENCY_REPAIR_WANT_LIMIT)
            .toList()
        requestedForSession.addAll(rootIds)
        return rootIds
    }

    private fun onHave(packet: DtnControlPacket): List<DtnControlPacket> {
        val startedAtNanos = System.nanoTime()
        val fingerprint = inventoryFingerprint(packet.ids)
        diagnostics?.log(
            DtnDiagnosticEvent(
                label = "DTN onHave enter",
                type = packet.type,
                direction = "handle",
                peerId = packet.peerId,
                gid = packet.gid,
                sessionId = packet.sessionId,
                idCount = packet.ids.size,
                idSample = packet.ids.take(DIAGNOSTIC_ID_SAMPLE_LIMIT),
                reason = "tier=${packet.inventoryTier ?: "none"} fingerprint=$fingerprint",
            )
        )
        val gid = packet.gid
        if (gid != null) {
            syncStateStore?.markPeerSeen(packet.peerId, gid, packet.sessionId, clockSeconds())
        }
        val nowSeconds = clockSeconds()
        val resolution = try {
            diagnostics?.log(
                DtnDiagnosticEvent(
                    label = "DTN onHave compare start",
                    type = packet.type,
                    direction = "lookup",
                    peerId = packet.peerId,
                    gid = gid,
                    sessionId = packet.sessionId,
                    idCount = packet.ids.size,
                    reason = "tier=${packet.inventoryTier ?: "none"} fingerprint=$fingerprint",
                )
            )
            gid?.let {
                diffResolver.resolveWantIds(
                    gid = it,
                    remoteIds = packet.ids,
                    nowSeconds = nowSeconds,
                    limit = DtnForwardPolicy.DEFAULT_TRANSFER_LIMIT,
                )
            }.also { result ->
                diagnostics?.log(
                    DtnDiagnosticEvent(
                        label = "DTN onHave compare complete",
                        type = packet.type,
                        direction = "lookup",
                        peerId = packet.peerId,
                        gid = gid,
                        sessionId = packet.sessionId,
                        idCount = packet.ids.size,
                        responseCount = result?.missing?.size ?: 0,
                        reason = "tier=${packet.inventoryTier ?: "none"} fingerprint=$fingerprint missing=${result?.missing?.size ?: 0} suppressed=${result?.suppressed?.size ?: 0} elapsed_ms=${elapsedMillis(startedAtNanos)}",
                    )
                )
            }
        } catch (failure: Throwable) {
            diagnostics?.log(
                DtnDiagnosticEvent(
                    label = "DTN onHave compare failed",
                    type = packet.type,
                    direction = "failed",
                    peerId = packet.peerId,
                    gid = gid,
                    sessionId = packet.sessionId,
                    idCount = packet.ids.size,
                    reason = "tier=${packet.inventoryTier ?: "none"} fingerprint=$fingerprint exception=${failure.javaClass.name} message=${failure.message ?: "none"} elapsed_ms=${elapsedMillis(startedAtNanos)}",
                )
            )
            throw failure
        }
        if (!resolution?.suppressed.isNullOrEmpty()) {
            val forwardedSample = resolution?.suppressedLocallyForwardedSuccess?.take(5)?.joinToString(",").orEmpty()
            val pendingRelaySample = resolution?.suppressedLocallyPendingRelay?.take(5)?.joinToString(",").orEmpty()
            val alreadyHaveSample = resolution?.suppressedLocallySatisfied?.take(5)?.joinToString(",").orEmpty()
            val suppressionReason = when {
                resolution?.suppressedLocallyPendingRelay?.isNotEmpty() == true ->
                    "local_pending_relay sample=${pendingRelaySample.ifBlank { "none" }}"
                resolution?.suppressedLocallyForwardedSuccess?.isNotEmpty() == true ->
                    "local_forwarded_success sample=${forwardedSample.ifBlank { "none" }}"
                resolution?.suppressedLocallySatisfied?.isNotEmpty() == true ->
                    "local_already_have sample=${alreadyHaveSample.ifBlank { "none" }}"
                else ->
                    "sample=" + (resolution?.suppressed?.take(5)?.joinToString(",") ?: "none")
            }
            diagnostics?.log(
                DtnDiagnosticEvent(
                    label = "DTN repair suppress",
                    type = DtnControlTypes.INV_WANT,
                    direction = "suppress",
                    peerId = packet.peerId,
                    gid = gid,
                    sessionId = packet.sessionId,
                    idCount = resolution?.suppressed?.size ?: 0,
                    reason = suppressionReason,
                )
            )
        }
        val sessionKey = gid?.let { stableGid ->
            packet.sessionId?.let { stableSessionId ->
                SessionKey(packet.peerId, stableGid, stableSessionId)
            }
        }
        val dependencyRoots = if (gid == null || sessionKey == null) {
            emptyList()
        } else {
            selectDependencyRepairRoots(sessionKey = sessionKey, gid = gid)
        }
        val wantIds = if (gid == null || packet.type != DtnControlTypes.INV_HAVE || resolution == null) {
            emptyList()
        } else {
            val knownMissingOverlap = currentOutstandingWantIds(nowSeconds)
                .filter { packet.ids.contains(it) }
            // Dependency roots need not occur in this INV_HAVE window. They are targeted
            // requests against the peer's durable event history.
            (dependencyRoots + knownMissingOverlap + resolution.missing)
                .distinct()
                .take(DtnForwardPolicy.DEFAULT_TRANSFER_LIMIT)
        }
        val stableGid = gid
        val stableSessionId = packet.sessionId
        val want = if (wantIds.isEmpty() || stableGid == null || stableSessionId == null) {
            if (stableGid == null || stableSessionId == null || packet.inventoryTier == null) {
                null
            } else {
                DtnControlPacket(
                    type = DtnControlTypes.INV_WANT,
                    peerId = localPeerId,
                    gid = stableGid,
                    sessionId = stableSessionId,
                    mode = DtnInventoryModes.ID_LIST,
                    inventoryTier = packet.inventoryTier,
                    ids = emptyList(),
                    ts = nowSeconds,
                )
            }
        } else {
            markOutstandingWant(wantIds, nowSeconds)
            trackOutstandingLocalWant(packet.peerId, stableGid, stableSessionId, wantIds)
            DtnControlPacket(
                type = DtnControlTypes.INV_WANT,
                peerId = localPeerId,
                gid = stableGid,
                sessionId = stableSessionId,
                mode = DtnInventoryModes.ID_LIST,
                inventoryTier = packet.inventoryTier,
                ids = wantIds,
                ts = nowSeconds,
            )
        }
        diagnostics?.log(
            DtnDiagnosticEvent(
                label = "DTN onHave outcome",
                type = packet.type,
                direction = if (want == null) "no_response" else "response",
                peerId = packet.peerId,
                gid = gid,
                sessionId = packet.sessionId,
                idCount = want?.ids?.size ?: 0,
                idSample = want?.ids?.take(DIAGNOSTIC_ID_SAMPLE_LIMIT).orEmpty(),
                responseCount = if (want == null) 0 else 1,
                reason = "tier=${packet.inventoryTier ?: "none"} fingerprint=$fingerprint want=${when { want == null -> "none"; want.ids.isEmpty() -> "empty"; else -> "non_empty" }} elapsed_ms=${elapsedMillis(startedAtNanos)}",
            )
        )
        if (want == null && gid != null && resolution?.suppressed.isNullOrEmpty()) {
            syncStateStore?.markSyncSuccess(packet.peerId, gid, clockSeconds())
        }
        return listOfNotNull(want)
    }

    private fun inventoryFingerprint(ids: List<String>): String {
        return sha256Hex(ids.joinToString("\u001f").toByteArray(Charsets.UTF_8)).take(16)
    }

    private fun elapsedMillis(startedAtNanos: Long): Long {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L
    }

    private fun onWant(packet: DtnControlPacket): List<DtnControlPacket> {
        val gid = packet.gid ?: return emptyList()
        val sessionId = packet.sessionId ?: return emptyList()
        val nowSeconds = clockSeconds()
        syncStateStore?.markPeerSeen(packet.peerId, gid, packet.sessionId, nowSeconds)
        val sessionKey = SessionKey(peerId = packet.peerId, gid = gid, sessionId = sessionId)
        val activeWindow = outboundInventoryStates[sessionKey]?.activeWindow
        val dependencyOnlyWant = packet.inventoryTier == null && activeWindow == null
        if (dependencyOnlyWant && packet.ids.isNotEmpty()) {
            diagnostics?.log(
                DtnDiagnosticEvent(
                    label = "DTN dependency want recv",
                    type = DtnControlTypes.INV_WANT,
                    direction = "recv",
                    peerId = packet.peerId,
                    gid = gid,
                    sessionId = sessionId,
                    idCount = packet.ids.size,
                    idSample = packet.ids.take(DIAGNOSTIC_ID_SAMPLE_LIMIT),
                    reason = "exact_id_durable_lookup",
                )
            )
        }
        logInventoryTrace(
            label = "DTN want recv",
            type = DtnControlTypes.INV_WANT,
            direction = "recv",
            peerId = packet.peerId,
            gid = gid,
            sessionId = sessionId,
            tier = packet.inventoryTier ?: activeWindow?.tier?.wireValue,
            haveIds = activeWindow?.offeredIds.orEmpty(),
            wantIds = packet.ids,
            cursorBefore = activeWindow?.cursorBefore,
            cursorAfter = activeWindow?.cursorAfter,
        )
        if (packet.ids.isEmpty()) {
            commitActiveWindow(
                sessionKey = sessionKey,
                nowSeconds = nowSeconds,
                reason = "empty_want",
            )
            return listOfNotNull(buildNextHaveForPeer(packet.peerId, gid, sessionId, nowSeconds))
        }
        val data = store.loadData(gid, packet.ids, DtnForwardPolicy.DEFAULT_TRANSFER_LIMIT)
        activeWindow?.let { window ->
            window.requestedIds = packet.ids.toSet()
            window.pendingAckIds = data.map { it.eventId }.toSet()
        }
        diagnostics?.log(
            DtnDiagnosticEvent(
                label = "DTN want load",
                type = DtnControlTypes.INV_WANT,
                direction = "lookup",
                peerId = packet.peerId,
                gid = gid,
                sessionId = packet.sessionId,
                idCount = packet.ids.size,
                dataCount = data.size,
                reason = buildString {
                    append("tier=")
                    append(packet.inventoryTier ?: activeWindow?.tier?.wireValue ?: "none")
                    append(" cursor_before=")
                    append(formatCursor(activeWindow?.cursorBefore))
                    append(" cursor_after=")
                    append(formatCursor(activeWindow?.cursorAfter))
                    append(" have_ids=")
                    append(activeWindow?.offeredIds?.take(5)?.joinToString(",").ifNullOrBlank("none"))
                    append(" want_ids=")
                    append(packet.ids.take(5).joinToString(",").ifBlank { "none" })
                    append(" ")
                    append("requested_sample=")
                    append(packet.ids.take(5).joinToString(",").ifBlank { "none" })
                    append(" loaded_sample=")
                    append(data.take(5).joinToString(",") { it.eventId }.ifBlank { "none" })
                },
            )
        )
        if (dependencyOnlyWant) {
            diagnostics?.log(
                DtnDiagnosticEvent(
                    label = "DTN dependency data served",
                    type = DtnControlTypes.DATA,
                    direction = if (data.isEmpty()) "miss" else "serve",
                    peerId = packet.peerId,
                    gid = gid,
                    sessionId = sessionId,
                    idCount = packet.ids.size,
                    dataCount = data.size,
                    idSample = packet.ids.take(DIAGNOSTIC_ID_SAMPLE_LIMIT),
                    dataIdSample = data.map { it.eventId }.take(DIAGNOSTIC_ID_SAMPLE_LIMIT),
                    reason = if (data.isEmpty()) "durable_lookup_miss" else "exact_id_durable_history",
                )
            )
        }
        if (data.isEmpty()) return emptyList()
        return listOf(
            DtnControlPacket(
                type = DtnControlTypes.DATA,
                peerId = localPeerId,
                gid = gid,
                sessionId = packet.sessionId,
                data = data,
                ts = clockSeconds(),
            )
        )
    }

    private fun onData(packet: DtnControlPacket): List<DtnControlPacket> {
        val gid = packet.gid ?: return emptyList()
        syncStateStore?.markPeerSeen(packet.peerId, gid, packet.sessionId, clockSeconds())
        if (packet.carrierData.isNotEmpty()) return onCarrierData(packet, gid)
        if (packet.data.isEmpty()) return emptyList()
        val acceptedIds = packet.data.mapNotNull { item ->
            val accepted = dataReceiver?.accept(gid, item) ?: true
            if (accepted) item.eventId else null
        }
        if (acceptedIds.isEmpty()) return emptyList()
        val sessionId = packet.sessionId
        val dependencyRequestedIds = if (sessionId == null) {
            emptySet()
        } else {
            dependencyRootsRequestedBySession[SessionKey(packet.peerId, gid, sessionId)].orEmpty()
        }
        val acceptedDependencyIds = acceptedIds.filter { it in dependencyRequestedIds }
        if (acceptedDependencyIds.isNotEmpty()) {
            diagnostics?.log(
                DtnDiagnosticEvent(
                    label = "DTN dependency data recv",
                    type = DtnControlTypes.DATA,
                    direction = "accepted",
                    peerId = packet.peerId,
                    gid = gid,
                    sessionId = sessionId,
                    idCount = acceptedDependencyIds.size,
                    dataCount = acceptedDependencyIds.size,
                    idSample = acceptedDependencyIds.take(DIAGNOSTIC_ID_SAMPLE_LIMIT),
                    dataIdSample = acceptedDependencyIds.take(DIAGNOSTIC_ID_SAMPLE_LIMIT),
                    reason = "dependency_root_accepted",
                )
            )
        }
        repairSuppressionStore?.markLocallySatisfied(
            eventIds = acceptedIds,
            nowSeconds = clockSeconds(),
        )
        clearOutstandingWant(acceptedIds)
        clearOutstandingLocalWant(packet.peerId, gid, packet.sessionId, acceptedIds)
        val responses = mutableListOf(
            DtnControlPacket(
                type = DtnControlTypes.ACK,
                peerId = localPeerId,
                gid = gid,
                sessionId = packet.sessionId,
                mode = DtnInventoryModes.ID_LIST,
                ids = acceptedIds,
                ts = clockSeconds(),
            )
        )
        buildDeferredManifestAccept(packet.peerId, gid, packet.sessionId)?.let(responses::add)
        return responses
    }

    private fun onCarrierData(packet: DtnControlPacket, gid: String): List<DtnControlPacket> {
        val manifestId = packet.manifestId ?: return emptyList()
        if (manifestId.isBlank()) return emptyList()
        val now = clockSeconds()
        val acceptedIds = packet.carrierData.mapNotNull { item ->
            val accepted = carrierDataReceiver?.accept(
                gid = gid,
                peerId = packet.peerId,
                manifestId = manifestId,
                item = item,
                nowSeconds = now,
            ) ?: false
            if (accepted) item.batchId else null
        }
        if (acceptedIds.isEmpty()) return emptyList()
        val responses = mutableListOf(
            DtnControlPacket(
                type = DtnControlTypes.ACK,
                peerId = localPeerId,
                gid = gid,
                sessionId = packet.sessionId,
                mode = DtnInventoryModes.ID_LIST,
                ids = acceptedIds,
                manifestId = manifestId,
                ts = now,
            )
        )
        buildDeferredManifestAccept(packet.peerId, gid, packet.sessionId)?.let(responses::add)
        return responses
    }

    private fun onAck(packet: DtnControlPacket): List<DtnControlPacket> {
        val gid = packet.gid ?: return emptyList()
        val sessionId = packet.sessionId ?: return emptyList()
        val nowSeconds = clockSeconds()
        syncStateStore?.markSyncSuccess(packet.peerId, gid, nowSeconds)
        val sessionKey = SessionKey(peerId = packet.peerId, gid = gid, sessionId = sessionId)
        maybeCommitActiveWindowFromAck(
            sessionKey = sessionKey,
            ackIds = packet.ids,
            nowSeconds = nowSeconds,
        )
        return listOfNotNull(buildNextHaveForPeer(packet.peerId, gid, sessionId, nowSeconds))
    }

    private fun buildManifestKeyPacket(gid: String, sessionId: String, nowSeconds: Long): DtnControlPacket? {
        val key = localManifestKey() ?: return null
        return DtnControlPacket(
            type = DtnControlTypes.MANIFEST_KEY,
            peerId = localPeerId,
            gid = gid,
            sessionId = sessionId,
            manifestKey = key,
            ts = nowSeconds,
        )
    }

    private fun buildManifestOfferPacket(
        remotePeerId: String,
        gid: String,
        sessionId: String,
        nowSeconds: Long,
    ): DtnControlPacket? {
        val signer = carrierManifestSigner ?: return null
        val source = carrierManifestOfferSource ?: return null
        val offerBatches = source.loadOfferBatches(
            gid = gid,
            nowSeconds = nowSeconds,
            maxBatches = DtnCarrierManifestPolicy.DEFAULT_MAX_MANIFEST_BATCHES,
            maxBytes = DtnCarrierManifestPolicy.DEFAULT_MAX_MANIFEST_BYTES,
        )
        if (offerBatches.isEmpty()) return null
        val expiresAt = offerBatches.minOf { it.expiresAtSeconds }
        if (expiresAt <= nowSeconds) return null
        val manifest = DtnCarrierManifest(
            manifestId = manifestId(localPeerId, remotePeerId, sessionId, offerBatches),
            senderPeerId = localPeerId,
            receiverPeerId = remotePeerId,
            gidHash = sha256Hex(gid.toByteArray(Charsets.UTF_8)),
            maxBatches = DtnCarrierManifestPolicy.DEFAULT_MAX_MANIFEST_BATCHES,
            maxBytes = DtnCarrierManifestPolicy.DEFAULT_MAX_MANIFEST_BYTES,
            expiresAt = expiresAt,
            nonce = manifestNonce(localPeerId, remotePeerId, sessionId, nowSeconds),
            batches = offerBatches.map { batch ->
                DtnCarrierManifestBatch(
                    batchId = batch.batchId,
                    payloadSha256 = batch.payloadSha256,
                    payloadBytes = batch.payloadBytes,
                    kind = batch.kind,
                    expiresAt = batch.expiresAtSeconds,
                    priority = batch.priority,
                )
            },
            signature = "",
        )
        val unsignedPacket = DtnControlPacket(
            type = DtnControlTypes.MANIFEST_OFFER,
            peerId = localPeerId,
            gid = gid,
            sessionId = sessionId,
            manifest = manifest,
            ts = nowSeconds,
        )
        val signature = signer.sign(unsignedPacket, manifest)
        if (signature.isBlank()) return null
        return unsignedPacket.copy(manifest = manifest.copy(signature = signature))
    }

    private fun buildManifestOfferPacketUnlessWantPending(
        remotePeerId: String,
        gid: String,
        sessionId: String,
        nowSeconds: Long,
    ): DtnControlPacket? {
        if (hasOutstandingLocalWant(remotePeerId, gid, sessionId)) return null
        return buildManifestOfferPacket(
            remotePeerId = remotePeerId,
            gid = gid,
            sessionId = sessionId,
            nowSeconds = nowSeconds,
        )
    }

    private fun onManifestKey(packet: DtnControlPacket): List<DtnControlPacket> {
        val now = clockSeconds()
        saveManifestKey(packet.peerId, packet.manifestKey, now)
        val gid = packet.gid ?: return emptyList()
        val sessionId = packet.sessionId ?: return emptyList()
        return listOfNotNull(
            buildManifestOfferPacketUnlessWantPending(
                remotePeerId = packet.peerId,
                gid = gid,
                sessionId = sessionId,
                nowSeconds = now,
            )
        )
    }

    private fun localManifestKey(): DtnPeerManifestKey? {
        val key = localManifestKeyProvider?.currentKey() ?: return null
        if (key.peerId != localPeerId) return null
        if (key.algorithm.isBlank() || key.publicKey.isBlank()) return null
        return key
    }

    private fun saveManifestKey(peerId: String, manifestKey: DtnPeerManifestKey?, nowSeconds: Long) {
        manifestKey ?: return
        if (manifestKey.peerId != peerId) return
        if (manifestKey.algorithm != DtnManifestKeyAlgorithms.ECDSA_P256_SHA256) return
        if (manifestKey.publicKey.isBlank()) return
        peerManifestKeyStore?.savePeerKey(
            peerId = peerId,
            algorithm = manifestKey.algorithm,
            publicKey = manifestKey.publicKey,
            nowSeconds = nowSeconds,
        )
    }

    private fun onManifestOffer(packet: DtnControlPacket): List<DtnControlPacket> {
        val manifest = packet.manifest ?: return emptyList()
        val now = clockSeconds()
        if (!carrierManifestVerifier.verify(packet, manifest)) return emptyList()
        val gid = packet.gid ?: return emptyList()
        val sessionId = packet.sessionId ?: return emptyList()
        if (hasOutstandingLocalWant(packet.peerId, gid, sessionId)) {
            deferredManifestOffers[SessionKey(packet.peerId, gid, sessionId)] = packet
            return emptyList()
        }
        return buildManifestAcceptResponses(packet, manifest, now)
    }

    private fun buildManifestAcceptResponses(
        packet: DtnControlPacket,
        manifest: DtnCarrierManifest,
        now: Long,
    ): List<DtnControlPacket> {
        val acceptedBatchIds = DtnCarrierManifestPolicy.acceptedBatchIds(
            packet = packet,
            nowSeconds = now,
            localPeerId = localPeerId,
        )
        if (acceptedBatchIds.isEmpty()) return emptyList()
        carrierManifestStore?.saveAcceptedManifest(
            peerId = packet.peerId,
            sessionId = packet.sessionId,
            manifest = manifest,
            acceptedBatchIds = acceptedBatchIds,
            nowSeconds = now,
        )
        return listOf(
            DtnControlPacket(
                type = DtnControlTypes.MANIFEST_ACCEPT,
                peerId = localPeerId,
                gid = packet.gid,
                sessionId = packet.sessionId,
                manifestId = manifest.manifestId,
                acceptedBatchIds = acceptedBatchIds,
                ts = now,
            )
        )
    }

    private fun onManifestAccept(packet: DtnControlPacket): List<DtnControlPacket> {
        val gid = packet.gid ?: return emptyList()
        val manifestId = packet.manifestId ?: return emptyList()
        if (manifestId.isBlank() || packet.acceptedBatchIds.isEmpty()) return emptyList()
        val now = clockSeconds()
        val carrierData = carrierDataSource?.loadCarrierData(
            gid = gid,
            manifestId = manifestId,
            batchIds = packet.acceptedBatchIds,
            nowSeconds = now,
            maxBytes = DtnCarrierManifestPolicy.DEFAULT_MAX_MANIFEST_BYTES,
        ).orEmpty()
        if (carrierData.isEmpty()) return emptyList()
        return listOf(
            DtnControlPacket(
                type = DtnControlTypes.DATA,
                peerId = localPeerId,
                gid = gid,
                sessionId = packet.sessionId,
                manifestId = manifestId,
                carrierData = carrierData,
                ts = now,
            )
        )
    }

    private fun trackOutstandingLocalWant(peerId: String, gid: String, sessionId: String?, eventIds: List<String>) {
        val stableSessionId = sessionId ?: return
        if (eventIds.isEmpty()) return
        val key = SessionKey(peerId = peerId, gid = gid, sessionId = stableSessionId)
        val ids = outstandingLocalWants.getOrPut(key) { LinkedHashSet() }
        ids.addAll(eventIds)
    }

    private fun clearOutstandingLocalWant(peerId: String, gid: String, sessionId: String?, eventIds: List<String>) {
        val stableSessionId = sessionId ?: return
        val key = SessionKey(peerId = peerId, gid = gid, sessionId = stableSessionId)
        val outstanding = outstandingLocalWants[key] ?: return
        outstanding.removeAll(eventIds.toSet())
        if (outstanding.isEmpty()) {
            outstandingLocalWants.remove(key)
        }
    }

    private fun hasOutstandingLocalWant(peerId: String, gid: String, sessionId: String?): Boolean {
        val stableSessionId = sessionId ?: return false
        return !outstandingLocalWants[SessionKey(peerId = peerId, gid = gid, sessionId = stableSessionId)].isNullOrEmpty()
    }

    private fun buildDeferredManifestAccept(peerId: String, gid: String, sessionId: String?): DtnControlPacket? {
        val stableSessionId = sessionId ?: return null
        if (hasOutstandingLocalWant(peerId, gid, stableSessionId)) return null
        val key = SessionKey(peerId = peerId, gid = gid, sessionId = stableSessionId)
        val packet = deferredManifestOffers.remove(key) ?: return null
        val manifest = packet.manifest ?: return null
        return buildManifestAcceptResponses(packet, manifest, clockSeconds()).firstOrNull()
    }

    private fun buildNextHaveForPeer(
        remotePeerId: String,
        gid: String,
        sessionId: String,
        nowSeconds: Long,
    ): DtnControlPacket? {
        val sessionKey = SessionKey(peerId = remotePeerId, gid = gid, sessionId = sessionId)
        val state = outboundInventoryStates.getOrPut(sessionKey) { OutboundInventoryState() }
        if (state.activeWindow != null) return null

        while (state.tierIndex < DtnInventoryTier.ordered.size) {
            val tier = DtnInventoryTier.ordered[state.tierIndex]
            val cursor = state.advertisedCursorsByTier[tier] ?: loadTierCursor(remotePeerId, gid, tier)
            val window = inventoryBuilder.buildHaveWindow(
                localPeerId = localPeerId,
                remotePeerId = remotePeerId,
                gid = gid,
                sessionId = sessionId,
                nowSeconds = nowSeconds,
                tier = tier,
                cursor = cursor,
            )
            if (window.suppressedIds.isNotEmpty()) {
                diagnostics?.log(
                    DtnDiagnosticEvent(
                        label = "DTN repair suppress",
                        type = DtnControlTypes.INV_HAVE,
                        direction = "suppress",
                        peerId = remotePeerId,
                        gid = gid,
                        sessionId = sessionId,
                        idCount = window.suppressedIds.size,
                        reason = "sample=" + window.suppressedIds.take(5).joinToString(","),
                    )
                )
            }
            window.cursor?.let { advancedCursor ->
                state.advertisedCursorsByTier[tier] = advancedCursor
            }
            if (window.packet.ids.isNotEmpty()) {
                state.activeWindow = AdvertisedWindowState(
                    tier = tier,
                    cursorBefore = cursor,
                    cursorAfter = window.cursor,
                    offeredIds = window.packet.ids,
                    hasMoreInTier = window.hasMoreInTier,
                )
                logInventoryTrace(
                    label = "DTN have build",
                    type = DtnControlTypes.INV_HAVE,
                    direction = "build",
                    peerId = remotePeerId,
                    gid = gid,
                    sessionId = sessionId,
                    tier = tier.wireValue,
                    haveIds = window.packet.ids,
                    wantIds = emptyList(),
                    cursorBefore = cursor,
                    cursorAfter = window.cursor,
                )
                return window.packet
            }
            if (window.hasMoreInTier) {
                continue
            }
            if (window.cursor != null && window.cursor != cursor) {
                saveTierCursor(remotePeerId, gid, tier, window.cursor, nowSeconds)
                logInventoryTrace(
                    label = "DTN cursor commit",
                    type = DtnControlTypes.INV_HAVE,
                    direction = "commit",
                    peerId = remotePeerId,
                    gid = gid,
                    sessionId = sessionId,
                    tier = tier.wireValue,
                    haveIds = emptyList(),
                    wantIds = emptyList(),
                    cursorBefore = cursor,
                    cursorAfter = window.cursor,
                    extra = "commit_reason=local_exhausted",
                )
            }
            state.tierIndex += 1
        }

        outboundInventoryStates.remove(sessionKey)
        return null
    }

    private fun markOutstandingWant(eventIds: Collection<String>, nowSeconds: Long) {
        if (eventIds.isEmpty()) return
        pruneOutstandingWants(nowSeconds)
        val expiresAt = nowSeconds + OUTSTANDING_WANT_TTL_SECONDS
        eventIds.distinct().forEach { eventId ->
            outstandingWantsByEventId[eventId] = OutstandingWant(
                eventId = eventId,
                expiresAtSeconds = expiresAt,
            )
        }
    }

    private fun clearOutstandingWant(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        eventIds.distinct().forEach(outstandingWantsByEventId::remove)
    }

    private fun currentOutstandingWantIds(nowSeconds: Long): List<String> {
        pruneOutstandingWants(nowSeconds)
        return outstandingWantsByEventId.values.map { it.eventId }
    }

    private fun pruneOutstandingWants(nowSeconds: Long) {
        outstandingWantsByEventId.entries.removeIf { (_, want) -> want.expiresAtSeconds <= nowSeconds }
    }

    private fun DtnControlPacket.sessionKeyOrNull(): SessionKey? {
        val stableGid = gid ?: return null
        val stableSessionId = sessionId ?: return null
        return SessionKey(peerId = peerId, gid = stableGid, sessionId = stableSessionId)
    }

    private fun loadTierCursor(peerId: String, gid: String, tier: DtnInventoryTier): DtnSyncCursor? {
        return syncStateStore?.loadCursor(peerId, tierCursorKey(gid, tier))
    }

    private fun saveTierCursor(
        peerId: String,
        gid: String,
        tier: DtnInventoryTier,
        cursor: DtnSyncCursor,
        nowSeconds: Long,
    ) {
        syncStateStore?.saveCursor(peerId, tierCursorKey(gid, tier), cursor, nowSeconds)
    }

    private fun tierCursorKey(gid: String, tier: DtnInventoryTier): String {
        return "$gid#${tier.wireValue}"
    }

    private fun maybeCommitActiveWindowFromAck(
        sessionKey: SessionKey,
        ackIds: List<String>,
        nowSeconds: Long,
    ) {
        val activeWindow = outboundInventoryStates[sessionKey]?.activeWindow ?: return
        if (activeWindow.pendingAckIds.isEmpty()) return
        if (!ackIds.containsAll(activeWindow.pendingAckIds)) return
        commitActiveWindow(
            sessionKey = sessionKey,
            nowSeconds = nowSeconds,
            reason = "ack_complete",
        )
    }

    private fun commitActiveWindow(
        sessionKey: SessionKey,
        nowSeconds: Long,
        reason: String,
    ) {
        val state = outboundInventoryStates[sessionKey] ?: return
        val activeWindow = state.activeWindow ?: return
        activeWindow.cursorAfter?.let { cursorAfter ->
            saveTierCursor(sessionKey.peerId, sessionKey.gid, activeWindow.tier, cursorAfter, nowSeconds)
        }
        logInventoryTrace(
            label = "DTN cursor commit",
            type = DtnControlTypes.INV_HAVE,
            direction = "commit",
            peerId = sessionKey.peerId,
            gid = sessionKey.gid,
            sessionId = sessionKey.sessionId,
            tier = activeWindow.tier.wireValue,
            haveIds = activeWindow.offeredIds,
            wantIds = activeWindow.requestedIds.toList(),
            cursorBefore = activeWindow.cursorBefore,
            cursorAfter = activeWindow.cursorAfter,
            extra = "commit_reason=$reason ack_ids=" +
                activeWindow.pendingAckIds.take(5).joinToString(",").ifBlank { "none" },
        )
        if (!activeWindow.hasMoreInTier) {
            state.tierIndex = maxOf(state.tierIndex, DtnInventoryTier.ordered.indexOf(activeWindow.tier) + 1)
        }
        state.activeWindow = null
        if (state.tierIndex >= DtnInventoryTier.ordered.size &&
            state.activeWindow == null &&
            state.advertisedCursorsByTier.isEmpty()
        ) {
            outboundInventoryStates.remove(sessionKey)
        }
    }

    private fun logInventoryTrace(
        label: String,
        type: String,
        direction: String,
        peerId: String,
        gid: String,
        sessionId: String?,
        tier: String?,
        haveIds: List<String>,
        wantIds: List<String>,
        cursorBefore: DtnSyncCursor?,
        cursorAfter: DtnSyncCursor?,
        extra: String? = null,
    ) {
        diagnostics?.log(
            DtnDiagnosticEvent(
                label = label,
                type = type,
                direction = direction,
                peerId = peerId,
                gid = gid,
                sessionId = sessionId,
                idCount = maxOf(haveIds.size, wantIds.size),
                reason = buildString {
                    append("tier=")
                    append(tier ?: "none")
                    append(" have_ids=")
                    append(haveIds.take(5).joinToString(",").ifBlank { "none" })
                    append(" want_ids=")
                    append(wantIds.take(5).joinToString(",").ifBlank { "none" })
                    append(" cursor_before=")
                    append(formatCursor(cursorBefore))
                    append(" cursor_after=")
                    append(formatCursor(cursorAfter))
                    if (!extra.isNullOrBlank()) {
                        append(" ")
                        append(extra)
                    }
                },
            )
        )
    }

    private fun formatCursor(cursor: DtnSyncCursor?): String {
        return if (cursor == null) "none" else "${cursor.lastInsertedAt}:${cursor.lastEventId}"
    }

    private fun String?.ifNullOrBlank(fallback: String): String {
        return if (this.isNullOrBlank()) fallback else this
    }

    private fun enqueueSessionTasks(sessionKey: SessionKey, packets: List<DtnControlPacket>, nowSeconds: Long) {
        if (packets.isEmpty()) return
        val state = sessionTaskStates.getOrPut(sessionKey) { SessionTaskState() }
        packets.forEach { packet ->
            val type = packet.toSessionTaskType() ?: return@forEach
            val key = sessionTaskKey(type, packet)
            state.pendingByKey[key] = SessionTask(
                key = key,
                type = type,
                packet = packet,
                enqueuedAtSeconds = nowSeconds,
            )
        }
    }

    private fun dequeueNextSessionTask(sessionKey: SessionKey): SessionTask? {
        val state = sessionTaskStates[sessionKey] ?: return null
        if (state.activeTask != null) return null
        val task = state.pendingByKey.values
            .sortedWith(compareBy<SessionTask>({ it.type.priority }, { it.enqueuedAtSeconds }))
            .firstOrNull()
            ?: return null
        state.pendingByKey.remove(task.key)
        state.activeTask = task
        if (state.pendingByKey.isEmpty()) {
            sessionTaskStates[sessionKey] = state
        }
        return task
    }

    private fun completeActiveTask(sessionKey: SessionKey) {
        val state = sessionTaskStates[sessionKey] ?: return
        state.activeTask = null
        state.activeDeferredForForeground = false
        state.deferredResumeInProgress = false
        if (state.pendingByKey.isEmpty()) {
            sessionTaskStates.remove(sessionKey)
        }
    }

    private fun sessionTaskKey(type: SessionTaskType, packet: DtnControlPacket): String {
        val identity = when (type) {
            SessionTaskType.DATA -> packet.data.map { it.eventId }.ifEmpty {
                packet.carrierData.map { it.batchId }
            }.joinToString(",")
            SessionTaskType.ACK,
            SessionTaskType.INV_WANT,
            SessionTaskType.INV_HAVE -> buildString {
                append(packet.inventoryTier.orEmpty())
                append('|')
                append(packet.ids.joinToString(","))
            }
            SessionTaskType.MANIFEST_ACCEPT -> buildString {
                append(packet.manifestId.orEmpty())
                append('|')
                append(packet.acceptedBatchIds.joinToString(","))
            }
            SessionTaskType.MANIFEST_KEY -> packet.manifestKey?.publicKey.orEmpty()
            SessionTaskType.MANIFEST_OFFER -> packet.manifest?.manifestId.orEmpty()
        }
        return "${type.name}|$identity"
    }

    private fun DtnControlPacket.toSessionTaskType(): SessionTaskType? {
        return when (type) {
            DtnControlTypes.DATA -> SessionTaskType.DATA
            DtnControlTypes.ACK -> SessionTaskType.ACK
            DtnControlTypes.INV_WANT -> SessionTaskType.INV_WANT
            DtnControlTypes.INV_HAVE -> SessionTaskType.INV_HAVE
            DtnControlTypes.MANIFEST_ACCEPT -> SessionTaskType.MANIFEST_ACCEPT
            DtnControlTypes.MANIFEST_KEY -> SessionTaskType.MANIFEST_KEY
            DtnControlTypes.MANIFEST_OFFER -> SessionTaskType.MANIFEST_OFFER
            else -> null
        }
    }

    private fun manifestId(
        localPeerId: String,
        remotePeerId: String,
        sessionId: String,
        batches: List<DtnCarrierOfferBatch>,
    ): String {
        val material = buildString {
            append(localPeerId).append('|')
            append(remotePeerId).append('|')
            append(sessionId).append('|')
            batches.forEach { batch ->
                append(batch.batchId).append(':')
                append(batch.payloadSha256).append(':')
                append(batch.payloadBytes).append(';')
            }
        }
        return "mf_${sha256Hex(material.toByteArray(Charsets.UTF_8)).take(40)}"
    }

    private fun manifestNonce(localPeerId: String, remotePeerId: String, sessionId: String, nowSeconds: Long): String {
        return sha256Hex("$localPeerId|$remotePeerId|$sessionId|$nowSeconds".toByteArray(Charsets.UTF_8)).take(32)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val OUTSTANDING_WANT_TTL_SECONDS: Long = 24L * 60L * 60L
        private const val DEPENDENCY_REPAIR_WANT_LIMIT = 16
    }
}
