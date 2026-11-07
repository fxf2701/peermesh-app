package app.peermesh.transport.adapter

class TransportRouter(
    private val primary: DtnTransportAdapter,
    private val fallback: DtnTransportAdapter? = null,
    private val backend: TransportBackendType = TransportBackendType.BLE_CURRENT,
) : DtnTransportAdapter {
    private var listener: DtnTransportAdapter.Listener? = null

    override fun start() {
        activeAdapter().start()
    }

    override fun stop() {
        activeAdapter().stop()
    }

    override fun setListener(listener: DtnTransportAdapter.Listener?) {
        this.listener = listener
        primary.setListener(listener)
        fallback?.setListener(listener)
    }

    override fun sendPayload(peer: PeerRef, payload: ByteArray, priority: Int): Boolean {
        return activeAdapter().sendPayload(peer, payload, priority)
    }

    override fun listPeers(): List<PeerRef> {
        return activeAdapter().listPeers()
    }

    private fun activeAdapter(): DtnTransportAdapter {
        return when (backend) {
            TransportBackendType.BLE_CURRENT -> primary
            TransportBackendType.BLE_NORDIC -> fallback ?: primary
            TransportBackendType.WIFI_DIRECT -> fallback ?: primary
            TransportBackendType.UNKNOWN -> primary
        }
    }
}
