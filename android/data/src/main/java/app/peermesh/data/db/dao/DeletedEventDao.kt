package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.DeletedEventEntity

@Dao
interface DeletedEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: DeletedEventEntity)

    @Query("SELECT * FROM deleted_events WHERE gid = :gid AND target_event_id = :targetEventId")
    fun find(gid: String, targetEventId: String): DeletedEventEntity?

    @Query(
        """
        SELECT * FROM deleted_events
        WHERE gid = :gid
          AND target_event_id = :targetEventId
          AND expires_at > :nowSeconds
        LIMIT 1
        """
    )
    fun findActive(gid: String, targetEventId: String, nowSeconds: Long): DeletedEventEntity?

    @Query("DELETE FROM deleted_events WHERE expires_at <= :nowSeconds")
    fun deleteExpired(nowSeconds: Long): Int

    @Query("DELETE FROM deleted_events WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
