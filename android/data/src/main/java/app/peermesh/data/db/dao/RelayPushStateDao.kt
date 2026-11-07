package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.RelayPushStateEntity

@Dao
interface RelayPushStateDao {
    @Query("SELECT * FROM relay_push_state WHERE relay_url = :relayUrl LIMIT 1")
    fun find(relayUrl: String): RelayPushStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: RelayPushStateEntity)
}
