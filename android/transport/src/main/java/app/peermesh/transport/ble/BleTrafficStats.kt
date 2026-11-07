package app.peermesh.transport.ble

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

object BleTrafficStats {
    private val uploadedBytes = AtomicLong(0)
    private val downloadedBytes = AtomicLong(0)
    private val receivedPackets = AtomicLong(0)
    private val lastScanAtMs = AtomicLong(0)
    private val lastNotifyAtMs = AtomicLong(0)
    private val listeners = CopyOnWriteArrayList<TrafficListener>()

    fun addUploaded(bytes: Long) {
        if (bytes > 0) {
            uploadedBytes.addAndGet(bytes)
            notifyTraffic()
        }
    }

    fun addDownloaded(bytes: Long) {
        if (bytes > 0) {
            downloadedBytes.addAndGet(bytes)
            notifyTraffic()
        }
    }

    fun addReceivedPacket() {
        receivedPackets.incrementAndGet()
        notifyTraffic()
    }

    fun markScanResult() {
        lastScanAtMs.set(System.currentTimeMillis())
    }

    fun snapshot(): BleTrafficSnapshot {
        return BleTrafficSnapshot(
            uploaded = uploadedBytes.get(),
            downloaded = downloadedBytes.get(),
            receivedPackets = receivedPackets.get(),
            lastScanAtMs = lastScanAtMs.get(),
            timestampMs = System.currentTimeMillis(),
        )
    }

    fun addListener(listener: TrafficListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TrafficListener) {
        listeners.remove(listener)
    }

    private fun notifyTraffic() {
        val now = System.currentTimeMillis()
        val last = lastNotifyAtMs.get()
        if (now - last < NOTIFY_THROTTLE_MS) return
        if (!lastNotifyAtMs.compareAndSet(last, now)) return
        listeners.forEach { it.onTrafficDetected() }
    }

    interface TrafficListener {
        fun onTrafficDetected()
    }
}

data class BleTrafficSnapshot(
    val uploaded: Long,
    val downloaded: Long,
    val receivedPackets: Long,
    val lastScanAtMs: Long,
    val timestampMs: Long,
)

private const val NOTIFY_THROTTLE_MS = 2_000L
