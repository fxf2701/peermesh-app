package app.peermesh.transport.ble

import java.util.concurrent.ThreadLocalRandom

class FloodControl(
    private val minDelayMs: Long = 200,
    private val maxDelayMs: Long = 500,
) {
    fun jitterDelayMs(): Long {
        return ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs + 1)
    }
}
