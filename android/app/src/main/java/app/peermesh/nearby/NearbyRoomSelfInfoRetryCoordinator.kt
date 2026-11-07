package app.peermesh.nearby

import android.content.Context
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.transport.adapter.NearbyForegroundDiagnosticLabels
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object NearbyRoomSelfInfoRetryCoordinator {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nearby-room-self-info-retry").apply { isDaemon = true }
    }
    private val drainInFlight = AtomicBoolean(false)
    private val pendingKeys = LinkedHashSet<RetryKey>()
    private val acknowledgedRevisions = ConcurrentHashMap<String, Long>()
    private val legacyAcknowledgements = ConcurrentHashMap<String, Long>()
    private val lock = Any()

    fun enqueue(
        context: Context,
        snapshotEnvelope: NearbyRoomTransportEnvelope,
    ) {
        require(snapshotEnvelope.kind == NearbyRoomTransportEnvelope.KIND_SELF_INFO_V1)
        val targetDeviceId = snapshotEnvelope.target_device_id?.takeIf { it.isNotBlank() } ?: return
        val revision = snapshotEnvelope.self_info_revision?.takeIf(NearbySelfInfoProtocol::isValidRevision) ?: return
        val key = RetryKey(
            roomId = snapshotEnvelope.room_id,
            targetDeviceId = targetDeviceId,
            profileUpdatedAtMs = snapshotEnvelope.profile_updated_at_ms,
            selfInfoRevision = revision,
            snapshotEnvelope = snapshotEnvelope,
            runtimeIdentity = NearbyForegroundRuntimeCoordinator.runtimeIdentityForRoom(snapshotEnvelope.room_id),
        )
        val queued = synchronized(lock) { pendingKeys.add(key) }
        ActivityLog.add(
            label = "Nearby room self info retry queued",
            source = "nearby",
            message = "room_id=${key.roomId} target=$targetDeviceId revision=${revision.take(8)} queued=$queued",
        )
        drain(context.applicationContext)
    }

    fun acknowledge(
        roomId: String,
        targetDeviceId: String,
        profileUpdatedAtMs: Long?,
        selfInfoRevision: String?,
    ) {
        val revision = selfInfoRevision?.takeIf(NearbySelfInfoProtocol::isValidRevision)
        if (revision != null) {
            acknowledgedRevisions[revisionAckKey(roomId, targetDeviceId, revision)] = System.currentTimeMillis()
        } else {
            val updatedAtMs = profileUpdatedAtMs ?: return
            legacyAcknowledgements[legacyAckKey(roomId, targetDeviceId, updatedAtMs)] = System.currentTimeMillis()
        }
        ActivityLog.add(
            label = "Nearby room self info retry acked",
            source = "nearby",
            message = "room_id=$roomId target=$targetDeviceId revision=${revision?.take(8) ?: "legacy"}",
        )
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
                    retryUntilAcknowledged(appContext, next)
                }
            } finally {
                drainInFlight.set(false)
                if (synchronized(lock) { pendingKeys.isNotEmpty() }) drain(appContext)
            }
        }
    }

    private fun retryUntilAcknowledged(context: Context, key: RetryKey) {
        val repository = NearbyRoomRepository(context)
        val transportManager = NearbyRoomTransportManager(context, repository)
        while (true) {
            if (!NearbyForegroundRuntimeCoordinator.isContextWorkerIdentityCurrent(key.roomId, key.runtimeIdentity)) {
                ActivityLog.add(label = NearbyForegroundDiagnosticLabels.WORKER_STALE, source = "nearby", message = "worker=self_info_retry room_id=${key.roomId} result=drop reason=identity_mismatch")
                return
            }
            if (isAcknowledged(key)) return
            val nowMs = System.currentTimeMillis()
            val room = repository.getRoom(key.roomId)
            if (room == null || room.state != NearbyRoomEntity.STATE_ACTIVE || room.expires_at_ms <= nowMs) return
            val sent = transportManager.sendSelfInfoSnapshot(key.snapshotEnvelope)
            ActivityLog.add(
                label = "Nearby room self info retry attempt",
                source = "nearby",
                message = "room_id=${key.roomId} target=${key.targetDeviceId} revision=${key.selfInfoRevision.take(8)} sent=$sent",
            )
            Thread.sleep(RETRY_INTERVAL_MS)
        }
    }

    private fun isAcknowledged(key: RetryKey): Boolean {
        if (acknowledgedRevisions.containsKey(revisionAckKey(key.roomId, key.targetDeviceId, key.selfInfoRevision))) return true
        val profileUpdatedAtMs = key.profileUpdatedAtMs ?: return false
        return legacyAcknowledgements.containsKey(legacyAckKey(key.roomId, key.targetDeviceId, profileUpdatedAtMs))
    }

    private fun revisionAckKey(roomId: String, targetDeviceId: String, revision: String): String =
        "$roomId|$targetDeviceId|revision|$revision"

    private fun legacyAckKey(roomId: String, targetDeviceId: String, profileUpdatedAtMs: Long): String =
        "$roomId|$targetDeviceId|legacy|$profileUpdatedAtMs"

    private data class RetryKey(
        val roomId: String,
        val targetDeviceId: String,
        val profileUpdatedAtMs: Long?,
        val selfInfoRevision: String,
        val snapshotEnvelope: NearbyRoomTransportEnvelope,
        val runtimeIdentity: String?,
    )

    private const val RETRY_INTERVAL_MS = 15_000L
}
