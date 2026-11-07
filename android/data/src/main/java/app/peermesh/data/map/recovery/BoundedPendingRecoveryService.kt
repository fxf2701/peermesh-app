package app.peermesh.data.map.recovery

/**
 * M2C3C group-scoped maintenance seam.
 *
 * Implementations must remain independent from UI, app startup, EventIngestor, BLE and relay.
 */
internal fun interface BoundedPendingRecoveryService {
    fun recover(request: BoundedPendingRecoveryRequest): BoundedPendingRecoveryResult
}
