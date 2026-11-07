package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.DtnSyncCursorEntity

@Dao
interface DtnSyncCursorDao {
    @Query("SELECT * FROM dtn_sync_cursor WHERE peer_id = :peerId AND gid = :gid LIMIT 1")
    fun find(peerId: String, gid: String): DtnSyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: DtnSyncCursorEntity)

    @Query("DELETE FROM dtn_sync_cursor WHERE updated_at < :cutoffSeconds")
    fun deleteOlderThan(cutoffSeconds: Long): Int

    @Query("DELETE FROM dtn_sync_cursor WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
