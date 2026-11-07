package app.peermesh.data.relay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySyncSchedulerTest {
    @Test
    fun `repair request pulls a running scheduler forward`() {
        val ran = CountDownLatch(1)
        val scheduler = RelaySyncScheduler(
            runOnce = {
                ran.countDown()
                true
            },
            successIntervalMs = 60_000L,
        )
        try {
            scheduler.start(immediate = false)

            assertTrue(scheduler.requestImmediateRun())
            assertTrue(ran.await(2, TimeUnit.SECONDS))
        } finally {
            scheduler.shutdown()
        }
    }
}
