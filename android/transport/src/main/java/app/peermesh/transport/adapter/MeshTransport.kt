package app.peermesh.transport.adapter

import app.peermesh.core.transport.EnvelopeBroadcaster

enum class MeshTrafficHint {
    DEFAULT,
    NEARBY_ROOM_CONTROL,
    NEARBY_ROOM_KEY_BOOTSTRAP,
    NEARBY_ROOM_DATA,
}

/**
 * Admission is intentionally distinct from a completed BLE send.  In particular,
 * foreground mode can defer source-owned work without treating it as a rejection.
 */
sealed interface MeshSendAdmissionResult {
    data object Enqueued : MeshSendAdmissionResult
    data object DeferredForeground : MeshSendAdmissionResult
    data class Rejected(val reason: String) : MeshSendAdmissionResult
}

interface MeshTransport : EnvelopeBroadcaster {
    fun start()
    fun stop()
    fun hardRestart()
    fun updateScanSettings()
    override fun broadcastEnvelopeToDeviceIds(blob: ByteArray, deviceIds: Set<String>): Boolean = broadcastEnvelope(blob)
    fun broadcastEnvelopeWithHint(blob: ByteArray, hint: MeshTrafficHint): Boolean = broadcastEnvelope(blob)
    fun broadcastEnvelopeToDeviceIdsWithHint(
        blob: ByteArray,
        deviceIds: Set<String>,
        hint: MeshTrafficHint,
    ): Boolean = broadcastEnvelopeToDeviceIds(blob, deviceIds)
    fun admitEnvelopeWithHint(blob: ByteArray, hint: MeshTrafficHint): MeshSendAdmissionResult =
        if (broadcastEnvelopeWithHint(blob, hint)) MeshSendAdmissionResult.Enqueued
        else MeshSendAdmissionResult.Rejected("broadcast_rejected")
    fun admitEnvelopeToDeviceIdsWithHint(
        blob: ByteArray,
        deviceIds: Set<String>,
        hint: MeshTrafficHint,
    ): MeshSendAdmissionResult =
        if (broadcastEnvelopeToDeviceIdsWithHint(blob, deviceIds, hint)) MeshSendAdmissionResult.Enqueued
        else MeshSendAdmissionResult.Rejected("broadcast_rejected")
    fun nearbyDeviceCount(nowMs: Long = System.currentTimeMillis()): Int
    fun hasPendingDataTraffic(): Boolean
    fun targetSnapshotForLog(nowMs: Long = System.currentTimeMillis()): String
    fun setDiagnosticsListener(listener: MeshTransportDiagnosticsListener?)
    fun setNearbyRoomAdvertisementProvider(provider: NearbyRoomAdvertisementProvider?) {}
    fun setNearbyRoomDiscoveryListener(listener: NearbyRoomDiscoveryListener?) {}
    fun refreshNearbyRoomAdvertisement(): Boolean = false
    fun requestDiscoveryBurst(durationMs: Long = 4_000L) {}
    fun setNearbyForegroundMode(enabled: Boolean, reason: String) {}
}
