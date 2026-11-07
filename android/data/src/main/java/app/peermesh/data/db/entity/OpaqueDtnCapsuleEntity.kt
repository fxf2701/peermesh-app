package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "opaque_dtn_capsules",
    indices = [
        Index(value = ["gid", "received_at"]),
        Index(value = ["gid", "event_id"]),
        Index(value = ["batch_id"]),
        Index(value = ["expires_at"]),
        Index(value = ["priority", "received_at"]),
        Index(value = ["manifest_id"]),
    ],
)
data class OpaqueDtnCapsuleEntity(
    @PrimaryKey val capsule_id: String,
    val gid: String,
    val gid_hash: String?,
    val event_id: String?,
    val batch_id: String?,
    val kind: String,
    val payload: ByteArray,
    val payload_sha256: String,
    val payload_bytes: Long,
    val created_t: Long,
    val received_at: Long,
    val expires_at: Long?,
    val hop_count: Int,
    val max_hops: Int,
    val priority: Int,
    val source_peer_id: String?,
    val last_forwarded_at: Long?,
    val manifest_id: String?,
)

object OpaqueDtnCapsuleKind {
    const val ENVELOPE = "envelope"
    const val APP_BATCH = "app_batch"
}
