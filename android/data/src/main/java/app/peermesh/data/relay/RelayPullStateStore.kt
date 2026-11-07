package app.peermesh.data.relay

data class RelayPullState(
    val cursor: String,
)

interface RelayPullStateStore {
    fun load(relayUrl: String, gid: String): RelayPullState?

    fun save(relayUrl: String, gid: String, state: RelayPullState)
}

class InMemoryRelayPullStateStore : RelayPullStateStore {
    private val states = mutableMapOf<String, RelayPullState>()

    override fun load(relayUrl: String, gid: String): RelayPullState? {
        return states[key(relayUrl, gid)]
    }

    override fun save(relayUrl: String, gid: String, state: RelayPullState) {
        states[key(relayUrl, gid)] = state
    }

    private fun key(relayUrl: String, gid: String) = "$relayUrl|$gid"
}

class RoomRelayPullStateStore(
    private val dao: app.peermesh.data.db.dao.RelayCursorDao,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) : RelayPullStateStore {
    override fun load(relayUrl: String, gid: String): RelayPullState? {
        return dao.find(relayUrl, gid)?.let { RelayPullState(it.cursor) }
    }

    override fun save(relayUrl: String, gid: String, state: RelayPullState) {
        dao.upsert(
            app.peermesh.data.db.entity.RelayCursorEntity(
                relay_url = relayUrl,
                gid = gid,
                cursor = state.cursor,
                updated_at = nowSeconds(),
            )
        )
    }
}
