package app.peermesh.transport.adapter

fun interface NearbyRoomAdvertisementProvider {
    fun currentPayload(): ByteArray?
}

fun interface NearbyRoomDiscoveryListener {
    fun onNearbyRoomMetadata(sourceDeviceId: String, payload: ByteArray, seenAtMs: Long, rssi: Int)
}
