package app.peermesh.data.relay

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

class RelayBackoff(
    private val baseDelayMs: Long = 2_000L,
    private val maxDelayMs: Long = 300_000L,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var attempt: Int = 0

    fun reset() {
        attempt = 0
    }

    fun nextDelayMs(): Long {
        val raw = baseDelayMs * 2.0.pow(attempt.toDouble())
        val capped = min(raw.toLong(), maxDelayMs)
        attempt = min(attempt + 1, 30)
        return applyJitter(capped)
    }

    private fun applyJitter(value: Long): Long {
        if (value <= 0) return 0L
        val maxOffset = (value * jitterRatio).toLong()
        if (maxOffset == 0L) return value
        val offset = random.nextLong(-maxOffset, maxOffset + 1)
        return (value + offset).coerceAtLeast(0L)
    }
}
