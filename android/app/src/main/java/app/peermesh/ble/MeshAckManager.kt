package app.peermesh.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.peermesh.DeviceIdProvider
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.id.EventIdFactory
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.ingest.IngestNotifier
import app.peermesh.data.membership.GroupMembershipPolicy
import app.peermesh.data.reducer.JsonAccess
import app.peermesh.data.transport.EnvelopeSender
import app.peermesh.security.crypto.SecureKeyStore
import kotlinx.serialization.decodeFromString
import java.util.concurrent.ConcurrentHashMap

object MeshAckManager : IngestNotifier.Listener {
    private const val TAG = "MeshAckManager"
    private const val RETRY_DELAY_MS = 5000L
    private const val MAX_RETRIES_PER_WINDOW = 5
    private const val MAX_RETRIES_TOTAL = 60
    private const val RESEND_TTL_MS = 60 * 60_000L
    private const val NEARBY_POLL_MS = 5000L
    private const val ACK_BATCH_WINDOW_MS = 10_000L
    private const val ACK_BATCH_MAX_TARGETS = 24
    private const val ACK_TARGETS_PER_EVENT = 8
    private const val ACK_RECEIVED = "received"
    private const val ACK_SEEN = "seen"
    private const val SYS_ACK_SEND_ENABLED = true

    private data class Pending(
        val gid: String,
        val core: EventCore,
        var attempts: Int,
        var scheduled: Runnable?,
        val firstSentMs: Long,
        var windowAttempts: Int,
        var windowId: Int,
    )

    private data class AckBatch(
        val gid: String,
        val ackType: String,
        val targetDeviceId: String?,
        val targetIds: LinkedHashSet<String> = LinkedHashSet(),
        var scheduled: Runnable? = null,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val seenSent = ConcurrentHashMap<String, Boolean>()
    private val ackBatches = ConcurrentHashMap<String, AckBatch>()
    private val handler = Handler(Looper.getMainLooper())
    private val ackLock = Any()
    @Volatile private var appContext: Context? = null
    @Volatile private var lastNearbyCount: Int = -1
    @Volatile private var currentWindowId: Int = 0
    @Volatile private var nearbyWatcherScheduled = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        IngestNotifier.add(this)
        startNearbyWatcher()
    }

    fun enqueuePending(gid: String, core: EventCore) {
        if (EventTypes.isChat(core.type)) {
            ActivityLog.add(
                label = "Mesh ACK legacy resend skipped",
                source = "mesh",
                message = "reason=chat_moved_to_persistent_queue event_id=${core.id} type=${core.type}",
                eventId = core.id,
                gid = gid,
            )
            return
        }
        val key = core.id
        if (pending.containsKey(key)) return
        val pendingEntry = Pending(
            gid = gid,
            core = core,
            attempts = 0,
            scheduled = null,
            firstSentMs = System.currentTimeMillis(),
            windowAttempts = 0,
            windowId = currentWindowId,
        )
        pending[key] = pendingEntry
        scheduleRetry(pendingEntry)
    }

    fun sendSeenAck(gid: String, targetEventId: String) {
        if (!SYS_ACK_SEND_ENABLED) {
            ActivityLog.add(
                label = "SYS ACK send locked",
                source = "mesh",
                message = "reason=seen_ack_debug_lock target_event_id=$targetEventId",
                gid = gid,
                eventId = targetEventId,
            )
            return
        }
        val context = appContext ?: return
        Thread {
            if (isActorRemoved(context, gid)) {
                ActivityLog.add(
                    label = "SYS ACK blocked removed member",
                    source = "mesh",
                    message = "reason=seen_ack_removed_member target_event_id=$targetEventId",
                    gid = gid,
                    eventId = targetEventId,
                )
                return@Thread
            }
            if (seenSent.putIfAbsent(targetEventId, true) != null) return@Thread
            enqueueAck(gid, targetEventId, ACK_SEEN, targetDeviceId = null)
        }.start()
    }

    override fun onEventInserted(gid: String, eventId: String) {
        val context = appContext ?: return
        Thread {
            val db = AppDatabaseProvider.get(context)
            val entity = db.eventsDao().findById(gid, eventId) ?: return@Thread
            val core = runCatching {
                CanonicalJson.json.decodeFromString<EventCore>(entity.payload_json)
            }.getOrNull() ?: return@Thread
            when (core.type) {
                EventTypes.CHAT_MSG, EventTypes.CHAT_REPLY -> {
                    if (core.actor.did == DeviceIdProvider.get(context)) return@Thread
                    enqueueAck(gid, core.id, ACK_RECEIVED, targetDeviceId = core.actor.did)
                }
                EventTypes.SYS_ACK -> {
                    val targets = JsonAccess.stringListValue(core.data, "target_ids")
                        .ifEmpty { listOfNotNull(JsonAccess.stringValue(core.data, "target_id") ?: core.ref.target) }
                    targets.forEach { targetId ->
                        checkPendingStop(gid, targetId)
                    }
                }
            }
        }.start()
    }

    private fun scheduleRetry(pendingEntry: Pending) {
        pendingEntry.scheduled?.let { handler.removeCallbacks(it) }
        val elapsedMs = System.currentTimeMillis() - pendingEntry.firstSentMs
        if (elapsedMs >= RESEND_TTL_MS) {
            pending.remove(pendingEntry.core.id)
            return
        }
        if (pendingEntry.attempts >= MAX_RETRIES_TOTAL) {
            pending.remove(pendingEntry.core.id)
            return
        }
        if (pendingEntry.windowId != currentWindowId) {
            pendingEntry.windowId = currentWindowId
            pendingEntry.windowAttempts = 0
        }
        if (pendingEntry.windowAttempts >= MAX_RETRIES_PER_WINDOW) {
            pendingEntry.scheduled = null
            return
        }
        val runnable = Runnable {
            val context = appContext ?: return@Runnable
            Thread {
                if (shouldStopResend(context, pendingEntry.gid, pendingEntry.core.id)) {
                    pending.remove(pendingEntry.core.id)
                    return@Thread
                }
                pendingEntry.attempts += 1
                pendingEntry.windowAttempts += 1
                val ok = sendEvent(context, pendingEntry.gid, pendingEntry.core)
                Log.i(TAG, "Resend attempt=${pendingEntry.attempts} eventId=${pendingEntry.core.id} ok=$ok")
                scheduleRetry(pendingEntry)
            }.start()
        }
        pendingEntry.scheduled = runnable
        handler.postDelayed(runnable, RETRY_DELAY_MS)
    }

    private fun checkPendingStop(gid: String, targetEventId: String) {
        val context = appContext ?: return
        Thread {
            if (shouldStopResend(context, gid, targetEventId)) {
                pending.remove(targetEventId)?.scheduled?.let { handler.removeCallbacks(it) }
            }
        }.start()
    }

    private fun shouldStopResend(context: Context, gid: String, targetEventId: String): Boolean {
        val db = AppDatabaseProvider.get(context)
        val message = db.chatDao().findMessage(gid, targetEventId) ?: return false
        val nearby = BleMeshRuntime.get(context).nearbyDeviceCount()
        val required = maxOf(1, minOf(4, nearby))
        return message.received_group_ack_count >= 1 && message.received_ack_count >= required
    }

    private fun startNearbyWatcher() {
        if (nearbyWatcherScheduled) return
        nearbyWatcherScheduled = true
        val runnable = object : Runnable {
            override fun run() {
                val context = appContext
                if (context != null) {
                    val count = BleMeshRuntime.get(context).nearbyDeviceCount()
                    if (count != lastNearbyCount) {
                        val previous = lastNearbyCount
                        lastNearbyCount = count
                        ActivityLog.add(
                            label = "Nearby changed",
                            source = "mesh",
                            message = "from=$previous to=$count",
                        )
                        currentWindowId += 1
                        if (count > 0) {
                            pending.values.forEach { scheduleRetry(it) }
                        }
                    }
                }
                handler.postDelayed(this, NEARBY_POLL_MS)
            }
        }
        handler.postDelayed(runnable, NEARBY_POLL_MS)
    }

    private fun enqueueAck(gid: String, targetEventId: String, ackType: String, targetDeviceId: String?) {
        if (!SYS_ACK_SEND_ENABLED) {
            ActivityLog.add(
                label = "SYS ACK send locked",
                source = "mesh",
                message = "reason=${ackType}_ack_debug_lock target_event_id=$targetEventId target_device_id=${targetDeviceId ?: "auto"}",
                gid = gid,
                eventId = targetEventId,
            )
            return
        }
        val context = appContext ?: return
        Thread {
            if (isActorRemoved(context, gid)) {
                ActivityLog.add(
                    label = "SYS ACK blocked removed member",
                    source = "mesh",
                    message = "reason=enqueue_removed_member ack_type=$ackType target_event_id=$targetEventId target_device_id=${targetDeviceId ?: "auto"}",
                    gid = gid,
                    eventId = targetEventId,
                )
                return@Thread
            }
            val normalizedTargetDeviceId = targetDeviceId?.takeIf { it.isNotBlank() }
            val batchKey = "$gid|$ackType|${normalizedTargetDeviceId ?: "auto"}"
            synchronized(ackLock) {
                val batch = ackBatches.getOrPut(batchKey) {
                    AckBatch(
                        gid = gid,
                        ackType = ackType,
                        targetDeviceId = normalizedTargetDeviceId,
                    )
                }
                batch.targetIds.add(targetEventId)
                if (batch.targetIds.size >= ACK_BATCH_MAX_TARGETS) {
                    flushAckBatchLocked(batchKey)
                    return@Thread
                }
                if (batch.scheduled == null) {
                    val runnable = Runnable {
                        Thread {
                            synchronized(ackLock) {
                                flushAckBatchLocked(batchKey)
                            }
                        }.start()
                    }
                    batch.scheduled = runnable
                    handler.postDelayed(runnable, ACK_BATCH_WINDOW_MS)
                }
            }
        }.start()
    }

    private fun flushAckBatchLocked(batchKey: String) {
        val context = appContext ?: return
        val batch = ackBatches.remove(batchKey) ?: return
        batch.scheduled?.let { handler.removeCallbacks(it) }
        if (batch.targetIds.isEmpty()) return
        if (isActorRemoved(context, batch.gid)) {
            ActivityLog.add(
                label = "SYS ACK blocked removed member",
                source = "mesh",
                message = "reason=flush_removed_member ack_type=${batch.ackType} targets=${batch.targetIds.size} target_device_id=${batch.targetDeviceId ?: "auto"}",
                gid = batch.gid,
            )
            return
        }
        if (!SYS_ACK_SEND_ENABLED) {
            ActivityLog.add(
                label = "SYS ACK send locked",
                source = "mesh",
                message = "reason=flush_debug_lock ack_type=${batch.ackType} targets=${batch.targetIds.size} target_device_id=${batch.targetDeviceId ?: "auto"}",
                gid = batch.gid,
            )
            return
        }
        val actorDid = DeviceIdProvider.get(context)
        val nowSeconds = System.currentTimeMillis() / 1000L
        val lamport = System.currentTimeMillis()
        batch.targetIds.toList()
            .chunked(ACK_TARGETS_PER_EVENT)
            .forEach { targets ->
                val eventId = EventIdFactory.generate(
                    batch.gid,
                    actorDid,
                    nowSeconds,
                    EventIdFactory.generateNonce(),
                )
                val core = EventFactory.sysAck(
                    eventId = eventId,
                    actorDid = actorDid,
                    createdSeconds = nowSeconds,
                    lamport = lamport,
                    targetEventId = targets.first(),
                    targetEventIds = targets,
                    ackType = batch.ackType,
                )
                val ok = sendEvent(context, batch.gid, core, batch.targetDeviceId)
                if (!ok) {
                    Log.w(TAG, "ACK batch send failed gid=${batch.gid} type=${batch.ackType} targetDeviceId=${batch.targetDeviceId} targets=${targets.size}")
                }
            }
    }

    private fun sendEvent(context: Context, gid: String, core: EventCore, targetDeviceId: String? = null): Boolean {
        if (isActorRemoved(context, gid)) {
            ActivityLog.add(
                label = "SYS ACK blocked removed member",
                source = "mesh",
                message = "reason=send_removed_member ack_type=${JsonAccess.stringValue(core.data, "ack_type") ?: "unknown"} target_device_id=${targetDeviceId ?: "auto"}",
                gid = gid,
                eventId = core.id,
            )
            return false
        }
        val keyStore = SecureKeyStore(context)
        val groupKey = keyStore.loadGroupKey(gid) ?: return false
        val sender = EnvelopeSender(
            broadcaster = BleMeshRuntime.get(context),
            groupKeyLoader = { _ -> groupKey }
        )
        return sender.send(
            gid = gid,
            core = core,
            targetDeviceIds = targetDeviceId?.let { setOf(it) } ?: emptySet(),
        )
    }

    private fun isActorRemoved(context: Context, gid: String): Boolean {
        val actorDid = DeviceIdProvider.get(context)
        val db = AppDatabaseProvider.get(context)
        return GroupMembershipPolicy.isPeerRemoved(db, gid, actorDid)
    }
}
