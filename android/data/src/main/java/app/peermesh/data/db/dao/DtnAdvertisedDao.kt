package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.DtnAdvertisedEntity

@Dao
interface DtnAdvertisedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(items: List<DtnAdvertisedEntity>)

    @Query(
        """
        SELECT event_id FROM dtn_advertised
        WHERE peer_id = :peerId AND gid = :gid
        ORDER BY advertised_at DESC, event_id ASC
        LIMIT :limit
        """
    )
    fun listAdvertisedEventIds(peerId: String, gid: String, limit: Int): List<String>

    @Query("DELETE FROM dtn_advertised WHERE expires_at IS NOT NULL AND expires_at < :nowSeconds")
    fun deleteExpired(nowSeconds: Long): Int

    @Query("DELETE FROM dtn_advertised WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
