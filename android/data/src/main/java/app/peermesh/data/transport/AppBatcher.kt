package app.peermesh.data.transport

import android.util.Log
import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.AppBatchEnvelope
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.transport.EnvelopeBroadcaster
import app.peermesh.core.types.EventTypes
import app.peermesh.data.debug.ActivityLog
import app.peermesh.transport.ble.BleConfig
import app.peermesh.transport.ble.BleMeshTransport
import app.peermesh.security.crypto.AppBatchCrypto
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object AppBatcher {
    private const val MESSAGE_BATCH_WINDOW_MS = 1_000L
    private const val ACK_BATCH_WINDOW_MS = 10_000L
    internal const val MAP_BATCH_WINDOW_MS = 3_000L
    private const val TRAIL_BATCH_WINDOW_MS = 8_000L
    private const val MEMBERSHIP_CONTROL_BATCH_WINDOW_MS = 0L
    private const val OTHER_BATCH_WINDOW_MS = 5_000L
    internal const val MAX_BATCH_BYTES = 4 * 1024
    private const val BATCH_OVERHEAD_BYTES = 200
    internal const val HARD_MAX_PAYLOAD_BYTES = 2_400
    internal const val DEFERRED_BATCH_TTL_MS = 15 * 60_000L

    internal enum class BatchType { MESSAGE, ACK, MAP, TRAIL, MEMBERSHIP_CONTROL, OTHER }

    private data class PendingItem(
        val base64: String,
        val sizeBytes: Int,
        val eventId: String? = null,
        val targetDeviceIds: Set<String> = emptySet(),
    )

    private data class DeferredBatch(
        val batchId: String,
        val payload: ByteArray,
        val eventIds: List<String>,
        val deferredAtMs: Long,
        val targetDeviceIds: Set<String>,
        val reason: String,
    )

    private val lock = Any()
    private val batchScheduler = Executors.newSingleThreadScheduledExecutor()
    private val pending = mutableMapOf<BatchType, ArrayDeque<PendingItem>>(
        BatchType.MESSAGE to ArrayDeque(),
        BatchType.ACK to ArrayDeque(),
        BatchType.MAP to ArrayDeque(),
        BatchType.TRAIL to ArrayDeque(),
        BatchType.MEMBERSHIP_CONTROL to ArrayDeque(),
        BatchType.OTHER to ArrayDeque(),
    )
    private val pendingBytes = mutableMapOf(
        BatchType.MESSAGE to 0,
        BatchType.ACK to 0,
        BatchType.MAP to 0,
        BatchType.TRAIL to 0,
        BatchType.MEMBERSHIP_CONTROL to 0,
        BatchType.OTHER to 0,
    )
    private val activePendingEventIds = mutableSetOf<String>()
    private val deferredBatchesById = LinkedHashMap<String, DeferredBatch>()
    private val scheduled = mutableMapOf<BatchType, ScheduledFuture<*>?>(
        BatchType.MESSAGE to null,
        BatchType.ACK to null,
        BatchType.MAP to null,
        BatchType.TRAIL to null,
        BatchType.MEMBERSHIP_CONTROL to null,
        BatchType.OTHER to null,
    )

    fun enqueue(
        broadcaster: EnvelopeBroadcaster,
        gid: String,
        coreType: String,
        envelopeBytes: ByteArray,
        targetDeviceIds: Set<String> = emptySet(),
    ): Boolean {
        val type = batchTypeFor(coreType)
        val eventId = extractEventId(envelopeBytes)
        val encoded = Base64Url.encode(envelopeBytes)
        val item = PendingItem(
            base64 = encoded,
            sizeBytes = encoded.length + 3,
            eventId = eventId,
            targetDeviceIds = targetDeviceIds,
        )
        var shouldFlush = false
        synchronized(lock) {
            if (!eventId.isNullOrBlank() && activePendingEventIds.contains(eventId)) {
                Log.i("AppBatcher", "Skip duplicate pending sender event eventId=$eventId")
                ActivityLog.add(
                    label = "APP sender duplicate skipped",
                    source = "mesh",
                    message = "event_id=$eventId type=$coreType reason=active_pending_send",
                    gid = gid,
                    eventId = eventId,
                )
                return true
            }
            val currentBytes = pendingBytes[type] ?: 0
            val nextBytes = currentBytes + item.sizeBytes + BATCH_OVERHEAD_BYTES
            if (currentBytes > 0 && nextBytes > MAX_BATCH_BYTES) {
                shouldFlush = true
            } else {
                addItemLocked(type, item)
                if (type == BatchType.MEMBERSHIP_CONTROL) {
                    ActivityLog.add(
                        label = "Membership control batch enqueue",
                        source = "mesh",
                        message = "gid=$gid type=$coreType event_id=${eventId ?: "-"} targets=${targetDeviceIds.size}",
                        gid = gid,
                        eventId = eventId,
                    )
                }
                scheduleFlushLocked(type, broadcaster)
            }
        }
        if (shouldFlush) {
            flushOne(type, broadcaster)
            synchronized(lock) {
                addItemLocked(type, item)
                scheduleFlushLocked(type, broadcaster)
            }
        }
        return true
    }

    fun releaseDeferred(broadcaster: EnvelopeBroadcaster, reason: String) {
        val nowMs = System.currentTimeMillis()
        val toRelease = synchronized(lock) {
            pruneDeferredLocked(nowMs)
            if (deferredBatchesById.isEmpty()) return
            deferredBatchesById.values.toList().also { deferredBatchesById.clear() }
        }
        toRelease.forEach { batch ->
            ActivityLog.add(
                label = "APP batch dispatch released",
                source = "mesh",
                message = "batch_id=${batch.batchId} reason=$reason deferred_age_ms=${nowMs - batch.deferredAtMs} event_ids=${batch.eventIds.joinToString(",")}",
            )
            val queued = broadcastEnvelopeWithTargets(
                broadcaster = broadcaster,
                payload = batch.payload,
                targetDeviceIds = batch.targetDeviceIds,
            )
            if (queued) {
                ActivityLog.add(
                    label = "APP batch transport queued",
                    source = "mesh",
                    message = "batch_id=${batch.batchId} reason=$reason event_ids=${batch.eventIds.joinToString(",")}",
                )
            } else {
                ActivityLog.add(
                    label = "APP batch transport skipped",
                    source = "mesh",
                    message = "batch_id=${batch.batchId} reason=$reason skip_reason=broadcast_rejected event_ids=${batch.eventIds.joinToString(",")}",
                )
                synchronized(lock) {
                    deferredBatchesById[batch.batchId] = batch.copy(reason = "release_failed")
                }
            }
        }
    }

    private fun addItemLocked(type: BatchType, item: PendingItem) {
        pending[type]?.addLast(item)
        pendingBytes[type] = (pendingBytes[type] ?: 0) + item.sizeBytes
        item.eventId?.let { activePendingEventIds.add(it) }
    }

    private fun scheduleFlushLocked(type: BatchType, broadcaster: EnvelopeBroadcaster) {
        if (scheduled[type] != null) return
        val queue = pending[type] ?: return
        if (queue.isEmpty()) return
        val delayMs = batchWindowMs(type)
        ActivityLog.add(
            label = "APP batch window armed",
            source = "mesh",
            message = "type=$type delay_ms=$delayMs pending=${queue.size}",
        )
        scheduled[type] = batchScheduler.schedule({
            flushOne(type, broadcaster)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun flushOne(type: BatchType, broadcaster: EnvelopeBroadcaster) {
        val pendingCount = synchronized(lock) {
            val queue = pending[type]
            if (queue == null || queue.isEmpty()) {
                scheduled[type]?.cancel(false)
                scheduled[type] = null
                return
            }
            val count = queue.size
            scheduled[type]?.cancel(false)
            scheduled[type] = null
            count
        }
        ActivityLog.add(
            label = "APP batch flush start",
            source = "mesh",
            message = "type=$type pending=$pendingCount",
        )
        val batch = takeBatch(type)
        if (batch.isNotEmpty()) emitBatches(type, batch, broadcaster)
    }

    private fun takeBatch(type: BatchType): List<PendingItem> {
        val items = mutableListOf<PendingItem>()
        var bytes = 0
        synchronized(lock) {
            val queue = pending[type] ?: return emptyList()
            while (queue.isNotEmpty()) {
                val next = queue.first()
                val nextBytes = bytes + next.sizeBytes + BATCH_OVERHEAD_BYTES
                if (bytes > 0 && nextBytes > MAX_BATCH_BYTES) break
                queue.removeFirst()
                items.add(next)
                bytes += next.sizeBytes
                if (bytes + BATCH_OVERHEAD_BYTES >= HARD_MAX_PAYLOAD_BYTES) break
            }
            if (bytes > 0) {
                pendingBytes[type] = (pendingBytes[type] ?: 0) - bytes
                if (pendingBytes[type]!! < 0) pendingBytes[type] = 0
            }
        }
        return items
    }

    private fun emitBatches(type: BatchType, items: List<PendingItem>, broadcaster: EnvelopeBroadcaster) {
        var current = mutableListOf<PendingItem>()
        var currentBytes = 0
        fun flushCurrent() {
            if (current.isEmpty()) return
            val batch = AppBatchEnvelope(
                v = 1,
                ts = System.currentTimeMillis(),
                sender_relay_id = localSenderRelayId(),
                items = current.map { it.base64 },
            )
            val signed = AppBatchCrypto.sign(batch)
            val json = CanonicalJson.json.encodeToString(AppBatchEnvelope.serializer(), signed)
            val bytes = json.toByteArray(Charsets.UTF_8)
            val batchId = batchId(signed)
            val eventIds = current.mapNotNull { it.eventId }
            val targetDeviceIds = current.flatMap { it.targetDeviceIds }.toCollection(LinkedHashSet())
            if (bytes.size > HARD_MAX_PAYLOAD_BYTES) {
                Log.w("AppBatcher", "Drop batch payload too large bytes=${bytes.size} items=${current.size}")
                ActivityLog.add(
                    label = "APP batch transport skipped",
                    source = "mesh",
                    message = "type=$type reason=payload_too_large payload_bytes=${bytes.size} items=${current.size} event_ids=${eventIds.joinToString(",")}",
                )
                current.forEach { pendingItem -> pendingItem.eventId?.let { activePendingEventIds.remove(it) } }
                current.clear()
                currentBytes = 0
                return
            }
            ActivityLog.add(
                label = "APP batch emit",
                source = "mesh",
                message = "batch_id=$batchId count=${current.size} payload_bytes=${bytes.size} item_sizes=${current.joinToString(",") { it.sizeBytes.toString() }} targets=${targetDeviceIds.joinToString(",").ifBlank { "auto" }} event_ids=${eventIds.joinToString(",")}",
            )
            if (type == BatchType.MEMBERSHIP_CONTROL) {
                ActivityLog.add(
                    label = "Membership control batch emit",
                    source = "mesh",
                    message = "batch_id=$batchId payload_bytes=${bytes.size} targets=${targetDeviceIds.joinToString(",").ifBlank { "auto" }} event_ids=${eventIds.joinToString(",")}",
                )
            }
            val queued = broadcastEnvelopeWithTargets(
                broadcaster = broadcaster,
                payload = bytes,
                targetDeviceIds = targetDeviceIds,
            )
            if (queued) {
                ActivityLog.add(
                    label = "APP batch transport queued",
                    source = "mesh",
                    message = "batch_id=$batchId reason=emit targets=${targetDeviceIds.joinToString(",").ifBlank { "auto" }} event_ids=${eventIds.joinToString(",")}",
                )
            } else {
                ActivityLog.add(
                    label = "APP batch transport skipped",
                    source = "mesh",
                    message = "batch_id=$batchId reason=emit skip_reason=broadcast_rejected event_ids=${eventIds.joinToString(",")}",
                )
                synchronized(lock) {
                    deferredBatchesById[batchId] = DeferredBatch(
                        batchId = batchId,
                        payload = bytes,
                        eventIds = eventIds,
                        deferredAtMs = System.currentTimeMillis(),
                        targetDeviceIds = targetDeviceIds,
                        reason = "broadcast_rejected",
                    )
                }
                ActivityLog.add(
                    label = "APP batch dispatch deferred",
                    source = "mesh",
                    message = "batch_id=$batchId reason=broadcast_rejected event_ids=${eventIds.joinToString(",")}",
                )
            }
            current.forEach { pendingItem -> pendingItem.eventId?.let { activePendingEventIds.remove(it) } }
            current.clear()
            currentBytes = 0
        }
        items.forEach { item ->
            val itemSize = item.sizeBytes
            if (current.isNotEmpty() && current.size >= maxItemsPerAppBatch(type)) {
                flushCurrent()
            }
            val nextBytes = currentBytes + itemSize + BATCH_OVERHEAD_BYTES
            if (current.isNotEmpty() && nextBytes > MAX_BATCH_BYTES) {
                flushCurrent()
            }
            current.add(item)
            currentBytes += itemSize
            if (currentBytes + BATCH_OVERHEAD_BYTES >= HARD_MAX_PAYLOAD_BYTES) {
                flushCurrent()
            }
        }
        flushCurrent()
    }

    private fun broadcastEnvelopeWithTargets(
        broadcaster: EnvelopeBroadcaster,
        payload: ByteArray,
        targetDeviceIds: Set<String>,
    ): Boolean {
        if (targetDeviceIds.isEmpty()) {
            return broadcaster.broadcastEnvelope(payload)
        }
        val directedBroadcaster = broadcaster as? BleMeshTransport
        if (directedBroadcaster == null) {
            ActivityLog.add(
                label = "APP batch directed fallback",
                source = "mesh",
                message = "reason=broadcaster_without_directed_api targets=${targetDeviceIds.joinToString(",")}",
            )
            return broadcaster.broadcastEnvelope(payload)
        }
        return directedBroadcaster.broadcastEnvelopeToDeviceIds(payload, targetDeviceIds)
    }

    private fun localSenderRelayId(): String? {
        val advertised = BleConfig.advertisedDeviceId ?: return null
        if (advertised.isEmpty()) return null
        return advertised.joinToString("") { "%02X".format(it) }
    }

    private fun maxItemsPerAppBatch(type: BatchType): Int {
        return when (type) {
            // ACK envelopes are already cumulative at MeshAckManager. Keeping one
            // encrypted ACK envelope per APP_BATCH avoids oversize drops.
            BatchType.ACK -> 1
            BatchType.MEMBERSHIP_CONTROL -> 1
            else -> Int.MAX_VALUE
        }
    }

    private fun pruneDeferredLocked(nowMs: Long) {
        val iterator = deferredBatchesById.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().value.deferredAtMs > DEFERRED_BATCH_TTL_MS) {
                iterator.remove()
            }
        }
    }

    private fun extractEventId(envelopeBytes: ByteArray): String? {
        return runCatching {
            val envelope = CanonicalJson.json.decodeFromString(
                EventEnvelope.serializer(),
                envelopeBytes.toString(Charsets.UTF_8),
            )
            envelope.mid
        }.getOrNull()
    }

    private fun batchId(batch: AppBatchEnvelope): String {
        val mac = batch.app_sig?.mac
        if (!mac.isNullOrBlank()) return mac.take(12)
        return "ts:${batch.ts}"
    }

    internal fun batchTypeFor(coreType: String): BatchType {
        return when {
            coreType == EventTypes.SYS_ACK -> BatchType.ACK
            EventTypes.isPriorityControl(coreType) -> BatchType.MEMBERSHIP_CONTROL
            EventTypes.isChat(coreType) -> BatchType.MESSAGE
            EventTypes.isMap(coreType) -> BatchType.MAP
            EventTypes.isLiveTrail(coreType) -> BatchType.TRAIL
            else -> BatchType.OTHER
        }
    }

    internal fun batchWindowMs(type: BatchType): Long {
        return when (type) {
            BatchType.MESSAGE -> MESSAGE_BATCH_WINDOW_MS
            BatchType.ACK -> ACK_BATCH_WINDOW_MS
            BatchType.MAP -> MAP_BATCH_WINDOW_MS
            BatchType.TRAIL -> TRAIL_BATCH_WINDOW_MS
            BatchType.MEMBERSHIP_CONTROL -> MEMBERSHIP_CONTROL_BATCH_WINDOW_MS
            BatchType.OTHER -> OTHER_BATCH_WINDOW_MS
        }
    }

    /** Deterministic package-internal seam for contract tests; production scheduling is unchanged. */
    internal fun flushForTest(coreType: String, broadcaster: EnvelopeBroadcaster) {
        flushOne(batchTypeFor(coreType), broadcaster)
    }

    /** Clears singleton queues between local JVM tests without affecting the runtime API. */
    internal fun resetForTest() {
        synchronized(lock) {
            scheduled.values.forEach { it?.cancel(false) }
            scheduled.keys.forEach { scheduled[it] = null }
            pending.values.forEach { it.clear() }
            pendingBytes.keys.forEach { pendingBytes[it] = 0 }
            activePendingEventIds.clear()
            deferredBatchesById.clear()
        }
    }
}
