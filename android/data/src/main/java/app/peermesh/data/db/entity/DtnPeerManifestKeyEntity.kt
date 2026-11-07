package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dtn_peer_manifest_keys",
    indices = [
        Index(value = ["algorithm", "updated_at"]),
    ],
)
data class DtnPeerManifestKeyEntity(
    @PrimaryKey val peer_id: String,
    val algorithm: String,
    val key_bytes: ByteArray,
    val first_seen_at: Long,
    val updated_at: Long,
)
