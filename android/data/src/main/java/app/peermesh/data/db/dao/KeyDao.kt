package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.KeyEntity

@Dao
interface KeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(key: KeyEntity)

    @Query("SELECT * FROM keys WHERE gid = :gid")
    fun findByGid(gid: String): KeyEntity?

    @Query("DELETE FROM keys WHERE gid = :gid")
    fun delete(gid: String): Int
}
