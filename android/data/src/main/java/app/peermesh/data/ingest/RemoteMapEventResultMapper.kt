package app.peermesh.data.ingest

import app.peermesh.data.map.processing.MapEventProcessingResult

/**
 * M2C4A result-mapping seam only.
 *
 * Implementations must remain pure: no Room access, notification, replay-cache mutation,
 * durable-dedupe mutation or logging is allowed here.
 */
internal fun interface RemoteMapEventResultMapper {
    fun map(result: MapEventProcessingResult): RemoteMapIngestOutcome
}

/** Pure exhaustive mapping; post-commit effects remain owned by [EventIngestor]. */
internal object DefaultRemoteMapEventResultMapper : RemoteMapEventResultMapper {
    override fun map(result: MapEventProcessingResult): RemoteMapIngestOutcome = when (result) {
        is MapEventProcessingResult.Accepted -> RemoteMapIngestOutcome.Accepted(result.eventId)
        is MapEventProcessingResult.AcceptedNoChange -> RemoteMapIngestOutcome.AcceptedNoChange(result.eventId)
        is MapEventProcessingResult.AcceptedDeferred ->
            RemoteMapIngestOutcome.AcceptedDeferred(result.eventId, result.rootEventId)
        is MapEventProcessingResult.ExactDuplicate -> RemoteMapIngestOutcome.ExactDuplicate(result.eventId)
        is MapEventProcessingResult.ConflictingDuplicate -> RemoteMapIngestOutcome.ConflictingDuplicate(result.eventId)
        is MapEventProcessingResult.Rejected -> RemoteMapIngestOutcome.Rejected(result.eventId, result.reason)
        is MapEventProcessingResult.InvalidPersistedState ->
            RemoteMapIngestOutcome.InvalidPersistedState(result.eventId, result.detail)
        is MapEventProcessingResult.PersistenceFailure ->
            RemoteMapIngestOutcome.PersistenceFailure(result.eventId, result.cause)
    }
}
