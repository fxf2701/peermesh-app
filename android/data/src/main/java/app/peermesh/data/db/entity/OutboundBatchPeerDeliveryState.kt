package app.peermesh.data.db.entity

object OutboundBatchPeerDeliveryState {
    const val PENDING = "PENDING"
    const val IN_FLIGHT = "IN_FLIGHT"
    const val DELIVERED = "DELIVERED"
    const val FAILED_RETRYABLE = "FAILED_RETRYABLE"
    const val FAILED_FINAL = "FAILED_FINAL"
}
