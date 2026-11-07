package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.RelayCursorEntity

@Dao
interface RelayCursorDao {
    @Query("SELECT * FROM relay_cursors WHERE relay_url = :relayUrl AND gid = :gid LIMIT 1")
    fun find(relayUrl: String, gid: String): RelayCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: RelayCursorEntity)

    @Query("DELETE FROM relay_cursors WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
