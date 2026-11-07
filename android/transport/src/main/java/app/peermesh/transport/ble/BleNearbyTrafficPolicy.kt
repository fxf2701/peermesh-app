package app.peermesh.transport.ble

import app.peermesh.transport.adapter.MeshTrafficHint

object BleNearbyTrafficPolicy {
    fun shouldBypassDeviceQuiet(hasNearbyRoomControlWork: Boolean): Boolean = hasNearbyRoomControlWork

    fun shouldBypassDeviceCooldown(hasNearbyRoomControlWork: Boolean): Boolean = hasNearbyRoomControlWork

    fun shouldBypassAddressCooldown(hint: MeshTrafficHint): Boolean =
        hint == MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP

    fun shouldPrioritizeBeforeMeshBatch(hint: MeshTrafficHint): Boolean =
        isNearbyRoomPriorityHint(hint)

    fun shouldPrioritizeBeforeNearbyRoomControl(hint: MeshTrafficHint): Boolean =
        hint == MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP

    fun isNearbyRoomPriorityHint(hint: MeshTrafficHint): Boolean =
        hint == MeshTrafficHint.NEARBY_ROOM_CONTROL ||
            hint == MeshTrafficHint.NEARBY_ROOM_KEY_BOOTSTRAP ||
            hint == MeshTrafficHint.NEARBY_ROOM_DATA

    fun isNearbyRoomTraffic(hint: MeshTrafficHint): Boolean =
        isNearbyRoomPriorityHint(hint)

    fun allowsAdmission(
        foregroundMode: Boolean,
        hint: MeshTrafficHint,
        isTransportAck: Boolean,
    ): Boolean =
        !foregroundMode || isTransportAck || isNearbyRoomTraffic(hint)

    fun allowsNewItemDequeue(
        foregroundMode: Boolean,
        hint: MeshTrafficHint,
        isTransportAck: Boolean,
    ): Boolean = allowsAdmission(foregroundMode, hint, isTransportAck)

    fun allowsPacketDequeue(
        foregroundMode: Boolean,
        hint: MeshTrafficHint,
        isTransportAck: Boolean,
        activeItemContinuation: Boolean,
    ): Boolean =
        activeItemContinuation || allowsNewItemDequeue(foregroundMode, hint, isTransportAck)

    fun hasForegroundQueueHeadroom(
        currentGlobalPackets: Int,
        currentPeerPackets: Int,
        incomingPackets: Int,
        globalBaseLimit: Int,
        peerBaseLimit: Int,
        reservePackets: Int,
    ): Boolean =
        currentGlobalPackets + incomingPackets <= globalBaseLimit + reservePackets &&
            currentPeerPackets + incomingPackets <= peerBaseLimit + reservePackets
}
