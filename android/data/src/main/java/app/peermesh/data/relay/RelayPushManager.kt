package app.peermesh.data.relay

import app.peermesh.data.db.dao.ChatDao
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.dao.OutboundBatchDao
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.db.entity.OutboundBatchStatus
import app.peermesh.data.ingest.EventSources
import app.peermesh.transport.relay.RelayHttpClient
import app.peermesh.transport.relay.RelayIngestResult
import app.peermesh.core.relay.RelayIngestRequest
import app.peermesh.core.types.EventTypes
import app.peermesh.data.reducer.DeliveryStatus
import app.peermesh.data.debug.ActivityLog

class RelayPushManager(
    private val relayUrl: String,
    private val eventsDao: EventsDao,
    private val chatDao: ChatDao? = null,
    private val outboundBatchDao: OutboundBatchDao? = null,
    private val relayClient: RelayHttpClient,
    private val mapper: RelayEventMapper,
    private val stateStore: RelayPushStateStore = InMemoryRelayPushStateStore(),
    private val ingestTransport: (RelayIngestRequest) -> RelayIngestResult = relayClient::ingest,
) {
    fun pushNewEvents(
        maxBatchSize: Int = 25,
        maxEvents: Int = 200,
    ): RelayPushReport {
        val startMs = System.currentTimeMillis()
        val nowSeconds = System.currentTimeMillis() / 1000L
        if (relayUrl.isBlank()) return RelayPushReport.empty()

        val state = stateStore.load(relayUrl) ?: RelayPushState(
            lastPushRowId = 0L,
            lastPushAt = 0L,
            lastPushEventId = "",
        )
        reconcileCoveredMeshBatches(
            lastPushRowId = state.lastPushRowId,
        )
        val candidateEvents = eventsDao.loadAfterRowIdExcludingSources(
            state.lastPushRowId,
            maxEvents,
            listOf(EventSources.RELAY, EventSources.DTN),
        )
        val rawEvents = candidateEvents.filter(::shouldBridgeToCloud)
        if (rawEvents.isEmpty() && candidateEvents.isNotEmpty()) {
            val lastCandidate = lastByRowId(candidateEvents)
            stateStore.save(
                relayUrl,
                RelayPushState(
                    lastPushRowId = lastCandidate.id,
                    lastPushAt = lastCandidate.inserted_at,
                    lastPushEventId = lastCandidate.event_id,
                )
            )
            ActivityLog.add(
                label = "Relay bridge skip",
                source = "relay",
                message = "relay=$relayUrl advanced_to_row=${lastCandidate.id} skipped=${candidateEvents.size} reasons=policy",
            )
            val elapsedMs = System.currentTimeMillis() - startMs
            ActivityLog.add(
                label = "Relay push",
                source = "relay",
                message = "relay=$relayUrl no bridgeable events elapsedMs=$elapsedMs",
            )
            return RelayPushReport.empty()
        }
        if (rawEvents.isEmpty()) {
            val elapsedMs = System.currentTimeMillis() - startMs
            ActivityLog.add(
                label = "Relay push",
                source = "relay",
                message = "relay=$relayUrl no events elapsedMs=$elapsedMs",
            )
            return RelayPushReport.empty()
        }
        var attempted = 0
        var accepted = 0
        var rejected = 0
        val rejectedReasons = mutableListOf<String>()
        ActivityLog.add(
            label = "Relay push",
            source = "relay",
            message = "relay=$relayUrl pending=${rawEvents.size}",
        )

        rawEvents.chunked(maxBatchSize).forEach { batch ->
            val orderedBatch = batch.sortedWith(
                compareBy<EventEntity>({ eventPriority(it.type) }, { it.inserted_at }, { it.event_id })
            )
            var dropped = 0
            val dropSamples = mutableListOf<String>()
            val dropReasons = linkedMapOf<String, Int>()
            val invalidEvents = mutableListOf<Pair<EventEntity, String>>()
            val relayEvents = orderedBatch.mapNotNull { event ->
                val result = runCatching { mapper.mapWithReason(event) }.getOrNull()
                val mapped = result?.relayEvent
                if (mapped == null) {
                    dropped += 1
                    val reason = result?.dropReason ?: "unknown"
                    dropReasons[reason] = (dropReasons[reason] ?: 0) + 1
                    if (shouldMarkInvalid(reason)) {
                        invalidEvents.add(event to reason)
                    }
                    if (dropSamples.size < 3) {
                        val detail = result?.dropDetail?.let { " $it" } ?: ""
                        dropSamples.add("id=${event.event_id} type=${event.type} gid=${event.gid} reason=$reason$detail")
                    }
                }
                mapped
            }
            if (invalidEvents.isNotEmpty()) {
                invalidEvents.forEach { (event, reason) ->
                    eventsDao.markInvalid(event.gid, event.event_id, nowSeconds, reason)
                }
            }
            if (relayEvents.isEmpty()) {
                if (dropped > 0) {
                    ActivityLog.add(
                        label = "Relay push drop",
                        source = "relay",
                        message = "relay=$relayUrl dropped=$dropped reasons=${dropReasons.entries.joinToString { "${it.key}=${it.value}" }} sample=${dropSamples.joinToString(" | ")}",
                    )
                    val detail = dropSamples.firstOrNull { it.contains("reason=expired") }
                    val detailPayload = detail?.substringAfter("reason=expired")?.trim()
                    if (!detailPayload.isNullOrBlank()) {
                        ActivityLog.add(
                            label = "Relay push drop detail",
                            source = "relay",
                            message = detailPayload,
                        )
                    }
                } else {
                    ActivityLog.add(
                        label = "Relay push drop",
                        source = "relay",
                        message = "relay=$relayUrl dropped=0 (all mapped null or empty batch)",
                    )
                }
                val lastEvent = lastByRowId(batch)
                stateStore.save(
                    relayUrl,
                    RelayPushState(
                        lastPushRowId = lastEvent.id,
                        lastPushAt = lastEvent.inserted_at,
                        lastPushEventId = lastEvent.event_id,
                    )
                )
                reconcileCoveredMeshBatches(
                    lastPushRowId = lastEvent.id,
                )
                return@forEach
            }
            val batchStartMs = System.currentTimeMillis()
            val result = ingestTransport(RelayIngestRequest(relayEvents))
            val batchElapsedMs = System.currentTimeMillis() - batchStartMs
            attempted += relayEvents.size
            if (!result.success) {
                return RelayPushReport(
                    attempted = attempted,
                    accepted = accepted,
                    rejected = rejected,
                    success = false,
                    statusCode = result.statusCode,
                    errorMessage = result.errorMessage,
                    errorCode = result.errorCode,
                    rejectedReasons = rejectedReasons.toList(),
                )
            }
            accepted += result.accepted
            rejected += result.rejected
            rejectedReasons.addAll(result.rejectedReasons)
            ActivityLog.add(
                label = "Relay push batch",
                source = "relay",
                message = "relay=$relayUrl attempted=${relayEvents.size} accepted=${result.accepted} rejected=${result.rejected} dropped=$dropped reasons=${dropReasons.entries.joinToString { "${it.key}=${it.value}" }} ok=${result.success} elapsedMs=$batchElapsedMs",
            )
            if (dropped > 0) {
                ActivityLog.add(
                    label = "Relay push drop",
                    source = "relay",
                    message = "relay=$relayUrl dropped=$dropped reasons=${dropReasons.entries.joinToString { "${it.key}=${it.value}" }} sample=${dropSamples.joinToString(" | ")}",
                )
                val detail = dropSamples.firstOrNull { it.contains("reason=expired") }
                val detailPayload = detail?.substringAfter("reason=expired")?.trim()
                if (!detailPayload.isNullOrBlank()) {
                    ActivityLog.add(
                        label = "Relay push drop detail",
                        source = "relay",
                        message = detailPayload,
                    )
                }
            }
            if (result.success && result.rejected == 0) {
                markChatDelivered(batch)
            }
            val lastEvent = lastByRowId(batch)
            stateStore.save(
                relayUrl,
                RelayPushState(
                    lastPushRowId = lastEvent.id,
                    lastPushAt = lastEvent.inserted_at,
                    lastPushEventId = lastEvent.event_id,
                )
            )
            reconcileCoveredMeshBatches(
                lastPushRowId = lastEvent.id,
            )
        }

        val elapsedMs = System.currentTimeMillis() - startMs
        ActivityLog.add(
            label = "Relay push summary",
            source = "relay",
            message = "relay=$relayUrl attempted=$attempted accepted=$accepted rejected=$rejected elapsedMs=$elapsedMs",
        )
        cleanupInvalidEvents(nowSeconds)
        return RelayPushReport(
            attempted = attempted,
            accepted = accepted,
            rejected = rejected,
            success = true,
            statusCode = 200,
            errorMessage = null,
            errorCode = null,
            rejectedReasons = rejectedReasons.toList(),
        )
    }

    private fun lastByRowId(batch: List<EventEntity>): EventEntity {
        return batch.maxBy { it.id }
    }

    private fun markChatDelivered(batch: List<EventEntity>) {
        val dao = chatDao ?: return
        val grouped = batch
            .filter { it.type == EventTypes.CHAT_MSG || it.type == EventTypes.CHAT_REPLY }
            .groupBy { it.gid }
        grouped.forEach { (gid, events) ->
            val ids = events.map { it.event_id }
            if (ids.isNotEmpty()) {
                dao.updateDeliveryStatusIfNotSeen(gid, ids, DeliveryStatus.DELIVERED, DeliveryStatus.SEEN)
            }
        }
    }

    private fun eventPriority(type: String): Int {
        return when {
            EventTypes.isChat(type) -> 0
            EventTypes.isMap(type) -> 1
            EventTypes.isLiveTrail(type) -> 2
            else -> 3
        }
    }

    private fun shouldBridgeToCloud(event: EventEntity): Boolean {
        return when (event.source) {
            EventSources.MESH -> isBleToCloudBridgeableType(event.type)
            EventSources.RELAY,
            EventSources.DTN,
            -> false
            else -> true
        }
    }

    private fun isBleToCloudBridgeableType(type: String): Boolean {
        return type == EventTypes.SYS_ACK ||
            EventTypes.isChat(type) ||
            EventTypes.isMap(type) ||
            EventTypes.isLiveTrail(type)
    }

    private fun shouldMarkInvalid(reason: String): Boolean {
        return when (reason) {
            "parse_core",
            "missing_sig",
            -> true
            else -> false
        }
    }

    private fun cleanupInvalidEvents(nowSeconds: Long) {
        val cutoff = nowSeconds - INVALID_RETENTION_SECONDS
        eventsDao.deleteInvalidBefore(cutoff)
    }

    private fun reconcileCoveredMeshBatches(lastPushRowId: Long) {
        if (lastPushRowId <= 0L) return
        val dao = outboundBatchDao ?: return
        val coveredBatchIds = dao.findCoveredBatchIdsByPushRowId(
            batchSource = EventSources.MESH,
            status = OutboundBatchStatus.QUEUED,
            excludedEventSource = EventSources.RELAY,
            lastPushRowId = lastPushRowId,
        )
        if (coveredBatchIds.isEmpty()) return
        val now = System.currentTimeMillis()
        coveredBatchIds.forEach { batchId ->
            dao.markSent(
                batchId = batchId,
                status = OutboundBatchStatus.SENT,
                updatedAt = now,
                sentAt = now,
            )
            dao.deleteBatch(batchId)
        }
        ActivityLog.add(
            label = "Relay push reconcile",
            source = "relay",
            message = "relay=$relayUrl marked_sent=${coveredBatchIds.size} lastPushRowId=$lastPushRowId",
        )
    }

    companion object {
        private const val INVALID_RETENTION_SECONDS = 7L * 24L * 60L * 60L
    }
}

data class RelayPushReport(
    val attempted: Int,
    val accepted: Int,
    val rejected: Int,
    val success: Boolean,
    val statusCode: Int,
    val errorMessage: String?,
    val errorCode: String?,
    val rejectedReasons: List<String>,
) {
    companion object {
        fun empty() = RelayPushReport(
            attempted = 0,
            accepted = 0,
            rejected = 0,
            success = true,
            statusCode = 0,
            errorMessage = null,
            errorCode = null,
            rejectedReasons = emptyList(),
        )
    }
}
