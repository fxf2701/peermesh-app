package app.peermesh.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "map_item_domain_state",
    primaryKeys = ["gid", "item_type", "item_id"],
    indices = [
        Index(value = ["gid", "item_type", "category"]),
    ],
)
data class MapItemDomainStateEntity(
    val gid: String,
    val item_type: String,
    val item_id: String,
    val category: String?,
    val category_origin: String,
    val creator_information_source: String?,
    val domain_version: Int?,
    val state_origin: String,
    val owner_did: String,
    val canonical_add_event_id: String?,
    val canonical_add_t: Long?,
    val canonical_add_lt: Long?,
    val canonical_add_actor: String?,
    val situation_lifecycle: String?,
    val expires_at: Long?,
    val owner_edit_until: Long?,
    val interaction_until: Long?,
    val temporal_origin: String,
    @ColumnInfo(defaultValue = "0")
    val confirmation_actor_count: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val acknowledgement_actor_count: Int = 0,
    val latest_confirmation_event_id: String?,
    val latest_confirmation_t: Long?,
    val latest_confirmation_lt: Long?,
    val latest_confirmation_actor: String?,
    val latest_update_event_id: String?,
    val latest_update_t: Long?,
    val latest_update_lt: Long?,
    val latest_update_actor: String?,
    val latest_lifecycle_event_id: String?,
    val latest_lifecycle_t: Long?,
    val latest_lifecycle_lt: Long?,
    val latest_lifecycle_actor: String?,
    val latest_material_event_id: String?,
    val latest_material_t: Long?,
    val latest_material_lt: Long?,
    val latest_material_actor: String?,
)
