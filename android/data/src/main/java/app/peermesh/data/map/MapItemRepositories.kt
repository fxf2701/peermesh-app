package app.peermesh.data.map

import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.MapItemPersistenceMutationValidator
import app.peermesh.data.db.MapItemPersistenceValues
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.dao.MapItemActionDao
import app.peermesh.data.db.dao.MapItemDomainStateDao
import app.peermesh.data.db.entity.MapItemActionEntity
import app.peermesh.data.db.failure

interface MapItemDomainStateRepository {
    fun find(gid: String, identity: MapItemIdentity): MapQueryResult<MapItemLookupResult>
    fun findBatch(gid: String, identities: Collection<MapItemIdentity>): MapQueryResult<MapItemBatchLookupResult>
}

class RoomMapItemDomainStateRepository(
    private val dao: MapItemDomainStateDao,
    private val chunkSize: Int = 500,
) : MapItemDomainStateRepository {
    override fun find(gid: String, identity: MapItemIdentity): MapQueryResult<MapItemLookupResult> {
        invalidIdentity(gid, identity)?.let { return MapQueryResult.InvalidInput(it) }
        val row = dao.find(gid, persisted(identity.itemType), identity.itemId) ?: return MapQueryResult.Success(MapItemLookupResult.Missing)
        return when (val mapped = MapItemEntityMappers.domain(row)) {
            is PersistenceParseResult.Success -> MapQueryResult.Success(MapItemLookupResult.Found(mapped.value))
            else -> MapQueryResult.Success(MapItemLookupResult.Invalid(mapped.failure()))
        }
    }

    override fun findBatch(gid: String, identities: Collection<MapItemIdentity>): MapQueryResult<MapItemBatchLookupResult> {
        if (gid.isBlank()) return invalid("gid must be nonblank")
        if (chunkSize <= 0) return invalid("chunk size must be positive")
        val requested = linkedSetOf<MapItemIdentity>()
        identities.forEach { identity -> invalidIdentity(gid, identity)?.let { return MapQueryResult.InvalidInput(it) }; requested += identity }
        val rows = requested.groupBy { it.itemType }.flatMap { (type, values) ->
            values.map { it.itemId }.chunked(chunkSize).flatMap { dao.findByItemIds(gid, persisted(type), it) }
        }
        val found = linkedMapOf<MapItemIdentity, MapItemDomainState>()
        val invalid = linkedMapOf<MapItemIdentity, PersistenceParseResult<Nothing>>()
        rows.forEach { row ->
            val identityResult = MapItemPersistenceValues.ItemType.parse(row.item_type)
            val identity = if (identityResult is PersistenceParseResult.Success) MapItemIdentity(identityResult.value, row.item_id) else return@forEach
            when (val mapped = MapItemEntityMappers.domain(row)) {
                is PersistenceParseResult.Success -> found[identity] = mapped.value
                else -> invalid[identity] = mapped.failure()
            }
        }
        return MapQueryResult.Success(MapItemBatchLookupResult(found, requested - found.keys - invalid.keys, invalid))
    }

    private fun persisted(type: MapItemType) = MapItemPersistenceValues.ItemType.valueOf(type)
}

interface MapItemActionQueryRepository {
    fun pendingTarget(gid: String, identity: MapItemIdentity): MapQueryResult<List<MapItemActionRead>>
    fun scanPending(gid: String, after: MapActionOrderKey?, limit: Int): MapQueryResult<List<MapItemActionRead>>
    fun latestApplied(gid: String, identity: MapItemIdentity): MapQueryResult<MapItemActionRead?>
    fun latestApplied(gid: String, identity: MapItemIdentity, action: MapItemAction): MapQueryResult<MapItemActionRead?>
    fun latestMaterialChange(gid: String, identity: MapItemIdentity): MapQueryResult<MapItemActionRead?>
    fun confirmationActorCount(gid: String, identity: MapItemIdentity, after: MapActionOrderKey? = null): MapQueryResult<Int>
    fun acknowledgementActorCount(gid: String, identity: MapItemIdentity): MapQueryResult<Int>
    fun appliedHistory(gid: String, identity: MapItemIdentity, after: MapActionHistoryCursor?, limit: Int): MapQueryResult<MapActionHistoryPage>
}

class RoomMapItemActionQueryRepository(private val dao: MapItemActionDao) : MapItemActionQueryRepository {
    override fun pendingTarget(gid: String, identity: MapItemIdentity) = list(gid, identity) { dao.findPendingTarget(gid, persisted(identity), identity.itemId) }
    override fun scanPending(gid: String, after: MapActionOrderKey?, limit: Int): MapQueryResult<List<MapItemActionRead>> {
        validate(gid, null, after, limit)?.let { return MapQueryResult.InvalidInput(it) }
        return mapRows(dao.scanPending(gid, after?.timestampSeconds, after?.lamport, after?.actorDid, after?.eventId, limit))
    }
    override fun latestApplied(gid: String, identity: MapItemIdentity) = one(gid, identity) { dao.findLatestApplied(gid, persisted(identity), identity.itemId) }
    override fun latestApplied(gid: String, identity: MapItemIdentity, action: MapItemAction) = one(gid, identity) {
        dao.findLatestAppliedOfType(gid, persisted(identity), identity.itemId, MapItemPersistenceValues.ActionType.valueOf(action))
    }
    override fun latestMaterialChange(gid: String, identity: MapItemIdentity) = one(gid, identity) { dao.findLatestMaterialChange(gid, persisted(identity), identity.itemId) }
    override fun confirmationActorCount(gid: String, identity: MapItemIdentity, after: MapActionOrderKey?): MapQueryResult<Int> {
        validate(gid, identity, after, null)?.let { return MapQueryResult.InvalidInput(it) }
        return MapQueryResult.Success(if (after == null) dao.countConfirmationActors(gid, persisted(identity), identity.itemId)
            else dao.countConfirmationActorsAfter(gid, persisted(identity), identity.itemId, after.timestampSeconds, after.lamport, after.actorDid, after.eventId))
    }
    override fun acknowledgementActorCount(gid: String, identity: MapItemIdentity): MapQueryResult<Int> {
        validate(gid, identity, null, null)?.let { return MapQueryResult.InvalidInput(it) }
        return MapQueryResult.Success(dao.countAcknowledgementActors(gid, persisted(identity), identity.itemId))
    }
    override fun appliedHistory(gid: String, identity: MapItemIdentity, after: MapActionHistoryCursor?, limit: Int): MapQueryResult<MapActionHistoryPage> {
        validate(gid, identity, after?.order, limit)?.let { return MapQueryResult.InvalidInput(it) }
        return when (val mapped = mapRows(dao.findAppliedHistory(gid, persisted(identity), identity.itemId,
            after?.order?.timestampSeconds, after?.order?.lamport, after?.order?.actorDid, after?.order?.eventId, limit))) {
            is MapQueryResult.Success -> MapQueryResult.Success(MapActionHistoryPage(mapped.value,
                mapped.value.lastOrNull()?.let { MapActionHistoryCursor(it.order) }))
            is MapQueryResult.InvalidRecord -> mapped
            is MapQueryResult.InvalidInput -> mapped
        }
    }
    private fun list(gid: String, identity: MapItemIdentity, query: () -> List<MapItemActionEntity>): MapQueryResult<List<MapItemActionRead>> {
        validate(gid, identity, null, null)?.let { return MapQueryResult.InvalidInput(it) }; return mapRows(query())
    }
    private fun one(gid: String, identity: MapItemIdentity, query: () -> MapItemActionEntity?): MapQueryResult<MapItemActionRead?> {
        validate(gid, identity, null, null)?.let { return MapQueryResult.InvalidInput(it) }
        val row = query() ?: return MapQueryResult.Success(null)
        return when (val mapped = MapItemEntityMappers.action(row)) {
            is PersistenceParseResult.Success -> MapQueryResult.Success(mapped.value)
            else -> MapQueryResult.InvalidRecord(mapped.failure())
        }
    }
    private fun mapRows(rows: List<MapItemActionEntity>): MapQueryResult<List<MapItemActionRead>> {
        val result = ArrayList<MapItemActionRead>(rows.size)
        rows.forEach { when (val mapped = MapItemEntityMappers.action(it)) {
            is PersistenceParseResult.Success -> result += mapped.value
            else -> return MapQueryResult.InvalidRecord(mapped.failure())
        } }
        return MapQueryResult.Success(result)
    }
    private fun persisted(identity: MapItemIdentity) = MapItemPersistenceValues.ItemType.valueOf(identity.itemType)
}

private fun validate(gid: String, identity: MapItemIdentity?, order: MapActionOrderKey?, limit: Int?): PersistenceParseResult<Nothing>? {
    if (gid.isBlank()) return shape("gid must be nonblank")
    if (identity != null && identity.itemId.isBlank()) return shape("item_id must be nonblank")
    if (limit != null && limit <= 0) return shape("limit must be positive")
    if (order != null) when (val result = MapItemPersistenceMutationValidator.order(order)) {
        is PersistenceParseResult.Success -> Unit
        else -> return result.failure()
    }
    return null
}
private fun invalidIdentity(gid: String, identity: MapItemIdentity) = validate(gid, identity, null, null)
private fun <T> invalid(reason: String): MapQueryResult<T> = MapQueryResult.InvalidInput(shape(reason))
private fun shape(reason: String) = PersistenceParseResult.InvalidRecordShape(reason)
