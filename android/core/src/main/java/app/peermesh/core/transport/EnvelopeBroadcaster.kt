package app.peermesh.core.transport

interface EnvelopeBroadcaster {
    fun broadcastEnvelope(blob: ByteArray): Boolean
    fun broadcastEnvelopeToDeviceIds(blob: ByteArray, deviceIds: Set<String>): Boolean {
        return broadcastEnvelope(blob)
    }
}
