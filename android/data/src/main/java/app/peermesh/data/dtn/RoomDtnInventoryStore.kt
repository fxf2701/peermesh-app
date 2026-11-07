package app.peermesh.data.dtn

import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.dao.PendingCanonicalMapEventDao
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.transport.DtnTransportPolicy
import app.peermesh.transport.ble.dtn.DtnDataItem
import app.peermesh.transport.ble.dtn.DtnDiagnosticEvent
import app.peermesh.transport.ble.dtn.DtnDiagnosticsSink
import app.peermesh.transport.ble.dtn.DtnInventoryItem
import app.peermesh.transport.ble.dtn.DtnInventoryStore

class RoomDtnInventoryStore(
    private val eventsDao: EventsDao,
    private val pendingCanonicalMapEventDao: PendingCanonicalMapEventDao? = null,
    groupKeyLoader: (String) -> ByteArray?,
    private val diagnostics: DtnDiagnosticsSink? = null,
) : DtnInventoryStore {
    private val envelopeFactory = DtnEventEnvelopeFactory(groupKeyLoader)

    override fun listInventory(gid: String, limit: Int): List<DtnInventoryItem> {
        return listInventoryAfter(gid, lastInsertedAt = 0, lastEventId = "", limit = limit)
    }

    override fun listInventoryAfter(
        gid: String,
        lastInsertedAt: Long,
        lastEventId: String,
        limit: Int,
    ): List<DtnInventoryItem> {
        if (limit <= 0) return emptyList()
        return eventsDao
            .loadDtnCursorWindow(
                gid = gid,
                lastInsertedAt = lastInsertedAt,
                lastEventId = lastEventId,
                limit = limit,
            )
            .map { it.toInventoryItem() }
    }

    override fun existingEventIds(gid: String, eventIds: Collection<String>): Set<String> {
        if (eventIds.isEmpty()) return emptySet()
        return eventsDao
            .loadByEventIdsForDtn(gid, eventIds.distinct())
            .mapTo(LinkedHashSet()) { it.event_id }
    }

    override fun listDependencyRepairWants(gid: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val canonicalRoots = pendingCanonicalMapEventDao
            ?.listPendingRootEventIds(gid, limit)
            .orEmpty()
        val actionRoots = eventsDao.listPendingMapActionRootEventIds(gid, limit)
        return (canonicalRoots + actionRoots)
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
            .toList()
    }

    override fun loadData(gid: String, eventIds: Collection<String>, limit: Int): List<DtnDataItem> {
        if (eventIds.isEmpty() || limit <= 0) return emptyList()
        val requestedOrder = eventIds.distinct().take(limit)
        val entitiesById = eventsDao
            .loadByEventIdsForDtn(gid, requestedOrder)
            .associateBy { it.event_id }
        val loaded = requestedOrder.mapNotNull { eventId ->
            val entity = entitiesById[eventId] ?: return@mapNotNull null
            val envelope = envelopeFactory.toEnvelopeJson(entity) ?: return@mapNotNull null
            DtnDataItem(eventId = eventId, envelope = envelope)
        }
        diagnostics?.log(
            DtnDiagnosticEvent(
                label = "DTN data lookup",
                type = "data",
                direction = "lookup",
                gid = gid,
                idCount = requestedOrder.size,
                dataCount = loaded.size,
                reason = buildString {
                    append("entity_matches=").append(entitiesById.size)
                    append(" requested_sample=").append(requestedOrder.take(5).joinToString(",").ifBlank { "none" })
                    append(" loaded_sample=").append(loaded.take(5).joinToString(",") { it.eventId }.ifBlank { "none" })
                },
            )
        )
        return loaded
    }

    private fun EventEntity.toInventoryItem(): DtnInventoryItem {
        return DtnInventoryItem(
            gid = gid,
            eventId = event_id,
            type = type,
            createdAtSeconds = created_t,
            insertedAtSeconds = inserted_at,
            priority = DtnTransportPolicy.priorityForEventType(type),
        )
    }
}
