package app.peermesh.data.relay

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class RelaySyncScheduler(
    private val runOnce: () -> Boolean,
    private val successIntervalMs: Long = 30_000L,
    backoff: RelayBackoff = RelayBackoff(),
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val backoffState = backoff
    private val stateLock = Any()
    private var running = false
    private var cycleRunning = false
    private var immediateRunRequested = false
    private var scheduled: ScheduledFuture<*>? = null

    fun start(immediate: Boolean = true) {
        synchronized(stateLock) {
            if (running) return
            running = true
            scheduleNextLocked(if (immediate) 0L else successIntervalMs)
        }
    }

    fun stop() {
        synchronized(stateLock) {
            running = false
            immediateRunRequested = false
            scheduled?.cancel(false)
            scheduled = null
        }
    }

    /**
     * Pulls an already-running scheduler forward without creating a parallel sync loop.
     * Multiple requests coalesce because the previously scheduled cycle is replaced.
     */
    fun requestImmediateRun(): Boolean {
        synchronized(stateLock) {
            if (!running) return false
            if (cycleRunning) {
                immediateRunRequested = true
            } else {
                scheduleNextLocked(0L)
            }
            return true
        }
    }

    fun shutdown() {
        stop()
        executor.shutdownNow()
    }

    private fun scheduleNextLocked(delayMs: Long) {
        if (!running) return
        scheduled?.cancel(false)
        scheduled = executor.schedule({ runCycle() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun runCycle() {
        synchronized(stateLock) {
            if (!running) return
            cycleRunning = true
        }

        val ok = runOnce()

        synchronized(stateLock) {
            cycleRunning = false
            if (!running) return
            if (immediateRunRequested) {
                immediateRunRequested = false
                scheduleNextLocked(0L)
            } else if (ok) {
                backoffState.reset()
                scheduleNextLocked(successIntervalMs)
            } else {
                val delay = backoffState.nextDelayMs()
                scheduleNextLocked(delay)
            }
        }
    }
}
