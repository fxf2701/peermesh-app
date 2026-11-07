package app.peermesh.data.db.entity

object OutboundBatchStatus {
    const val NEW = "NEW"
    const val QUEUED = "QUEUED"
    const val PROCESSING = "PROCESSING"
    const val SENT = "SENT"
    const val FAILED_RETRYABLE = "FAILED_RETRYABLE"
    const val FAILED_FINAL = "FAILED_FINAL"
}
