package app.peermesh

/**
 * Retry timing policy for durable APP outbound batches.
 *
 * A peer delivery failure stores next_retry_at in Room. The dispatcher must
 * therefore schedule an explicit wake-up for that deadline; an immediate kick
 * alone cannot claim the not-yet-due FAILED_RETRYABLE row.
 */
internal object OutboundBatchRetryPolicy {
    const val DEFAULT_RETRY_DELAY_MS = 30_000L
    const val ROUTE_RECOVERY_RETRY_DELAY_MS = 2_500L

    fun retryDelayMs(reason: String): Long = when {
        reason == "queued_no_write_attempt_timeout" -> ROUTE_RECOVERY_RETRY_DELAY_MS
        reason == "queued_no_progress_timeout" -> ROUTE_RECOVERY_RETRY_DELAY_MS
        reason == "interrupted_transport_stop" -> ROUTE_RECOVERY_RETRY_DELAY_MS
        reason == "transport_stop" -> ROUTE_RECOVERY_RETRY_DELAY_MS
        reason == "stalled_write" -> ROUTE_RECOVERY_RETRY_DELAY_MS
        reason.contains("transport_stop") -> ROUTE_RECOVERY_RETRY_DELAY_MS
        else -> DEFAULT_RETRY_DELAY_MS
    }
}
