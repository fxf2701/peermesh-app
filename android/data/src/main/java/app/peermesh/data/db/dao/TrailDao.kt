package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.TrailEntity
import app.peermesh.data.db.entity.TrailPointEntity

@Dao
interface TrailDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTrail(trail: TrailEntity): Long

    @Query(
        """
        UPDATE trails
        SET name = :name,
            mode = :mode,
            start_t = :startT,
            end_t = :endT,
            point_count = :pointCount,
            dist_m = :distM,
            unstable = :unstable,
            unstable_note = :unstableNote
        WHERE trail_id = :trailId
        """
    )
    fun updateTrail(
        trailId: String,
        name: String,
        mode: String,
        startT: Long,
        endT: Long,
        pointCount: Int,
        distM: Float,
        unstable: Int,
        unstableNote: String?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPoints(points: List<TrailPointEntity>)

    @Query("SELECT * FROM trails ORDER BY start_t DESC LIMIT :limit")
    fun listTrails(limit: Int): List<TrailEntity>

    @Query("SELECT * FROM trails WHERE start_t >= :sinceSeconds ORDER BY start_t DESC")
    fun listTrailsSince(sinceSeconds: Long): List<TrailEntity>

    @Query("SELECT * FROM trails WHERE trail_id = :trailId LIMIT 1")
    fun getTrail(trailId: String): TrailEntity?

    @Query("SELECT * FROM trail_points WHERE tid = :trailId ORDER BY idx ASC")
    fun listPoints(trailId: String): List<TrailPointEntity>

    @Query("DELETE FROM trails WHERE trail_id = :trailId")
    fun deleteTrail(trailId: String)
}
