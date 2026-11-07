package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dtn_carrier_manifest",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["sender_peer_id", "expires_at"]),
        Index(value = ["gid_hash", "expires_at"]),
        Index(value = ["expires_at"]),
    ],
)
data class DtnCarrierManifestEntity(
    @PrimaryKey val manifest_id: String,
    val session_id: String,
    val sender_peer_id: String,
    val receiver_peer_id: String?,
    val gid_hash: String,
    val max_batches: Int,
    val max_bytes: Long,
    val expires_at: Long,
    val nonce: String,
    val signature: ByteArray,
    val created_at: Long,
    val accepted_at: Long?,
)
