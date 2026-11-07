package app.peermesh.nearby

data class NearbyRoomBleAdvertisement(
    val roomId: String,
    val roomName: String,
    val ownerDeviceId: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val participantCount: Int?,
    val protocolVersion: Int,
)
