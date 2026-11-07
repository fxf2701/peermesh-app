package app.peermesh.map.application.creation

import app.peermesh.core.model.EventCore
import app.peermesh.core.validation.EventValidator
import app.peermesh.data.map.local.LocalMapEventWriter
import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.local.LocalMapWriteRequest
import app.peermesh.data.map.processing.MapEventRejectionReason

/** App-facing canonical creation submission boundary. */
fun interface SubmitMapCreation {
    fun submit(intent: MapCreationIntent): MapCreationSubmissionResult
}

/** Canonical creation orchestration; fatal [Error] subclasses deliberately propagate. */
class DefaultSubmitMapCreation(
    private val validator: ValidateMapCreationIntent,
    private val constructionContextProvider: MapCreationConstructionContextProvider,
    private val eventFactory: MapCreationEventFactoryAdapter,
    private val keyContextAttacher: MapCreationKeyContextAttacher,
    private val localWriter: LocalMapEventWriter,
    private val postCommitTransport: MapCreationPostCommitTransport,
) : SubmitMapCreation {
    override fun submit(intent: MapCreationIntent): MapCreationSubmissionResult {
        val validation = try {
            validator.validate(intent)
        } catch (_: Exception) {
            return failed(null, MapCreationSubmissionFailure.INTERNAL_FAILURE)
        }
        if (validation is MapCreationValidationResult.Invalid) {
            return MapCreationSubmissionResult.InvalidInput(validation.failure)
        }

        val shape = when (intent) {
            is MapCreationIntent.Marker -> MapCreationShape.MARKER
            is MapCreationIntent.Area -> MapCreationShape.AREA
        }
        val context = try {
            constructionContextProvider.create(intent.groupId, shape)
        } catch (_: Exception) {
            return failed(null, MapCreationSubmissionFailure.INTERNAL_FAILURE)
        }
        if (!context.isValid()) {
            return failed(context.itemId.ifBlank { null }, MapCreationSubmissionFailure.INVALID_LOCAL_STATE)
        }

        val core = try {
            eventFactory.create(intent, context)
        } catch (_: Exception) {
            return failed(context.itemId, MapCreationSubmissionFailure.INVALID_CONSTRUCTED_EVENT)
        }
        if (!EventValidator.validate(core)) {
            return failed(context.itemId, MapCreationSubmissionFailure.INVALID_CONSTRUCTED_EVENT)
        }
        val finalCore = try {
            keyContextAttacher.attach(intent.groupId, core)
        } catch (_: Exception) {
            return failed(context.itemId, MapCreationSubmissionFailure.INTERNAL_FAILURE)
        }
        val outcome = try {
            localWriter.write(
                LocalMapWriteRequest(
                    gid = intent.groupId,
                    core = finalCore,
                    localActorDid = context.actorDid,
                    insertedAtSeconds = context.insertedAtSeconds,
                ),
            )
        } catch (_: Exception) {
            return failed(context.itemId, MapCreationSubmissionFailure.PERSISTENCE_FAILURE)
        }
        val mapped = outcome.toSubmissionResult(context.itemId)
        if (outcome !is LocalMapWriteOutcome.AcceptedNew ||
            mapped !is MapCreationSubmissionResult.Accepted
        ) {
            return mapped
        }
        val delivery = try {
            postCommitTransport.dispatch(intent.groupId, outcome, finalCore)
        } catch (_: Exception) {
            MapCreationDeliveryState.TRANSPORT_FAILURE
        }
        return mapped.copy(delivery = delivery)
    }

    private fun LocalMapWriteOutcome.toSubmissionResult(itemId: String): MapCreationSubmissionResult =
        when (this) {
            is LocalMapWriteOutcome.AcceptedNew ->
                MapCreationSubmissionResult.Accepted(itemId, MapCreationDeliveryState.ENQUEUED)
            is LocalMapWriteOutcome.AcceptedNoChange,
            is LocalMapWriteOutcome.ExactDuplicate,
            -> MapCreationSubmissionResult.AlreadyRecorded(itemId)
            is LocalMapWriteOutcome.ConflictingDuplicate ->
                MapCreationSubmissionResult.Rejected(itemId, MapCreationRejection.CONFLICTING_DUPLICATE)
            is LocalMapWriteOutcome.SemanticRejected ->
                MapCreationSubmissionResult.Rejected(itemId, reason.toCreationRejection())
            is LocalMapWriteOutcome.InvalidPersistedState ->
                failed(itemId, MapCreationSubmissionFailure.PERSISTENCE_FAILURE)
            is LocalMapWriteOutcome.PersistenceFailure ->
                failed(itemId, MapCreationSubmissionFailure.PERSISTENCE_FAILURE)
        }

    private fun MapEventRejectionReason.toCreationRejection(): MapCreationRejection = when (this) {
        MapEventRejectionReason.ACTOR_NOT_MEMBER,
        MapEventRejectionReason.LOCAL_MEMBER_REMOVED,
        -> MapCreationRejection.NOT_CURRENT_MEMBER
        MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED,
        MapEventRejectionReason.ACTION_NOT_ALLOWED_FOR_CATEGORY,
        -> MapCreationRejection.INVALID_CATEGORY
        MapEventRejectionReason.CONFLICTING_ADD -> MapCreationRejection.CONFLICTING_DUPLICATE
        MapEventRejectionReason.INVALID_ACTION_PAYLOAD -> MapCreationRejection.STRUCTURAL_REJECTION
        MapEventRejectionReason.TARGET_DELETED,
        MapEventRejectionReason.TARGET_EXPIRED,
        MapEventRejectionReason.STALE_CANONICAL_EVENT,
        -> MapCreationRejection.INVALID_TARGET_STATE
        else -> MapCreationRejection.UNKNOWN
    }

    private fun failed(itemId: String?, reason: MapCreationSubmissionFailure) =
        MapCreationSubmissionResult.Failed(itemId, reason)
}

fun interface MapCreationKeyContextAttacher {
    fun attach(groupId: String, core: EventCore): EventCore
}

fun interface MapCreationPostCommitTransport {
    fun dispatch(
        groupId: String,
        outcome: LocalMapWriteOutcome.AcceptedNew,
        core: EventCore,
    ): MapCreationDeliveryState
}
