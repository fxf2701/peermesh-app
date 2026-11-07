package app.peermesh.transport.ble.dtn

/**
 * Transport-side ordering for complete DTN control envelopes.
 *
 * A control envelope must stay contiguous once its first chunk is selected. Before an envelope
 * starts, a HELLO for the same session is the prerequisite for session-scoped work; otherwise
 * exact dependency/data work outranks bulk inventory advertisement.
 */
object DtnControlQueuePolicy {
    data class Candidate(
        val itemId: String,
        val type: String,
        val gid: String?,
        val sessionId: String?,
    )

    fun select(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null

        val bestNonHello = candidates
            .asSequence()
            .filter { it.type != DtnControlTypes.HELLO }
            .minWithOrNull(compareBy<Candidate> { priority(it.type) })

        if (bestNonHello != null) {
            candidates.firstOrNull { candidate ->
                candidate.type == DtnControlTypes.HELLO &&
                    candidate.gid == bestNonHello.gid &&
                    candidate.sessionId == bestNonHello.sessionId
            }?.let { return it }
            return bestNonHello
        }

        return candidates.first()
    }

    private fun priority(type: String): Int = when (type) {
        DtnControlTypes.DATA -> 0
        DtnControlTypes.ACK -> 1
        DtnControlTypes.INV_WANT -> 2
        DtnControlTypes.INV_HAVE -> 3
        DtnControlTypes.MANIFEST_ACCEPT -> 4
        DtnControlTypes.MANIFEST_KEY -> 5
        DtnControlTypes.MANIFEST_OFFER -> 6
        else -> 7
    }
}
