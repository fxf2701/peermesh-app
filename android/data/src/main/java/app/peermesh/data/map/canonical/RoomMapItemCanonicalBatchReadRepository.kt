package app.peermesh.data.map.canonical

import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.dao.MapDao
import app.peermesh.data.map.MapItemIdentity

class RoomMapItemCanonicalBatchReadRepository(
    private val dao: MapDao,
    private val chunkSize: Int = 500,
) : MapItemCanonicalBatchReadRepository {

    override fun findBatch(
        groupId: String,
        identities: Collection<MapItemIdentity>,
    ): MapItemCanonicalBatchLookupResult {
        require(groupId.isNotBlank()) { "groupId must be nonblank" }
        require(chunkSize > 0) { "chunkSize must be positive" }

        val requested = identities.toCollection(linkedSetOf())
        require(requested.none { it.itemId.isBlank() }) { "itemId must be nonblank" }
        if (requested.isEmpty()) {
            return MapItemCanonicalBatchLookupResult(emptyMap(), emptySet(), emptySet())
        }

        val rows = try {
            requested.groupBy(MapItemIdentity::itemType).flatMap { (type, typed) ->
                typed.map(MapItemIdentity::itemId).chunked(chunkSize).flatMap { ids ->
                    when (type) {
                        MapItemType.MARKER -> dao.findMarkersByIds(groupId, ids)
                        MapItemType.AREA -> dao.findAreasByIds(groupId, ids)
                    }
                }
            }
        } catch (_: Exception) {
            return MapItemCanonicalBatchLookupResult(
                found = emptyMap(),
                missing = requested,
                invalid = emptySet(),
                readFailure = true,
            )
        }

        val found = linkedMapOf<MapItemIdentity, MapItemCanonicalRead>()
        val invalid = linkedSetOf<MapItemIdentity>()
        rows.forEach { row ->
            val mapped = when (row) {
                is app.peermesh.data.db.entity.MapMarkerEntity -> MapItemCanonicalEntityMapper.marker(row)
                is app.peermesh.data.db.entity.MapAreaEntity -> MapItemCanonicalEntityMapper.area(row)
                else -> error("Unsupported canonical row type")
            }
            val identity = when (row) {
                is app.peermesh.data.db.entity.MapMarkerEntity -> MapItemIdentity(MapItemType.MARKER, row.marker_id)
                is app.peermesh.data.db.entity.MapAreaEntity -> MapItemIdentity(MapItemType.AREA, row.area_id)
                else -> error("Unsupported canonical row type")
            }
            if (identity !in requested) {
                return MapItemCanonicalBatchLookupResult(
                    found = emptyMap(),
                    missing = requested,
                    invalid = emptySet(),
                    readFailure = true,
                )
            }
            when (mapped) {
                is MapItemCanonicalLookupResult.Found -> {
                    val item = mapped.item
                    if (item.groupId == groupId &&
                        item.itemType == identity.itemType &&
                        item.itemId == identity.itemId &&
                        identity in requested
                    ) {
                        val prior = found[identity]
                        if (prior == null) {
                            found[identity] = item
                        } else {
                            found -= identity
                            invalid += identity
                        }
                    } else {
                        invalid += identity
                    }
                }
                MapItemCanonicalLookupResult.InvalidRecord -> invalid += identity
                MapItemCanonicalLookupResult.Missing -> Unit
                MapItemCanonicalLookupResult.ReadFailure -> return MapItemCanonicalBatchLookupResult(
                    found = emptyMap(), missing = requested, invalid = emptySet(), readFailure = true,
                )
            }
        }

        val orderedFound = requested.mapNotNull { identity ->
            found[identity]?.takeIf { identity !in invalid }?.let { identity to it }
        }.toMap(linkedMapOf())
        val orderedInvalid = requested.filterTo(linkedSetOf()) { it in invalid }
        return MapItemCanonicalBatchLookupResult(
            found = orderedFound,
            missing = requested.filterTo(linkedSetOf()) { it !in orderedFound && it !in orderedInvalid },
            invalid = orderedInvalid,
        )
    }
}
