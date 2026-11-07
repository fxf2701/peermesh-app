package app.peermesh.data.db.entity

import androidx.room.Entity
@Entity(
    tableName = "map_layers",
    primaryKeys = ["gid", "layer_id"]
)
data class MapLayerEntity(
    val gid: String,
    val layer_id: String,
    val name: String?,
    val status: String,
    val updated_t: Long,
)
