package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.EventDedupeEntity

@Dao
interface EventDedupeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: EventDedupeEntity)

    @Query(
        """
        SELECT COUNT(1) FROM event_dedupe
        WHERE gid = :gid
          AND event_id = :eventId
          AND expires_at > :nowSeconds
        """
    )
    fun countActive(gid: String, eventId: String, nowSeconds: Long): Int

    @Query("DELETE FROM event_dedupe WHERE expires_at <= :nowSeconds")
    fun deleteExpired(nowSeconds: Long): Int

    @Query("DELETE FROM event_dedupe WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
