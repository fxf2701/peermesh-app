package app.peermesh.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.peermesh.data.db.entity.DtnCarrierManifestBatchEntity
import app.peermesh.data.db.entity.DtnCarrierManifestEntity

@Dao
interface DtnCarrierManifestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(manifest: DtnCarrierManifestEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBatches(batches: List<DtnCarrierManifestBatchEntity>)

    @Query("DELETE FROM dtn_carrier_manifest_batch WHERE manifest_id = :manifestId")
    fun deleteBatches(manifestId: String): Int

    @Transaction
    fun upsertGraph(
        manifest: DtnCarrierManifestEntity,
        batches: List<DtnCarrierManifestBatchEntity>,
    ) {
        upsert(manifest)
        deleteBatches(manifest.manifest_id)
        upsertBatches(batches)
    }

    @Query("SELECT * FROM dtn_carrier_manifest WHERE manifest_id = :manifestId LIMIT 1")
    fun find(manifestId: String): DtnCarrierManifestEntity?

    @Query(
        """
        SELECT * FROM dtn_carrier_manifest
        WHERE session_id = :sessionId
          AND expires_at > :nowSeconds
        ORDER BY created_at DESC
        LIMIT 1
        """
    )
    fun findActiveBySession(sessionId: String, nowSeconds: Long): DtnCarrierManifestEntity?

    @Query(
        """
        SELECT * FROM dtn_carrier_manifest_batch
        WHERE manifest_id = :manifestId
        ORDER BY priority ASC, expires_at ASC, batch_id ASC
        """
    )
    fun listBatches(manifestId: String): List<DtnCarrierManifestBatchEntity>

    @Query(
        """
        SELECT * FROM dtn_carrier_manifest_batch
        WHERE manifest_id = :manifestId
          AND batch_id = :batchId
        LIMIT 1
        """
    )
    fun findBatch(manifestId: String, batchId: String): DtnCarrierManifestBatchEntity?

    @Query(
        """
        UPDATE dtn_carrier_manifest
        SET accepted_at = :acceptedAt
        WHERE manifest_id = :manifestId
        """
    )
    fun markAccepted(manifestId: String, acceptedAt: Long): Int

    @Query("DELETE FROM dtn_carrier_manifest WHERE expires_at <= :nowSeconds")
    fun deleteExpired(nowSeconds: Long): Int

    @Query("DELETE FROM dtn_carrier_manifest WHERE manifest_id = :manifestId")
    fun delete(manifestId: String): Int
}
