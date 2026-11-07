package app.peermesh.nearby

import app.peermesh.data.db.entity.NearbyRoomMessageEntity

object NearbyRoomLocalBlockPolicy {
    const val ACTION_NEARBY_ROOM_LOCAL_BLOCK = "nearby_room_local_block"

    fun visibleMessages(
        messages: List<NearbyRoomMessageEntity>,
        blockedDeviceIds: Set<String>,
    ): List<NearbyRoomMessageEntity> {
        if (blockedDeviceIds.isEmpty()) return messages
        return messages.filterNot { shouldHideMessage(it, blockedDeviceIds) }
    }

    fun shouldHideMessage(
        message: NearbyRoomMessageEntity,
        blockedDeviceIds: Set<String>,
    ): Boolean {
        if (message.delivery_state == NearbyRoomMessageEntity.DELIVERY_SYSTEM) return false
        val actorDeviceId = message.owner_device_id ?: message.sender_device_id
        return actorDeviceId in blockedDeviceIds
    }
}
