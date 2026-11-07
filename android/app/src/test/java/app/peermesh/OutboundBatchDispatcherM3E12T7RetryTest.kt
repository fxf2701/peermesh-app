package app.peermesh

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundBatchDispatcherM3E12T7RetryTest {
    private val source = File("src/main/java/app/peermesh/OutboundBatchDispatcher.kt").readText()

    @Test
    fun `failed durable peer delivery schedules a wake at its retry deadline`() {
        val failurePath = source.substringAfter("fun onBatchSendFailed(")
            .substringBefore("fun completeDispatchJob(")

        assertTrue(failurePath.contains("failDispatchJob("))
        assertTrue(failurePath.contains("kick(app, reason = \"batch_failed\")"))
        assertTrue(failurePath.contains("scheduleRejectedRetry("))
        assertTrue(failurePath.contains("reason = \"batch_failed_retry\""))
        assertTrue(failurePath.contains("delayMs = retryDelayMs"))
    }

    @Test
    fun `queued without write failures use short route recovery retry`() {
        assertTrue(
            OutboundBatchRetryPolicy.retryDelayMs("queued_no_write_attempt_timeout") <
                OutboundBatchRetryPolicy.DEFAULT_RETRY_DELAY_MS,
        )
    }
}
