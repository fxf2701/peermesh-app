package app.peermesh.data.map.local

import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.MapEventProcessingResult
import app.peermesh.data.map.processing.MapEventProcessor

/**
 * M2C4B compatibility adapter backed by the accepted shared map processor.
 *
 * This class must remain a thin adapter:
 * - no semantic validation;
 * - no duplicate policy;
 * - no Room transaction;
 * - no DAO or reducer construction;
 * - no group-key lookup;
 * - no BLE/relay enqueue;
 * - no Android UI feedback.
 */
internal class RoomLocalMapEventWriter(
    private val processor: MapEventProcessor,
) : LocalMapEventWriter {

    override fun write(request: LocalMapWriteRequest): LocalMapWriteOutcome {
        val result = processor.process(toProcessingRequest(request))
        if (result is MapEventProcessingResult.Accepted) {
            ActivityLog.add(
                label = "Local map event committed",
                source = LOCAL_SOURCE,
                message = "type=${request.core.type} target=${request.core.ref.target ?: "-"}",
                eventId = result.eventId,
                gid = request.gid,
            )
        }
        return mapProcessingResult(result)
    }

    private fun mapProcessingResult(result: MapEventProcessingResult): LocalMapWriteOutcome {
        return when (result) {
            is MapEventProcessingResult.Accepted -> LocalMapWriteOutcome.AcceptedNew(result.eventId)
            is MapEventProcessingResult.AcceptedNoChange -> LocalMapWriteOutcome.AcceptedNoChange(result.eventId)
            is MapEventProcessingResult.AcceptedDeferred -> LocalMapWriteOutcome.SemanticRejected(
                result.eventId,
                app.peermesh.data.map.processing.MapEventRejectionReason.TARGET_NOT_FOUND,
            )
            is MapEventProcessingResult.ExactDuplicate -> LocalMapWriteOutcome.ExactDuplicate(result.eventId)
            is MapEventProcessingResult.ConflictingDuplicate -> LocalMapWriteOutcome.ConflictingDuplicate(result.eventId)
            is MapEventProcessingResult.Rejected -> LocalMapWriteOutcome.SemanticRejected(result.eventId, result.reason)
            is MapEventProcessingResult.InvalidPersistedState -> LocalMapWriteOutcome.InvalidPersistedState(result.eventId, result.detail)
            is MapEventProcessingResult.PersistenceFailure -> LocalMapWriteOutcome.PersistenceFailure(result.eventId, result.cause)
        }
    }

    private fun toProcessingRequest(request: LocalMapWriteRequest): MapEventProcessingRequest {
        return MapEventProcessingRequest(
            gid = request.gid,
            core = request.core,
            source = LOCAL_SOURCE,
            insertedAtSeconds = request.insertedAtSeconds,
            localActorDid = request.localActorDid,
        )
    }

    private companion object {
        const val LOCAL_SOURCE: String = "local"
    }
}
