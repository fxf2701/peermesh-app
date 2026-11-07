package app.peermesh.moderation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModerationReportSyncPolicyTest {
    @Test
    fun pendingReportsAreImmediatelyEligible() {
        assertTrue(
            ModerationReportSyncPolicy.isRetryEligible(
                deliveryState = "pending",
                attemptCount = 0,
                lastAttemptAtMs = null,
                nowMs = 1_000L,
            )
        )
    }

    @Test
    fun retryWaitingReportsRespectBackoffWindow() {
        val lastAttemptAtMs = 10_000L
        assertFalse(
            ModerationReportSyncPolicy.isRetryEligible(
                deliveryState = "retry_waiting",
                attemptCount = 2,
                lastAttemptAtMs = lastAttemptAtMs,
                nowMs = lastAttemptAtMs + 59_999L,
            )
        )
        assertTrue(
            ModerationReportSyncPolicy.isRetryEligible(
                deliveryState = "retry_waiting",
                attemptCount = 2,
                lastAttemptAtMs = lastAttemptAtMs,
                nowMs = lastAttemptAtMs + 60_000L,
            )
        )
    }

    @Test
    fun retryDelayIsExponentialAndBounded() {
        assertEquals(30_000L, ModerationReportSyncPolicy.retryDelayMs(attemptCount = 1))
        assertEquals(60_000L, ModerationReportSyncPolicy.retryDelayMs(attemptCount = 2))
        assertEquals(120_000L, ModerationReportSyncPolicy.retryDelayMs(attemptCount = 3))
        assertEquals(1_800_000L, ModerationReportSyncPolicy.retryDelayMs(attemptCount = 20))
    }

    @Test
    fun duplicateRetrySuccessStatusRemainsNonPermanent() {
        assertFalse(ModerationReportSyncPolicy.isPermanentFailureStatus(200))
    }

    @Test
    fun transientFailureStatusIsNotPermanent() {
        assertFalse(ModerationReportSyncPolicy.isPermanentFailureStatus(429))
        assertFalse(ModerationReportSyncPolicy.isPermanentFailureStatus(503))
    }

    @Test
    fun permanentFailureStatusIsDetected() {
        assertTrue(ModerationReportSyncPolicy.isPermanentFailureStatus(400))
        assertTrue(ModerationReportSyncPolicy.isPermanentFailureStatus(409))
    }
}
