package app.peermesh.ble

import app.peermesh.transport.ble.dtn.DtnCloudStates
import app.peermesh.transport.ble.dtn.DtnCloudStatus
import java.util.concurrent.ConcurrentHashMap

object BlePeerCloudStateRegistry {
    private const val RECENT_HELLO_WINDOW_MS = 10 * 60_000L

    data class PeerCloudState(
        val peerId: String,
        val gid: String,
        val state: String,
        val lastSuccessAtSeconds: Long?,
        val seenAtMs: Long,
    )

    private val states = ConcurrentHashMap<String, PeerCloudState>()

    fun update(peerId: String, gid: String, cloudStatus: DtnCloudStatus?, seenAtSeconds: Long) {
        val state = cloudStatus?.state ?: return
        val key = key(peerId, gid)
        states[key] = PeerCloudState(
            peerId = peerId,
            gid = gid,
            state = state,
            lastSuccessAtSeconds = cloudStatus.lastSuccessAtSeconds,
            seenAtMs = seenAtSeconds * 1000L,
        )
    }

    fun shouldBridgeGroup(gid: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        pruneExpired(nowMs)
        return states.values.any { state ->
            state.gid == gid &&
                (state.state == DtnCloudStates.DISABLED || state.state == DtnCloudStates.STALE)
        }
    }

    internal fun clearForTest() {
        states.clear()
    }

    private fun pruneExpired(nowMs: Long) {
        states.entries.removeIf { (_, value) -> nowMs - value.seenAtMs > RECENT_HELLO_WINDOW_MS }
    }

    private fun key(peerId: String, gid: String): String = "$peerId::$gid"
}
