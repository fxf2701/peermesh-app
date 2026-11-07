package app.peermesh.data.relay

data class RelayPushState(
    val lastPushRowId: Long,
    val lastPushAt: Long,
    val lastPushEventId: String,
)

interface RelayPushStateStore {
    fun load(relayUrl: String): RelayPushState?

    fun save(relayUrl: String, state: RelayPushState)
}

class InMemoryRelayPushStateStore : RelayPushStateStore {
    private val states = mutableMapOf<String, RelayPushState>()

    override fun load(relayUrl: String): RelayPushState? = states[relayUrl]

    override fun save(relayUrl: String, state: RelayPushState) {
        states[relayUrl] = state
    }
}
