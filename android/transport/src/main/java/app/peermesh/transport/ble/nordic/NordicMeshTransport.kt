package app.peermesh.transport.ble.nordic

import android.content.Context
import app.peermesh.core.transport.EnvelopeReceiver
import app.peermesh.transport.adapter.MeshDispatchJobSource
import app.peermesh.transport.adapter.MeshTrafficHint
import app.peermesh.transport.adapter.MeshTransport
import app.peermesh.transport.adapter.MeshTransportDiagnosticsListener
import app.peermesh.transport.adapter.NearbyRoomAdvertisementProvider
import app.peermesh.transport.adapter.NearbyRoomDiscoveryListener
import app.peermesh.transport.ble.BleMeshTransport
import app.peermesh.transport.ble.OpaqueRelayStore
import app.peermesh.transport.ble.dtn.DtnDiagnosticsSink
import app.peermesh.transport.ble.dtn.DtnRepairSuppressionStore
import app.peermesh.transport.ble.dtn.DtnSyncEngine

class NordicMeshTransport(
    context: Context,
    receiver: EnvelopeReceiver,
    dispatchJobSource: MeshDispatchJobSource? = null,
    dtnSyncEngine: DtnSyncEngine? = null,
    dtnActiveGidProvider: (() -> String?)? = null,
    dtnSyncGidProvider: (() -> List<String>)? = null,
    dtnDiagnostics: DtnDiagnosticsSink? = null,
    opaqueRelayStore: OpaqueRelayStore? = null,
    dtnRepairSuppressionStore: DtnRepairSuppressionStore? = null,
) : MeshTransport {
    private val delegate = BleMeshTransport(
        context = context,
        receiver = receiver,
        clientBackend = NordicClientBackend(),
        dispatchJobSource = dispatchJobSource,
        dtnSyncEngine = dtnSyncEngine,
        dtnActiveGidProvider = dtnActiveGidProvider,
        dtnSyncGidProvider = dtnSyncGidProvider,
        dtnDiagnostics = dtnDiagnostics,
        opaqueRelayStore = opaqueRelayStore,
        dtnRepairSuppressionStore = dtnRepairSuppressionStore,
    )

    override fun start() = delegate.start()

    override fun stop() = delegate.stop()

    override fun hardRestart() = delegate.hardRestart()

    override fun updateScanSettings() = delegate.updateScanSettings()

    override fun nearbyDeviceCount(nowMs: Long): Int = delegate.nearbyDeviceCount(nowMs)

    override fun hasPendingDataTraffic(): Boolean = delegate.hasPendingDataTraffic()

    override fun targetSnapshotForLog(nowMs: Long): String = delegate.targetSnapshotForLog(nowMs)

    override fun setDiagnosticsListener(listener: MeshTransportDiagnosticsListener?) {
        delegate.setDiagnosticsListener(listener)
    }

    override fun setNearbyRoomAdvertisementProvider(provider: NearbyRoomAdvertisementProvider?) {
        delegate.setNearbyRoomAdvertisementProvider(provider)
    }

    override fun setNearbyRoomDiscoveryListener(listener: NearbyRoomDiscoveryListener?) {
        delegate.setNearbyRoomDiscoveryListener(listener)
    }

    override fun refreshNearbyRoomAdvertisement(): Boolean = delegate.refreshNearbyRoomAdvertisement()

    override fun requestDiscoveryBurst(durationMs: Long) {
        delegate.requestDiscoveryBurst(durationMs)
    }

    override fun setNearbyForegroundMode(enabled: Boolean, reason: String) {
        delegate.setNearbyForegroundMode(enabled, reason)
    }

    override fun broadcastEnvelope(blob: ByteArray): Boolean = delegate.broadcastEnvelope(blob)
    override fun broadcastEnvelopeToDeviceIds(blob: ByteArray, deviceIds: Set<String>): Boolean =
        delegate.broadcastEnvelopeToDeviceIds(blob, deviceIds)
    override fun broadcastEnvelopeWithHint(blob: ByteArray, hint: MeshTrafficHint): Boolean =
        delegate.broadcastEnvelopeWithHint(blob, hint)
    override fun broadcastEnvelopeToDeviceIdsWithHint(
        blob: ByteArray,
        deviceIds: Set<String>,
        hint: MeshTrafficHint,
    ): Boolean = delegate.broadcastEnvelopeToDeviceIdsWithHint(blob, deviceIds, hint)
    override fun admitEnvelopeWithHint(blob: ByteArray, hint: MeshTrafficHint) =
        delegate.admitEnvelopeWithHint(blob, hint)
    override fun admitEnvelopeToDeviceIdsWithHint(blob: ByteArray, deviceIds: Set<String>, hint: MeshTrafficHint) =
        delegate.admitEnvelopeToDeviceIdsWithHint(blob, deviceIds, hint)

}
