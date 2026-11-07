package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.LiveTrailEntity
import app.peermesh.data.db.entity.LiveTrailPointEntity

@Dao
interface LiveTrailDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTrail(trail: LiveTrailEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPoints(points: List<LiveTrailPointEntity>)

    @Query("SELECT * FROM live_trails WHERE gid = :gid ORDER BY updated_t DESC LIMIT :limit")
    fun listTrails(gid: String, limit: Int): List<LiveTrailEntity>

    @Query("SELECT * FROM live_trails WHERE gid = :gid AND active = 1 ORDER BY updated_t DESC LIMIT :limit")
    fun listActiveTrails(gid: String, limit: Int): List<LiveTrailEntity>

    @Query("SELECT * FROM live_trails WHERE gid = :gid AND trail_id = :trailId LIMIT 1")
    fun findTrail(gid: String, trailId: String): LiveTrailEntity?

    @Query("UPDATE live_trails SET accepted = :accepted, pending_expires_t = :pendingExpires WHERE gid = :gid AND trail_id = :trailId")
    fun updateAccepted(gid: String, trailId: String, accepted: Int, pendingExpires: Long?)

    @Query("SELECT * FROM live_trail_points WHERE trail_id = :trailId ORDER BY idx ASC")
    fun listPoints(trailId: String): List<LiveTrailPointEntity>

    @Query("SELECT * FROM live_trail_points WHERE trail_id = :trailId ORDER BY idx DESC LIMIT 1")
    fun findLastPoint(trailId: String): LiveTrailPointEntity?

    @Query("UPDATE live_trails SET point_count = :pointCount, dist_m = :dist WHERE gid = :gid AND trail_id = :trailId")
    fun updateTrailStats(gid: String, trailId: String, pointCount: Int, dist: Float)

    @Query("SELECT * FROM live_trails WHERE accepted = 0 AND pending_expires_t IS NOT NULL AND pending_expires_t <= :cutoff")
    fun findExpiredPendingTrails(cutoff: Long): List<LiveTrailEntity>

    @Query("SELECT trail_id FROM live_trails WHERE active = 0 AND end_t IS NOT NULL AND end_t <= :cutoff")
    fun findEndedTrailIdsBefore(cutoff: Long): List<String>

    @Query("SELECT trail_id FROM live_trails WHERE active = 1 AND updated_t <= :cutoff")
    fun findStaleActiveTrailIds(cutoff: Long): List<String>

    @Query(
        """
        SELECT * FROM live_trails
        WHERE gid = :gid
          AND owner_did = :ownerDid
          AND active = 1
          AND updated_t <= :cutoff
          AND trail_id != :trailId
        """
    )
    fun findStaleActiveTrailsByOwner(
        gid: String,
        ownerDid: String,
        cutoff: Long,
        trailId: String,
    ): List<LiveTrailEntity>

    @Query("DELETE FROM live_trail_points WHERE trail_id IN (:trailIds)")
    fun deletePointsByTrailIds(trailIds: List<String>): Int

    @Query("DELETE FROM live_trail_points WHERE t <= :cutoff")
    fun deletePointsBefore(cutoff: Long): Int

    @Query("DELETE FROM live_trails WHERE trail_id IN (:trailIds)")
    fun deleteTrailsByIds(trailIds: List<String>): Int

    @Query("DELETE FROM live_trail_points WHERE trail_id IN (SELECT trail_id FROM live_trails WHERE gid = :gid)")
    fun deletePointsByGid(gid: String): Int

    @Query("DELETE FROM live_trails WHERE gid = :gid")
    fun deleteTrailsByGid(gid: String): Int
}
