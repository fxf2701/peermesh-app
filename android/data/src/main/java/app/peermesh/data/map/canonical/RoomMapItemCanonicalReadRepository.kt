package app.peermesh.data.map.canonical

import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.dao.MapDao
import app.peermesh.data.map.MapItemIdentity

class RoomMapItemCanonicalReadRepository(
    private val dao: MapDao,
) : MapItemCanonicalReadRepository {

    override fun find(
        groupId: String,
        identity: MapItemIdentity,
    ): MapItemCanonicalLookupResult {
        if (groupId.isBlank() || identity.itemId.isBlank()) return MapItemCanonicalLookupResult.InvalidRecord
        return try {
            val mapped = when (identity.itemType) {
                MapItemType.MARKER -> dao.findMarker(groupId, identity.itemId)?.let(MapItemCanonicalEntityMapper::marker)
                MapItemType.AREA -> dao.findArea(groupId, identity.itemId)?.let(MapItemCanonicalEntityMapper::area)
            } ?: return MapItemCanonicalLookupResult.Missing
            when (mapped) {
                is MapItemCanonicalLookupResult.Found -> {
                    val item = mapped.item
                    if (item.groupId == groupId && item.itemType == identity.itemType && item.itemId == identity.itemId) mapped
                    else MapItemCanonicalLookupResult.InvalidRecord
                }
                else -> mapped
            }
        } catch (_: Exception) {
            MapItemCanonicalLookupResult.ReadFailure
        }
    }
}
