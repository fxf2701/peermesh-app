package app.peermesh.nearby

import android.content.Context
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.transport.adapter.NearbyForegroundDiagnosticLabels

object NearbyRoomMetadataFetchCoordinator {
    private const val MAX_CONCURRENT_FETCHES = 2
    private const val RESPONSE_TIMEOUT_MS = 8_000L
    private const val FETCH_REASON_DISCOVERY = "discovery"
    private const val FETCH_REASON_POST_JOIN = "post_join"
    private const val MAX_TIMEOUT_BACKOFF_MULTIPLIER = 8L

    private val lock = Any()
    private val pendingRequestsByRoomId = LinkedHashMap<String, FetchRequest>()
    private val inFlightRequestsByRoomId = LinkedHashMap<String, FetchRequest>()
    private val timeoutRetryStateByRoomId = LinkedHashMap<String, TimeoutRetryState>()

    fun enqueue(context: Context, roomIds: Collection<String>) {
        if (roomIds.isEmpty()) return
        val queuedRoomIds = mutableListOf<String>()
        synchronized(lock) {
            roomIds.forEach { roomId ->
                if (roomId !in inFlightRequestsByRoomId) {
                    val existing = pendingRequestsByRoomId[roomId]
                    pendingRequestsByRoomId[roomId] = FetchRequest(
                        roomId = roomId,
                        targetDeviceIdOverride = existing?.targetDeviceIdOverride,
                        force = existing?.force == true,
                        reason = existing?.reason ?: FETCH_REASON_DISCOVERY,
                    )
                    queuedRoomIds += roomId
                }
            }
        }
        queuedRoomIds.forEach { roomId ->
            ActivityLog.add(
                label = "Nearby room metadata fetch queued",
                source = "nearby",
                message = "room_id=$roomId reason=$FETCH_REASON_DISCOVERY force=false",
            )
        }
        drain(context.applicationContext)
    }

    fun enqueuePostJoinRefresh(context: Context, roomId: String, targetDeviceId: String?) {
        val queued: Boolean
        synchronized(lock) {
            val existingPending = pendingRequestsByRoomId[roomId]
            val inFlight = inFlightRequestsByRoomId[roomId]
            pendingRequestsByRoomId[roomId] = FetchRequest(
                roomId = roomId,
                targetDeviceIdOverride =
                    targetDeviceId
                        ?: existingPending?.targetDeviceIdOverride
                        ?: inFlight?.targetDeviceIdOverride,
                force = true,
                reason = FETCH_REASON_POST_JOIN,
            )
            queued = existingPending == null
        }
        ActivityLog.add(
            label = "Nearby room metadata fetch queued",
            source = "nearby",
            message =
                "room_id=$roomId reason=$FETCH_REASON_POST_JOIN target=${targetDeviceId ?: "db_source"} force=true queued=$queued",
        )
        drain(context.applicationContext)
    }

    fun onMetadataResolved(context: Context, roomId: String) {
        val request = synchronized(lock) {
            timeoutRetryStateByRoomId.remove(roomId)
            inFlightRequestsByRoomId.remove(roomId).also {
                pendingRequestsByRoomId.remove(roomId)
            }
        }
        ActivityLog.add(
            label = "Nearby room metadata fetch resolved",
            source = "nearby",
            message = "room_id=$roomId reason=${request?.reason ?: "unknown"} force=${request?.force ?: false}",
        )
        ActivityLog.add(label = NearbyForegroundDiagnosticLabels.JOIN_METADATA_RESOLVED, source = "nearby", message = NearbyForegroundRuntimeCoordinator.joinTimingFields(roomId))
        drain(context.applicationContext)
    }

    fun onMetadataFetchFinished(context: Context, roomId: String) {
        val request = synchronized(lock) {
            inFlightRequestsByRoomId.remove(roomId)
        }
        ActivityLog.add(
            label = "Nearby room metadata fetch finished",
            source = "nearby",
            message = "room_id=$roomId reason=${request?.reason ?: "unknown"} force=${request?.force ?: false}",
        )
        drain(context.applicationContext)
    }

    private fun drain(context: Context) {
        while (true) {
            val nextRequest = synchronized(lock) {
                if (inFlightRequestsByRoomId.size >= MAX_CONCURRENT_FETCHES) {
                    null
                } else {
                    pendingRequestsByRoomId.entries.firstOrNull()?.let { entry ->
                        pendingRequestsByRoomId.remove(entry.key)
                        inFlightRequestsByRoomId[entry.key] = entry.value
                        entry.value
                    }
                }
            }
            nextRequest ?: return
            ActivityLog.add(
                label = "Nearby room metadata fetch started",
                source = "nearby",
                message =
                    "room_id=${nextRequest.roomId} reason=${nextRequest.reason} target=${nextRequest.targetDeviceIdOverride ?: "db_source"} force=${nextRequest.force}",
            )
            ActivityLog.add(label = NearbyForegroundDiagnosticLabels.JOIN_METADATA_START, source = "nearby", message = NearbyForegroundRuntimeCoordinator.joinTimingFields(nextRequest.roomId))
            startFetch(context, nextRequest)
        }
    }

    private fun startFetch(context: Context, request: FetchRequest) {
        val appContext = context.applicationContext
        Thread {
            if (!NearbyForegroundRuntimeCoordinator.allowsNearbyWorkerForRoom(request.roomId)) {
                ActivityLog.add(label = NearbyForegroundDiagnosticLabels.WORKER_STALE, source = "nearby", message = "worker=metadata_fetch ${NearbyForegroundRuntimeCoordinator.joinTimingFields(request.roomId)} result=drop reason=stale_runtime_identity")
                ActivityLog.add(
                    label = "Nearby room metadata fetch skipped",
                    source = "nearby",
                    message = "room_id=${request.roomId} reason=stale_runtime_identity fetch_reason=${request.reason}",
                )
                onMetadataFetchFinished(appContext, request.roomId)
                return@Thread
            }
            val repository = NearbyRoomRepository(appContext)
            ActivityLog.add(label = NearbyForegroundDiagnosticLabels.WORKER_EXECUTE, source = "nearby", message = "worker=metadata_fetch ${NearbyForegroundRuntimeCoordinator.joinTimingFields(request.roomId)} result=execute")
            val room = repository.getRoom(request.roomId)
            val targetDeviceId = request.targetDeviceIdOverride ?: room?.metadata_source_device_id
            val policyDecision = evaluateFetchPolicy(
                request = request,
                room = room,
                targetDeviceId = targetDeviceId,
                nowMs = System.currentTimeMillis(),
            )
            if (!policyDecision.allowed) {
                ActivityLog.add(
                    label = "Nearby room metadata fetch skipped",
                    source = "nearby",
                    message =
                        "room_id=${request.roomId} reason=${policyDecision.reason} force=${request.force} fetch_reason=${request.reason}" +
                            (policyDecision.retryInMs?.let { " retry_in_ms=$it" } ?: ""),
                )
                onMetadataFetchFinished(appContext, request.roomId)
                return@Thread
            }
            if (room == null || targetDeviceId.isNullOrBlank() || targetDeviceId == repository.localDeviceId()) {
                repository.markRoomMetadataFailed(request.roomId, targetDeviceId)
                ActivityLog.add(
                    label = "Nearby room metadata fetch skipped",
                    source = "nearby",
                    message = "room_id=${request.roomId} reason=missing_target force=${request.force} fetch_reason=${request.reason}",
                )
                onMetadataFetchFinished(appContext, request.roomId)
                return@Thread
            }

            val startedAtMs = System.currentTimeMillis()
            repository.markRoomMetadataPending(request.roomId, targetDeviceId, startedAtMs)
            val sent = NearbyRoomTransportManager(appContext, repository)
                .sendMetadataRequest(request.roomId, targetDeviceId)
            ActivityLog.add(
                label = "Nearby room metadata fetch send",
                source = "nearby",
                message = "room_id=${request.roomId} target=$targetDeviceId sent=$sent force=${request.force} fetch_reason=${request.reason}",
            )
            if (!sent) {
                repository.markRoomMetadataFailed(request.roomId, targetDeviceId, startedAtMs)
                onMetadataFetchFinished(appContext, request.roomId)
                return@Thread
            }

            Thread.sleep(RESPONSE_TIMEOUT_MS)
            val refreshed = repository.getRoom(request.roomId)
            val timedOut =
                refreshed != null &&
                    refreshed.metadata_status != NearbyRoomEntity.METADATA_RESOLVED &&
                    refreshed.metadata_last_fetched_at == startedAtMs &&
                    refreshed.metadata_source_device_id == targetDeviceId
            if (timedOut) {
                repository.markRoomMetadataFailed(request.roomId, targetDeviceId, startedAtMs)
                recordTimeout(
                    roomId = request.roomId,
                    targetDeviceId = targetDeviceId,
                    startedAtMs = startedAtMs,
                )
                ActivityLog.add(
                    label = "Nearby room metadata fetch timeout",
                    source = "nearby",
                    message =
                        "room_id=${request.roomId} target=$targetDeviceId timeout_ms=$RESPONSE_TIMEOUT_MS force=${request.force} fetch_reason=${request.reason}",
                )
                ActivityLog.add(label = NearbyForegroundDiagnosticLabels.JOIN_METADATA_TIMEOUT, source = "nearby", message = "${NearbyForegroundRuntimeCoordinator.joinTimingFields(request.roomId)} timeout_ms=$RESPONSE_TIMEOUT_MS")
            }
            onMetadataFetchFinished(appContext, request.roomId)
        }.start()
    }

    private data class FetchRequest(
        val roomId: String,
        val targetDeviceIdOverride: String?,
        val force: Boolean,
        val reason: String,
    )

    private fun evaluateFetchPolicy(
        request: FetchRequest,
        room: NearbyRoomEntity?,
        targetDeviceId: String?,
        nowMs: Long,
    ): FetchPolicyDecision {
        if (room == null) return FetchPolicyDecision(allowed = false, reason = "missing_room")
        if (room.is_local_owner) return FetchPolicyDecision(allowed = false, reason = "policy_gate_local_owner")
        if (room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= nowMs) {
            return FetchPolicyDecision(allowed = false, reason = "policy_gate_inactive")
        }
        if (room.metadata_source_device_id.isNullOrBlank() && request.targetDeviceIdOverride.isNullOrBlank()) {
            return FetchPolicyDecision(allowed = false, reason = "missing_target")
        }
        if (request.force) return FetchPolicyDecision(allowed = true, reason = "forced")
        if (room.metadata_status == NearbyRoomEntity.METADATA_RESOLVED) {
            return FetchPolicyDecision(allowed = false, reason = "policy_gate_resolved")
        }

        val retryState = synchronized(lock) { timeoutRetryStateByRoomId[request.roomId] }
        val effectiveRetryIntervalMs = effectiveRetryIntervalMs(
            timeoutState = retryState,
            targetDeviceId = targetDeviceId,
        )
        val lastFetchedAt = room.metadata_last_fetched_at
        if (lastFetchedAt != null && nowMs - lastFetchedAt < effectiveRetryIntervalMs) {
            return FetchPolicyDecision(
                allowed = false,
                reason = "retry_backoff",
                retryInMs = effectiveRetryIntervalMs - (nowMs - lastFetchedAt),
            )
        }
        if (room.metadata_status == NearbyRoomEntity.METADATA_FAILED &&
            lastFetchedAt != null &&
            room.last_seen_at_ms <= lastFetchedAt &&
            retryState?.lastTimedOutTargetDeviceId == targetDeviceId
        ) {
            return FetchPolicyDecision(
                allowed = false,
                reason = "no_new_signal",
            )
        }
        return FetchPolicyDecision(allowed = true, reason = "eligible")
    }

    private fun effectiveRetryIntervalMs(
        timeoutState: TimeoutRetryState?,
        targetDeviceId: String?,
    ): Long {
        if (timeoutState == null) return NearbyRoomRepository.METADATA_FETCH_RETRY_INTERVAL_MS
        if (!targetDeviceId.isNullOrBlank() && timeoutState.lastTimedOutTargetDeviceId != targetDeviceId) {
            return NearbyRoomRepository.METADATA_FETCH_RETRY_INTERVAL_MS
        }
        val multiplier = minOf(1L shl timeoutState.consecutiveTimeoutCount.coerceAtMost(3), MAX_TIMEOUT_BACKOFF_MULTIPLIER)
        return NearbyRoomRepository.METADATA_FETCH_RETRY_INTERVAL_MS * multiplier
    }

    private fun recordTimeout(
        roomId: String,
        targetDeviceId: String,
        startedAtMs: Long,
    ) {
        synchronized(lock) {
            val existing = timeoutRetryStateByRoomId[roomId]
            val nextCount = if (existing?.lastTimedOutTargetDeviceId == targetDeviceId) {
                existing.consecutiveTimeoutCount + 1
            } else {
                1
            }
            timeoutRetryStateByRoomId[roomId] = TimeoutRetryState(
                consecutiveTimeoutCount = nextCount,
                lastTimedOutTargetDeviceId = targetDeviceId,
                lastTimedOutAtMs = startedAtMs,
            )
        }
    }

    private data class FetchPolicyDecision(
        val allowed: Boolean,
        val reason: String,
        val retryInMs: Long? = null,
    )

    private data class TimeoutRetryState(
        val consecutiveTimeoutCount: Int,
        val lastTimedOutTargetDeviceId: String,
        val lastTimedOutAtMs: Long,
    )
}
