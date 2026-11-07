package app.peermesh.data.relay

import app.peermesh.data.db.dao.RelayPushStateDao
import app.peermesh.data.db.entity.RelayPushStateEntity

class RoomRelayPushStateStore(
    private val dao: RelayPushStateDao,
) : RelayPushStateStore {
    companion object {
        private const val TAG = "RelayPushState"
    }

    override fun load(relayUrl: String): RelayPushState? {
        val row = dao.find(relayUrl) ?: return null
        return RelayPushState(
            lastPushRowId = row.last_push_row_id,
            lastPushAt = row.last_push_at,
            lastPushEventId = row.last_push_event_id,
        )
    }

    override fun save(relayUrl: String, state: RelayPushState) {
        val now = System.currentTimeMillis() / 1000L
        dao.upsert(
            RelayPushStateEntity(
                relay_url = relayUrl,
                last_push_row_id = state.lastPushRowId,
                last_push_at = state.lastPushAt,
                last_push_event_id = state.lastPushEventId,
                updated_at = now,
            )
        )
        android.util.Log.i(
            TAG,
            "push cursor saved relay=${relayUrl} lastPushRowId=${state.lastPushRowId} lastPushAt=${state.lastPushAt} lastEventId=${state.lastPushEventId}"
        )
    }
}
