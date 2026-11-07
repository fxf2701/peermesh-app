package app.peermesh.transport.adapter

interface DtnTransportAdapter {
    fun start()
    fun stop()
    fun setListener(listener: Listener?)

    fun sendPayload(peer: PeerRef, payload: ByteArray, priority: Int = 1): Boolean
    fun listPeers(): List<PeerRef>

    interface Listener {
        fun onPeerUp(peer: PeerRef)
        fun onPeerDown(peer: PeerRef)
        fun onPayloadReceived(peer: PeerRef, payload: ByteArray, rssi: Int?)
    }
}

data class PeerRef(
    val peerId: String,
    val address: String?,
    val transport: TransportBackendType,
    val rssi: Int? = null,
)

enum class TransportBackendType {
    BLE_CURRENT,
    BLE_NORDIC,
    WIFI_DIRECT,
    UNKNOWN,
}
