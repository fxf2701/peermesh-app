package app.peermesh.nearby

import android.content.Context
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.transport.adapter.NearbyForegroundDiagnosticLabels
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object NearbyRoomOwnerMessageRetryCoordinator {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nearby-room-owner-send-retry").apply { isDaemon = true }
    }
    private val drainInFlight = AtomicBoolean(false)
    private val pendingKeys = LinkedHashSet<RetryKey>()
    private val lock = Any()

    fun enqueue(context: Context, roomId: String, messageId: String) {
        val queued = synchronized(lock) {
            pendingKeys.add(
                RetryKey(
                    roomId = roomId,
                    messageId = messageId,
                    runtimeIdentity = NearbyForegroundRuntimeCoordinator.runtimeIdentityForRoom(roomId),
                )
            )
        }
        ActivityLog.add(
            label = "Nearby room owner send retry queued",
            source = "nearby",
            message = "room_id=$roomId message_id=$messageId queued=$queued",
        )
        drain(context.applicationContext)
    }

    private fun drain(context: Context) {
        val appContext = context.applicationContext
        if (!drainInFlight.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (true) {
                    val next = synchronized(lock) {
                        pendingKeys.firstOrNull()?.also { pendingKeys.remove(it) }
                    } ?: return@execute
                    retryUntilDelivered(appContext, next)
                }
            } finally {
                drainInFlight.set(false)
                val hasPending = synchronized(lock) { pendingKeys.isNotEmpty() }
                if (hasPending) {
                    drain(appContext)
                }
            }
        }
    }

    private fun retryUntilDelivered(context: Context, key: RetryKey) {
        val repository = NearbyRoomRepository(context)
        val transportManager = NearbyRoomTransportManager(context, repository)
        while (true) {
            if (!NearbyForegroundRuntimeCoordinator.isContextWorkerIdentityCurrent(key.roomId, key.runtimeIdentity)) {
                val reason = "identity_mismatch"
                ActivityLog.add(label = NearbyForegroundDiagnosticLabels.WORKER_STALE, source = "nearby", message = "worker=owner_message_retry room_id=${key.roomId} captured_identity=${key.runtimeIdentity?.take(8) ?: "none"} current_identity=${NearbyForegroundRuntimeCoordinator.runtimeIdentityForRoom(key.roomId)?.take(8) ?: "none"} result=drop reason=$reason")
                ActivityLog.add(
                    label = "Nearby room owner send retry stopped",
                    source = "nearby",
                    message = "room_id=${key.roomId} message_id=${key.messageId} reason=stale_runtime_identity",
                )
                return
            }
            val nowMs = System.currentTimeMillis()
            ActivityLog.add(label = NearbyForegroundDiagnosticLabels.WORKER_EXECUTE, source = "nearby", message = "worker=owner_message_retry room_id=${key.roomId} captured_scope=${if (key.runtimeIdentity == null) "NO_FOREGROUND_SESSION" else "FOREGROUND_SESSION"} current_scope=${if (NearbyForegroundRuntimeCoordinator.runtimeIdentityForRoom(key.roomId) == null) "NO_FOREGROUND_SESSION" else "FOREGROUND_SESSION"} result=execute")
            val room = repository.getRoom(key.roomId)
            if (room == null ||
                !room.is_local_owner ||
                room.state != NearbyRoomEntity.STATE_ACTIVE ||
                room.expires_at_ms <= nowMs
            ) {
                ActivityLog.add(
                    label = "Nearby room owner send retry stopped",
                    source = "nearby",
                    message = "room_id=${key.roomId} message_id=${key.messageId} reason=room_inactive",
                )
                return
            }
            val message = repository.findMessage(key.roomId, key.messageId)
            if (message == null) {
                ActivityLog.add(
                    label = "Nearby room owner send retry stopped",
                    source = "nearby",
                    message = "room_id=${key.roomId} message_id=${key.messageId} reason=message_missing",
                )
                return
            }
            if (message.delivery_state == NearbyRoomMessageEntity.DELIVERY_SENT) {
                return
            }
            val targetDeviceIds = NearbyRoomPeerSnapshotStore
                .nearbyDeviceIdsForRoom(roomId = key.roomId, limit = MAX_TARGETS, nowMs = nowMs)
                .filterNot { it == repository.localDeviceId() }
                .toSet()
            val sent = if (targetDeviceIds.isEmpty()) {
                false
            } else {
                transportManager.sendEncryptedMessage(
                    message = message,
                    targetDeviceIds = targetDeviceIds,
                )
            }
            ActivityLog.add(
                label = "Nearby room owner send retry attempt",
                source = "nearby",
                message =
                    "room_id=${key.roomId} message_id=${key.messageId} targets=${targetDeviceIds.size} sent=$sent",
            )
            if (sent) {
                repository.markMessageSent(message.room_id, message.message_id)
                NearbyRoomRelayRecoveryManager.onMessageAvailable(
                    context = context,
                    message = message,
                    lastHopSenderDeviceId = null,
                )
                ActivityLog.add(
                    label = "Nearby room owner send retry delivered",
                    source = "nearby",
                    message = "room_id=${key.roomId} message_id=${key.messageId}",
                )
                return
            }
            Thread.sleep(RETRY_INTERVAL_MS)
        }
    }

    private data class RetryKey(
        val roomId: String,
        val messageId: String,
        val runtimeIdentity: String?,
    )

    private const val MAX_TARGETS = 10
    private const val RETRY_INTERVAL_MS = 2_000L
}
