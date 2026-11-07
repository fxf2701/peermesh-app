package app.peermesh.relay

object ManagedRelayFallbackPolicy {
    fun shouldUseKnownRelays(report: RelaySyncRunReport): Boolean {
        if (report.success) return false
        if (report.relayCount == 0) return true
        val note = report.note.orEmpty()
        return note.split(',')
            .map { it.trim() }
            .any { it.startsWith("managed_reauth_failed:") }
    }
}
