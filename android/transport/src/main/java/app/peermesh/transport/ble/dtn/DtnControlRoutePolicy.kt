package app.peermesh.transport.ble.dtn

internal object DtnControlRoutePolicy {
    /**
     * A DTN control envelope is address-agnostic until its first chunk is actually active on wire.
     * Merely having a connected/connecting old BLE address is not enough to pin the envelope there.
     */
    fun shouldRebindQueuedEnvelope(
        hasStartedEnvelope: Boolean,
        hasActivePacket: Boolean,
        writeInFlight: Boolean,
    ): Boolean = !hasStartedEnvelope && !hasActivePacket && !writeInFlight
}
