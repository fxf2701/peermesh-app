package app.peermesh.map.application.submission

import app.peermesh.core.validation.EventValidator
import app.peermesh.data.map.local.LocalMapEventWriter
import app.peermesh.data.map.local.LocalMapWriteOutcome
import app.peermesh.data.map.local.LocalMapWriteRequest

fun interface SubmitMapInteraction {
    fun submit(command: MapInteractionCommand): MapInteractionSubmissionResult
}

fun interface MapCanonicalRootResolver {
    fun resolve(target: app.peermesh.map.application.detail.MapItemIdentity): String?
}

/**
 * M3A3 application orchestration only.
 *
 * This class must not call DAOs, reducers, Room transactions, BLE or relay APIs directly.
 */
class DefaultSubmitMapInteraction(
    private val inputValidator: MapInteractionInputValidator,
    private val constructionContextProvider: MapEventConstructionContextProvider,
    private val eventFactory: MapInteractionEventFactoryAdapter,
    private val keyContextAttacher: MapEventKeyContextAttacher,
    private val localWriter: LocalMapEventWriter,
    private val postCommitTransport: MapInteractionPostCommitTransport,
    private val outcomeMapper: MapInteractionOutcomeMapper,
    private val canonicalRootResolver: MapCanonicalRootResolver = MapCanonicalRootResolver { null },
) : SubmitMapInteraction {
    override fun submit(command: MapInteractionCommand): MapInteractionSubmissionResult {
        val validation = try {
            inputValidator.validate(command)
        } catch (_: Exception) {
            return failed(null, MapInteractionSubmissionFailure.INTERNAL_FAILURE)
        }
        if (validation is MapInteractionInputValidationResult.Invalid) {
            return MapInteractionSubmissionResult.InvalidInput(validation.failure)
        }

        val context = try {
            constructionContextProvider.create(command.target.groupId)
        } catch (_: Exception) {
            return failed(null, MapInteractionSubmissionFailure.INTERNAL_FAILURE)
        }
        if (!context.isValid()) {
            return failed(context.eventId.ifBlank { null }, MapInteractionSubmissionFailure.INVALID_LOCAL_STATE)
        }

        val core = try {
            eventFactory.create(command, context)
                .let { constructed ->
                    canonicalRootResolver.resolve(command.target)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { root -> constructed.copy(ref = constructed.ref.copy(root = root)) }
                        ?: constructed
                }
        } catch (_: Exception) {
            return failed(context.eventId, MapInteractionSubmissionFailure.INVALID_CONSTRUCTED_EVENT)
        }
        if (!isStructurallyValid(core)) {
            return failed(context.eventId, MapInteractionSubmissionFailure.INVALID_CONSTRUCTED_EVENT)
        }

        val finalCore = try {
            keyContextAttacher.attach(command.target.groupId, core)
        } catch (_: Exception) {
            return failed(context.eventId, MapInteractionSubmissionFailure.INTERNAL_FAILURE)
        }
        val outcome = try {
            write(command, context, finalCore)
        } catch (_: Exception) {
            return failed(context.eventId, MapInteractionSubmissionFailure.PERSISTENCE_FAILURE)
        }
        val mapped = outcomeMapper.mapWriteOutcome(outcome)
        if (outcome !is LocalMapWriteOutcome.AcceptedNew || mapped !is MapInteractionSubmissionResult.Accepted) {
            return mapped
        }
        val transport = try {
            postCommitTransport.dispatch(command.target.groupId, outcome, finalCore)
        } catch (_: Exception) {
            MapInteractionPostCommitResult.EnqueueFailed
        }
        return outcomeMapper.mapAcceptedDelivery(mapped, transport)
    }

    private fun write(
        command: MapInteractionCommand,
        context: MapEventConstructionContext,
        coreWithKeyContext: app.peermesh.core.model.EventCore,
    ): LocalMapWriteOutcome = localWriter.write(
        LocalMapWriteRequest(
            gid = command.target.groupId,
            core = coreWithKeyContext,
            localActorDid = context.actorDid,
            insertedAtSeconds = context.insertedAtSeconds,
        ),
    )

    private fun isStructurallyValid(core: app.peermesh.core.model.EventCore): Boolean =
        EventValidator.validate(core)

    private fun MapEventConstructionContext.isValid(): Boolean =
        actorDid.isNotBlank() && eventId.isNotBlank() && createdSeconds > 0L &&
            lamport >= 0L && insertedAtSeconds > 0L

    private fun failed(
        eventId: String?,
        reason: MapInteractionSubmissionFailure,
    ) = MapInteractionSubmissionResult.Failed(eventId, reason)
}
