package app.peermesh.data.dtn

import app.peermesh.data.db.dao.DtnPeerStateDao
import app.peermesh.data.db.dao.DtnSyncCursorDao
import app.peermesh.data.db.entity.DtnPeerStateEntity
import app.peermesh.data.db.entity.DtnSyncCursorEntity
import app.peermesh.transport.ble.dtn.DtnSyncCursor
import app.peermesh.transport.ble.dtn.DtnSyncStateStore

class RoomDtnSyncStateStore(
    private val peerStateDao: DtnPeerStateDao,
    private val syncCursorDao: DtnSyncCursorDao,
) : DtnSyncStateStore {
    override fun markPeerSeen(peerId: String, gid: String, sessionId: String?, nowSeconds: Long) {
        val existing = peerStateDao.find(peerId, gid)
        peerStateDao.upsert(
            DtnPeerStateEntity(
                peer_id = peerId,
                gid = gid,
                last_seen_at = nowSeconds,
                last_session_id = sessionId ?: existing?.last_session_id,
                last_sync_at = existing?.last_sync_at,
                fail_count = existing?.fail_count ?: 0,
                backoff_until = existing?.backoff_until,
            )
        )
    }

    override fun loadCursor(peerId: String, gid: String): DtnSyncCursor? {
        return syncCursorDao.find(peerId, gid)?.let { entity ->
            DtnSyncCursor(
                lastInsertedAt = entity.last_inserted_at,
                lastEventId = entity.last_event_id,
            )
        }
    }

    override fun saveCursor(peerId: String, gid: String, cursor: DtnSyncCursor, nowSeconds: Long) {
        syncCursorDao.upsert(
            DtnSyncCursorEntity(
                peer_id = peerId,
                gid = gid,
                last_inserted_at = cursor.lastInsertedAt,
                last_event_id = cursor.lastEventId,
                updated_at = nowSeconds,
            )
        )
    }

    override fun markSyncSuccess(peerId: String, gid: String, nowSeconds: Long) {
        val existing = peerStateDao.find(peerId, gid)
        peerStateDao.upsert(
            DtnPeerStateEntity(
                peer_id = peerId,
                gid = gid,
                last_seen_at = existing?.last_seen_at ?: nowSeconds,
                last_session_id = existing?.last_session_id,
                last_sync_at = nowSeconds,
                fail_count = 0,
                backoff_until = null,
            )
        )
    }

    override fun markSyncFailure(peerId: String, gid: String, backoffUntilSeconds: Long?) {
        val existing = peerStateDao.find(peerId, gid)
        peerStateDao.upsert(
            DtnPeerStateEntity(
                peer_id = peerId,
                gid = gid,
                last_seen_at = existing?.last_seen_at ?: 0L,
                last_session_id = existing?.last_session_id,
                last_sync_at = existing?.last_sync_at,
                fail_count = (existing?.fail_count ?: 0) + 1,
                backoff_until = backoffUntilSeconds,
            )
        )
    }
}
