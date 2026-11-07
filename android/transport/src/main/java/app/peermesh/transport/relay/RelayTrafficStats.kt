package app.peermesh.transport.relay

import java.util.concurrent.atomic.AtomicLong

object RelayTrafficStats {
    private val uploadedBytes = AtomicLong(0)
    private val downloadedBytes = AtomicLong(0)

    fun addUploaded(bytes: Long) {
        if (bytes > 0) uploadedBytes.addAndGet(bytes)
    }

    fun addDownloaded(bytes: Long) {
        if (bytes > 0) downloadedBytes.addAndGet(bytes)
    }

    fun snapshot(): TrafficSnapshot {
        return TrafficSnapshot(
            uploaded = uploadedBytes.get(),
            downloaded = downloadedBytes.get(),
            timestampMs = System.currentTimeMillis(),
        )
    }
}

data class TrafficSnapshot(
    val uploaded: Long,
    val downloaded: Long,
    val timestampMs: Long,
)
