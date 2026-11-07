package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapLayerEntity
import app.peermesh.data.db.entity.MapMarkerEntity

@Dao
interface MapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertLayer(layer: MapLayerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMarker(marker: MapMarkerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertArea(area: MapAreaEntity)

    @Query("SELECT * FROM map_markers WHERE gid = :gid AND marker_id = :markerId")
    fun findMarker(gid: String, markerId: String): MapMarkerEntity?

    @Query("SELECT * FROM map_areas WHERE gid = :gid AND area_id = :areaId")
    fun findArea(gid: String, areaId: String): MapAreaEntity?

    @Query("SELECT * FROM map_markers WHERE gid = :gid AND marker_id IN (:markerIds)")
    fun findMarkersByIds(gid: String, markerIds: List<String>): List<MapMarkerEntity>

    @Query("SELECT * FROM map_areas WHERE gid = :gid AND area_id IN (:areaIds)")
    fun findAreasByIds(gid: String, areaIds: List<String>): List<MapAreaEntity>

    @Query(
        "SELECT * FROM map_markers " +
            "WHERE gid = :gid AND status = 'active' " +
            "AND (expires_at IS NULL OR expires_at > :nowSeconds) " +
            "ORDER BY updated_t DESC LIMIT :limit"
    )
    fun listActiveMarkers(gid: String, nowSeconds: Long, limit: Int): List<MapMarkerEntity>

    @Query(
        "SELECT * FROM map_areas " +
            "WHERE gid = :gid AND status = 'active' " +
            "AND (expires_at IS NULL OR expires_at > :nowSeconds) " +
            "ORDER BY updated_t DESC LIMIT :limit"
    )
    fun listActiveAreas(gid: String, nowSeconds: Long, limit: Int): List<MapAreaEntity>

    /** Presentation candidate discovery only. Semantic renderability belongs to M3D1. */
    @Query("SELECT * FROM map_markers WHERE gid = :gid ORDER BY updated_t DESC LIMIT :limit")
    fun listMarkerCandidates(gid: String, limit: Int): List<MapMarkerEntity>

    /** Presentation candidate discovery only. Semantic renderability belongs to M3D1. */
    @Query("SELECT * FROM map_areas WHERE gid = :gid ORDER BY updated_t DESC LIMIT :limit")
    fun listAreaCandidates(gid: String, limit: Int): List<MapAreaEntity>

    @Query("DELETE FROM map_markers WHERE gid = :gid")
    fun deleteMarkersByGid(gid: String): Int

    @Query("DELETE FROM map_areas WHERE gid = :gid")
    fun deleteAreasByGid(gid: String): Int

    @Query("DELETE FROM map_layers WHERE gid = :gid")
    fun deleteLayersByGid(gid: String): Int
}
