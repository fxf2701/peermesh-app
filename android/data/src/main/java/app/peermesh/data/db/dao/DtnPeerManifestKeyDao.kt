package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.peermesh.data.db.entity.DtnPeerManifestKeyEntity

@Dao
interface DtnPeerManifestKeyDao {
    @Query("SELECT * FROM dtn_peer_manifest_keys WHERE peer_id = :peerId AND algorithm = :algorithm LIMIT 1")
    fun find(peerId: String, algorithm: String): DtnPeerManifestKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: DtnPeerManifestKeyEntity)

    @Query("DELETE FROM dtn_peer_manifest_keys WHERE peer_id = :peerId")
    fun delete(peerId: String): Int
}
