package app.peermesh.moderation

internal object ModerationReportSyncPolicy {
    private const val DELIVERY_PENDING = "pending"
    private const val DELIVERY_RETRY_WAITING = "retry_waiting"
    private const val RETRY_BASE_DELAY_MS = 30_000L
    private const val RETRY_MAX_DELAY_MS = 30 * 60_000L

    fun isRetryEligible(
        deliveryState: String,
        attemptCount: Int,
        lastAttemptAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (deliveryState == DELIVERY_PENDING) return true
        if (deliveryState != DELIVERY_RETRY_WAITING) return false
        val lastAttempt = lastAttemptAtMs ?: return true
        return nowMs >= lastAttempt + retryDelayMs(attemptCount)
    }

    fun retryDelayMs(attemptCount: Int): Long {
        val exponent = maxOf(0, attemptCount - 1)
        val multiplier = 1L.shl(minOf(exponent, 10))
        return (RETRY_BASE_DELAY_MS * multiplier).coerceAtMost(RETRY_MAX_DELAY_MS)
    }

    fun isPermanentFailureStatus(statusCode: Int): Boolean {
        return statusCode in 400..499 && statusCode != 429
    }
}
