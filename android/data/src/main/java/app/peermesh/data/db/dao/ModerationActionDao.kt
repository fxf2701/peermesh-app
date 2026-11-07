package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import app.peermesh.data.db.entity.ModerationActionEntity

@Dao
interface ModerationActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: ModerationActionEntity)
}
