package app.peermesh.data.ingest

import app.peermesh.data.map.processing.MapEventRejectionReason

/**
 * M2C4A internal boundary between shared map processing and EventIngestor's Boolean contract.
 *
 * This type deliberately carries no Room entity and no reducer/replay implementation detail.
 * Accepted and AcceptedNoChange both represent a newly committed durable event. The latter is a
 * canonical materialization no-op, not a duplicate.
 */
internal sealed interface RemoteMapIngestOutcome {
    val eventId: String?

    data class Accepted(override val eventId: String) : RemoteMapIngestOutcome
    data class AcceptedNoChange(override val eventId: String) : RemoteMapIngestOutcome
    data class AcceptedDeferred(
        override val eventId: String,
        val rootEventId: String?,
    ) : RemoteMapIngestOutcome
    data class ExactDuplicate(override val eventId: String) : RemoteMapIngestOutcome
    data class ConflictingDuplicate(override val eventId: String) : RemoteMapIngestOutcome
    data class Rejected(
        override val eventId: String?,
        val reason: MapEventRejectionReason,
    ) : RemoteMapIngestOutcome
    data class InvalidPersistedState(
        override val eventId: String,
        val detail: String,
    ) : RemoteMapIngestOutcome
    data class PersistenceFailure(
        override val eventId: String?,
        val cause: Throwable,
    ) : RemoteMapIngestOutcome
}
