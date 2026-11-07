package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.GroupPeerRouteEntity

@Dao
interface GroupPeerRouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: GroupPeerRouteEntity)

    @Query(
        """
        SELECT * FROM group_peer_routes
        WHERE gid = :gid
          AND actor_did = :actorDid
          AND device_id = :deviceId
        LIMIT 1
        """
    )
    fun find(gid: String, actorDid: String, deviceId: String): GroupPeerRouteEntity?

    @Query(
        """
        SELECT device_id FROM group_peer_routes
        WHERE gid = :gid
          AND actor_did = :actorDid
        ORDER BY updated_at DESC, device_id ASC
        LIMIT :limit
        """
    )
    fun listDeviceIds(gid: String, actorDid: String, limit: Int = 8): List<String>

    @Query(
        """
        DELETE FROM group_peer_routes
        WHERE updated_at < :cutoffSeconds
        """
    )
    fun deleteOlderThan(cutoffSeconds: Long): Int
}
