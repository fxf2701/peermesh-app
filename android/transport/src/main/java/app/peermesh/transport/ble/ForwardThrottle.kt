package app.peermesh.transport.ble

class ForwardThrottle(
    private val maxForwards: Int,
    private val windowSeconds: Long,
) {
    private var windowStart = 0L
    private var count = 0

    fun allow(nowSeconds: Long): Boolean {
        if (nowSeconds - windowStart >= windowSeconds) {
            windowStart = nowSeconds
            count = 0
        }
        if (count >= maxForwards) return false
        count += 1
        return true
    }
}
