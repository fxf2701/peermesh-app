package app.peermesh

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboundBatchRetryPolicyM3E12T7Test {
    @Test
    fun queuedNoWriteAttemptUsesShortRouteRecoveryRetry() {
        assertEquals(
            OutboundBatchRetryPolicy.ROUTE_RECOVERY_RETRY_DELAY_MS,
            OutboundBatchRetryPolicy.retryDelayMs("queued_no_write_attempt_timeout"),
        )
    }

    @Test
    fun queuedNoProgressUsesShortRouteRecoveryRetry() {
        assertEquals(
            OutboundBatchRetryPolicy.ROUTE_RECOVERY_RETRY_DELAY_MS,
            OutboundBatchRetryPolicy.retryDelayMs("queued_no_progress_timeout"),
        )
    }

    @Test
    fun transportResetFailuresStayOnShortRecoveryRetry() {
        listOf(
            "interrupted_transport_stop",
            "transport_stop",
            "stalled_write",
            "some_transport_stop_reason",
        ).forEach { reason ->
            assertEquals(
                reason,
                OutboundBatchRetryPolicy.ROUTE_RECOVERY_RETRY_DELAY_MS,
                OutboundBatchRetryPolicy.retryDelayMs(reason),
            )
        }
    }

    @Test
    fun ordinaryFailureKeepsBoundedDefaultRetry() {
        assertEquals(
            OutboundBatchRetryPolicy.DEFAULT_RETRY_DELAY_MS,
            OutboundBatchRetryPolicy.retryDelayMs("gatt_write_failed"),
        )
    }
}
