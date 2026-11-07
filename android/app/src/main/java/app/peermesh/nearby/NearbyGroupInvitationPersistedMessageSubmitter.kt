package app.peermesh.nearby

import android.content.Context
import app.peermesh.data.db.entity.NearbyRoomMessageEntity

private const val OWNER_SEND_TARGET_LIMIT = 10

/**
 * Shared GI2E transport bridge. Both public and private pipelines submit only the entity returned
 * by authoritative repository insertion; retry continues to reload that persisted message.
 */
internal fun productionGroupInvitationSubmitter(
    context: Context,
    repository: NearbyRoomRepository,
    transportManager: NearbyRoomTransportManager,
): (NearbyRoomMessageEntity) -> Boolean = { message ->
    val room = repository.getRoom(message.room_id)
    val ownerTargetDeviceIds = if (room?.is_local_owner == true) {
        NearbyRoomPeerSnapshotStore
            .nearbyDeviceIdsForRoom(message.room_id, limit = OWNER_SEND_TARGET_LIMIT)
            .filterNot { it == repository.localDeviceId() }
            .toSet()
            .takeIf { it.isNotEmpty() }
    } else {
        null
    }
    val sent = transportManager.sendEncryptedMessage(
        message = message,
        targetDeviceIds = ownerTargetDeviceIds,
    )
    if (sent) {
        repository.markMessageSent(message.room_id, message.message_id)
        NearbyRoomRelayRecoveryManager.onMessageAvailable(
            context = context.applicationContext,
            message = message,
            lastHopSenderDeviceId = null,
        )
    } else if (room?.is_local_owner == true) {
        NearbyRoomOwnerMessageRetryCoordinator.enqueue(
            context = context.applicationContext,
            roomId = message.room_id,
            messageId = message.message_id,
        )
    }
    sent
}
