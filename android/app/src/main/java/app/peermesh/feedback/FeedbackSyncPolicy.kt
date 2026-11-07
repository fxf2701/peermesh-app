package app.peermesh.feedback

object FeedbackSyncPolicy {
    private const val MAX_ATTEMPTS = 8
    private const val BASE_RETRY_DELAY_MS = 30_000L
    private const val MAX_RETRY_DELAY_MS = 6 * 60 * 60 * 1000L

    fun isRetryEligible(
        deliveryState: String,
        attemptCount: Int,
        lastAttemptAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (deliveryState == DELIVERY_FAILED_PERMANENT || deliveryState == DELIVERY_DELIVERED) return false
        if (attemptCount <= 0 || lastAttemptAtMs == null) return true
        if (attemptCount >= MAX_ATTEMPTS) return false
        return nowMs - lastAttemptAtMs >= retryDelayMs(attemptCount)
    }

    fun isPermanentFailureStatus(statusCode: Int): Boolean {
        return statusCode in 400..499 && statusCode != 408 && statusCode != 429
    }

    private fun retryDelayMs(attemptCount: Int): Long {
        var delayMs = BASE_RETRY_DELAY_MS
        repeat((attemptCount - 1).coerceAtLeast(0)) {
            delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        }
        return delayMs
    }

    const val DELIVERY_PENDING = "pending"
    const val DELIVERY_RETRY_WAITING = "retry_waiting"
    const val DELIVERY_DELIVERED = "delivered"
    const val DELIVERY_FAILED_PERMANENT = "failed_permanent"
}
