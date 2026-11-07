package app.peermesh.data.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "map_item_actions",
    primaryKeys = ["gid", "action_id"],
    indices = [
        Index(value = ["gid", "event_id"], unique = true),
        Index(
            value = [
                "gid", "item_type", "item_id", "materialization_state",
                "action_t", "action_lt", "actor_did", "event_id",
            ],
        ),
        Index(
            value = [
                "gid", "item_type", "item_id", "materialization_state",
                "action_type", "actor_did",
            ],
        ),
        Index(
            value = [
                "materialization_state", "gid", "item_type", "item_id",
                "action_t", "action_lt", "actor_did", "event_id",
            ],
        ),
    ],
)
data class MapItemActionEntity(
    val gid: String,
    val action_id: String,
    val event_id: String,
    val event_type: String,
    val map_domain_v: Int,
    val item_type: String,
    val item_id: String,
    val action_type: String,
    val actor_did: String,
    val action_t: Long,
    val action_lt: Long,
    val information_source: String?,
    val action_note: String?,
    val update_disposition: String?,
    val target_lifecycle: String?,
    val event_fingerprint: String,
    val materialization_state: String,
    val rejection_reason: String?,
)
