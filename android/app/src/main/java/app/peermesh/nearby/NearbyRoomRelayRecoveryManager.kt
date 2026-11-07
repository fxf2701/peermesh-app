package app.peermesh.nearby

import android.content.Context
import app.peermesh.data.db.entity.NearbyRoomEntity
import app.peermesh.data.db.entity.NearbyRoomMessageEntity
import app.peermesh.data.db.entity.NearbyRoomRelayOfferEntity
import app.peermesh.data.db.entity.NearbyRoomRelayWantEntity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object NearbyRoomRelayRecoveryManager {
    private val offerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nearby-room-relay-offer").apply { isDaemon = true }
    }
    private val wantExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nearby-room-relay-want").apply { isDaemon = true }
    }
    private val offerDrainInFlight = AtomicBoolean(false)
    private val wantDrainInFlight = AtomicBoolean(false)

    fun onMessageAvailable(
        context: Context,
        message: NearbyRoomMessageEntity,
        lastHopSenderDeviceId: String?,
    ) {
        val appContext = context.applicationContext
        val repository = NearbyRoomRepository(appContext)
        val localDeviceId = repository.localDeviceId()
        val room = repository.getRoom(message.room_id) ?: return
        if (room.state != NearbyRoomEntity.STATE_ACTIVE) return
        val nearbyTargets = NearbyRoomPeerSnapshotStore
            .nearbyDeviceIds(limit = MAX_NEARBY_TARGETS)
            .filterNot { target ->
                target == localDeviceId ||
                    target == (message.owner_device_id ?: message.sender_device_id) ||
                    (lastHopSenderDeviceId != null && target == lastHopSenderDeviceId)
            }
        if (nearbyTargets.isEmpty()) return
        repository.enqueueRelayOffers(
            roomId = message.room_id,
            fullMessageId = message.message_id,
            targetDeviceIds = nearbyTargets,
            expiresAtMs = minOf(message.ttl_expires_at_ms, room.expires_at_ms),
        )
        wakeOfferWorker(appContext)
    }

    fun onMessageOfferReceived(context: Context, envelope: NearbyRoomTransportEnvelope) {
        val appContext = context.applicationContext
        val repository = NearbyRoomRepository(appContext)
        val room = repository.getRoom(envelope.room_id) ?: return
        if (room.state != NearbyRoomEntity.STATE_ACTIVE) return
        val compactMessageId = envelope.message_compact_id ?: return
        if (repository.findMessageByCompactId(envelope.room_id, compactMessageId) != null) {
            return
        }
        val existingWant = repository.getRelayWant(envelope.room_id, compactMessageId)
        if (existingWant?.state == NearbyRoomRelayWantEntity.STATE_PENDING ||
            existingWant?.state == NearbyRoomRelayWantEntity.STATE_REQUESTING ||
            existingWant?.state == NearbyRoomRelayWantEntity.STATE_RESOLVED
        ) {
            return
        }
        repository.upsertRelayWant(
            roomId = envelope.room_id,
            compactMessageId = compactMessageId,
            sourceDeviceId = envelope.sender_device_id,
            ownerDeviceId = envelope.origin_device_id,
            expiresAtMs = room.expires_at_ms,
        )
        wakeWantWorker(appContext)
    }

    fun onMessageFetchReceived(context: Context, envelope: NearbyRoomTransportEnvelope) {
        val appContext = context.applicationContext
        val compactMessageId = envelope.message_compact_id ?: return
        val message = NearbyRoomRepository(appContext)
            .findMessageByCompactId(envelope.room_id, compactMessageId) ?: return
        NearbyRoomTransportManager(appContext).sendMessageData(
            message = message,
            targetDeviceId = envelope.sender_device_id,
        )
    }

    fun onMessageDataResolved(
        context: Context,
        envelope: NearbyRoomTransportEnvelope,
        message: NearbyRoomMessageEntity,
    ) {
        val appContext = context.applicationContext
        val compactMessageId = envelope.message_compact_id ?: NearbyRoomRelayIdCodec.compactMessageId(message.message_id)
        val repository = NearbyRoomRepository(appContext)
        repository.markRelayWantResolved(
            roomId = envelope.room_id,
            compactMessageId = compactMessageId,
            fullMessageId = message.message_id,
        )
        onMessageAvailable(
            context = appContext,
            message = message,
            lastHopSenderDeviceId = envelope.sender_device_id,
        )
    }

    fun wakeOfferWorker(context: Context) {
        val appContext = context.applicationContext
        if (!offerDrainInFlight.compareAndSet(false, true)) return
        offerExecutor.execute {
            try {
                drainOffers(appContext)
            } finally {
                offerDrainInFlight.set(false)
                if (NearbyRoomRepository(appContext).listPendingRelayOffers(limit = 1).isNotEmpty()) {
                    wakeOfferWorker(appContext)
                }
            }
        }
    }

    fun wakeWantWorker(context: Context) {
        val appContext = context.applicationContext
        if (!wantDrainInFlight.compareAndSet(false, true)) return
        wantExecutor.execute {
            try {
                drainWants(appContext)
            } finally {
                wantDrainInFlight.set(false)
                if (NearbyRoomRepository(appContext).listRunnableRelayWants(limit = 1).isNotEmpty()) {
                    wakeWantWorker(appContext)
                }
            }
        }
    }

    private fun drainOffers(context: Context) {
        val repository = NearbyRoomRepository(context)
        val transportManager = NearbyRoomTransportManager(context, repository)
        repository.listPendingRelayOffers(limit = OFFER_BATCH_LIMIT).forEach { offer ->
            val message = repository.findMessageByCompactId(offer.room_id, offer.message_compact_id)
            val sent = if (message != null) {
                transportManager.sendMessageOffer(
                    roomId = offer.room_id,
                    messageCompactId = offer.message_compact_id,
                    targetDeviceId = offer.target_device_id,
                    messageOwnerDeviceId = message.owner_device_id ?: message.sender_device_id,
                )
            } else {
                false
            }
            repository.markRelayOfferState(
                roomId = offer.room_id,
                compactMessageId = offer.message_compact_id,
                targetDeviceId = offer.target_device_id,
                state = if (sent) NearbyRoomRelayOfferEntity.STATE_SENT else NearbyRoomRelayOfferEntity.STATE_FAILED,
            )
            Thread.sleep(RELAY_ITEM_COOLDOWN_MS)
        }
    }

    private fun drainWants(context: Context) {
        val repository = NearbyRoomRepository(context)
        val transportManager = NearbyRoomTransportManager(context, repository)
        val localDeviceId = repository.localDeviceId()
        repository.listRunnableRelayWants(limit = WANT_BATCH_LIMIT).forEach { want ->
            val nearbyIds = NearbyRoomPeerSnapshotStore.nearbyDeviceIds(limit = MAX_NEARBY_TARGETS).toSet()
            val candidates = want.candidate_source_device_ids
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() && it != localDeviceId && nearbyIds.contains(it) }
            if (candidates.isEmpty()) {
                repository.markRelayWantFailed(want.room_id, want.message_compact_id)
                return@forEach
            }
            val nextSourceIndex = if (candidates.isEmpty()) 0 else (want.next_source_index % candidates.size)
            val targetSource = candidates[nextSourceIndex]
            repository.markRelayWantRequesting(
                roomId = want.room_id,
                compactMessageId = want.message_compact_id,
                nextSourceIndex = (nextSourceIndex + 1) % candidates.size,
            )
            val sent = transportManager.sendMessageFetch(
                roomId = want.room_id,
                messageCompactId = want.message_compact_id,
                sourceDeviceId = targetSource,
            )
            if (!sent) {
                repository.markRelayWantFailed(want.room_id, want.message_compact_id)
            } else {
                Thread.sleep(FETCH_TIMEOUT_MS)
                val refreshed = repository.getRelayWant(want.room_id, want.message_compact_id)
                if (refreshed?.state == NearbyRoomRelayWantEntity.STATE_REQUESTING) {
                    repository.markRelayWantFailed(want.room_id, want.message_compact_id)
                }
            }
            Thread.sleep(RELAY_ITEM_COOLDOWN_MS)
        }
    }

    private const val MAX_NEARBY_TARGETS = 10
    private const val OFFER_BATCH_LIMIT = 4
    private const val WANT_BATCH_LIMIT = 2
    private const val RELAY_ITEM_COOLDOWN_MS = 500L
    private const val FETCH_TIMEOUT_MS = 2_500L
}
