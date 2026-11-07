package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.MapMarkerFieldClockEntity

@Dao
interface MapMarkerFieldClockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(clock: MapMarkerFieldClockEntity)

    @Query(
        """
        SELECT * FROM map_marker_field_clock
        WHERE gid = :gid AND marker_id = :markerId AND field = :field
        """
    )
    fun find(gid: String, markerId: String, field: String): MapMarkerFieldClockEntity?

    @Query("DELETE FROM map_marker_field_clock WHERE gid = :gid")
    fun deleteByGid(gid: String): Int
}
