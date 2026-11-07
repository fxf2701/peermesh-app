package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "dtn_carrier_manifest_batch",
    primaryKeys = ["manifest_id", "batch_id"],
    foreignKeys = [
        ForeignKey(
            entity = DtnCarrierManifestEntity::class,
            parentColumns = ["manifest_id"],
            childColumns = ["manifest_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["batch_id"]),
        Index(value = ["payload_sha256"]),
        Index(value = ["expires_at"]),
        Index(value = ["priority", "expires_at"]),
    ],
)
data class DtnCarrierManifestBatchEntity(
    val manifest_id: String,
    val batch_id: String,
    val payload_sha256: String,
    val payload_bytes: Long,
    val kind: String,
    val expires_at: Long,
    val priority: Int,
)
