package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.DtnPeerStateEntity

@Dao
interface DtnPeerStateDao {
    @Query("SELECT * FROM dtn_peer_state WHERE peer_id = :peerId AND gid = :gid LIMIT 1")
    fun find(peerId: String, gid: String): DtnPeerStateEntity?

    @Query(
        """
        SELECT * FROM dtn_peer_state
        WHERE gid = :gid
        ORDER BY last_seen_at DESC, peer_id ASC
        LIMIT :limit
        """
    )
    fun listRecentForGroup(gid: String, limit: Int): List<DtnPeerStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: DtnPeerStateEntity)

    @Query(
        """
        UPDATE dtn_peer_state
        SET last_seen_at = :lastSeenAt,
            last_session_id = :sessionId
        WHERE peer_id = :peerId AND gid = :gid
        """
    )
    fun markSeen(peerId: String, gid: String, lastSeenAt: Long, sessionId: String?)

    @Query(
        """
        UPDATE dtn_peer_state
        SET last_sync_at = :lastSyncAt,
            fail_count = 0,
            backoff_until = NULL
        WHERE peer_id = :peerId AND gid = :gid
        """
    )
    fun markSyncSuccess(peerId: String, gid: String, lastSyncAt: Long)

    @Query(
        """
        UPDATE dtn_peer_state
        SET fail_count = fail_count + 1,
            backoff_until = :backoffUntil
        WHERE peer_id = :peerId AND gid = :gid
        """
    )
    fun markSyncFailure(peerId: String, gid: String, backoffUntil: Long?)

    @Query("DELETE FROM dtn_peer_state WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
