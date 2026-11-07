package app.peermesh.transport.ble

import app.peermesh.core.transport.EnvelopeReceiver

class BleReceiverAdapter(
    private val receiver: EnvelopeReceiver,
) {
    fun onBlePayload(blob: ByteArray): Boolean {
        return receiver.onEnvelopeReceived(blob)
    }
}
