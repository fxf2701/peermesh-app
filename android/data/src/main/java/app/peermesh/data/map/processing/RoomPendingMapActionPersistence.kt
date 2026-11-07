package app.peermesh.data.map.processing

import app.peermesh.data.db.dao.MapItemActionDao
import app.peermesh.data.db.MapItemPersistenceRecordValidator
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.PersistenceWriteResult
import app.peermesh.data.db.entity.MapItemActionEntity

/**
 * Room-backed M2C3A implementation anchor.
 *
 * Immutable pending insertion and duplicate classification. The durable event insert and
 * transaction remain owned by RoomMapEventProcessor.
 */
internal class RoomPendingMapActionPersistence(
    private val actionDao: MapItemActionDao,
    private val fingerprint: MapEventFingerprint,
    private val entityFactory: MapItemActionEntityFactory,
) : PendingMapActionPersistence {
    override fun persist(
        request: PendingMapActionPersistenceRequest,
    ): PendingMapActionPersistenceResult {
        if (request.gid.isBlank() || request.itemId.isBlank() ||
            request.itemType != request.interaction.itemType ||
            request.itemId != request.interaction.itemId()
        ) return PendingMapActionPersistenceResult.InvalidPersistedState("invalid_pending_identity")
        val expected = when (val mapped = entityFactory.create(
            MapItemActionPersistenceInput(request.gid, request.event, request.interaction),
            fingerprint.sha256(request.event),
            MapItemActionInitialState.PENDING_TARGET,
        )) {
            is PersistenceParseResult.Success -> mapped.value
            else -> return PendingMapActionPersistenceResult.InvalidPersistedState(mapped.toString())
        }
        return when (val inserted = actionDao.insertIgnore(expected)) {
            is PersistenceWriteResult.Rejected ->
                PendingMapActionPersistenceResult.InvalidPersistedState(inserted.failure.toString())
            is PersistenceWriteResult.Success -> if (inserted.value != -1L) {
                PendingMapActionPersistenceResult.Inserted
            } else classify(expected)
        }
    }

    private fun classify(expected: MapItemActionEntity): PendingMapActionPersistenceResult {
        val byAction = actionDao.findByActionId(expected.gid, expected.action_id)
        val byEvent = actionDao.findByEventId(expected.gid, expected.event_id)
        if (byAction == null || byEvent == null || byAction != byEvent) {
            return PendingMapActionPersistenceResult.InvalidPersistedState("action_event_indexes_inconsistent")
        }
        if (MapItemPersistenceRecordValidator.validate(byAction) !is PersistenceParseResult.Success) {
            return PendingMapActionPersistenceResult.InvalidPersistedState("stored_action_contract_invalid")
        }
        if (byAction.event_fingerprint != expected.event_fingerprint) {
            return PendingMapActionPersistenceResult.ConflictingDuplicate
        }
        return if (byAction.sameImmutableActionAs(expected)) {
            PendingMapActionPersistenceResult.ExactDuplicate
        } else PendingMapActionPersistenceResult.InvalidPersistedState("immutable_action_row_contradiction")
    }
}

internal fun MapItemActionEntity.sameImmutableActionAs(other: MapItemActionEntity): Boolean =
    copy(materialization_state = other.materialization_state, rejection_reason = other.rejection_reason) == other
