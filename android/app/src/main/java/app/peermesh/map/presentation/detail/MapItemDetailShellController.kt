package app.peermesh.map.presentation.detail

import app.peermesh.map.application.detail.LoadMapItemDetail
import app.peermesh.map.application.detail.MapItemDetailLoadResult
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.interaction.EvaluateMapItemInteractions
import app.peermesh.map.application.interaction.MapInteractionType
import app.peermesh.map.application.submission.SubmitMapInteraction
import app.peermesh.map.application.submission.AcknowledgeCoordinationCommand
import app.peermesh.map.application.submission.AddCoordinationNoteCommand
import app.peermesh.map.application.submission.AddSituationUpdateCommand
import app.peermesh.map.application.submission.ConfirmSituationCommand
import app.peermesh.map.application.submission.MapInteractionSubmissionFailure
import app.peermesh.map.application.submission.MapInteractionSubmissionResult
import app.peermesh.map.application.submission.MapInteractionInputFailure
import app.peermesh.map.application.submission.ResolveSituationCommand

/**
 * Lifecycle owner for one exact item identity.
 *
 * Implementations must perform synchronous application calls off the main thread, allow at most
 * one in-flight submission, and reload persisted detail instead of mutating aggregates/lifecycle.
 */
interface MapItemDetailShellController {
    val currentState: MapItemDetailShellState

    fun load()

    fun refresh()

    fun openInteraction(interaction: MapInteractionType)

    fun updateForm(form: MapItemInteractionForm)

    fun submitActiveForm()

    fun dismissForm()

    fun consumeFeedback()

    fun close()
}

/** Callback-based observation keeps the skeleton independent from a particular Android UI stack. */
fun interface MapItemDetailShellStateObserver {
    fun onStateChanged(state: MapItemDetailShellState)
}

/** M3A4 orchestration over the accepted M3A1, M3A2 and M3A3 boundaries. */
class DefaultMapItemDetailShellController(
    private val identity: MapItemIdentity,
    private val loadMapItemDetail: LoadMapItemDetail,
    private val evaluateMapItemInteractions: EvaluateMapItemInteractions,
    private val submitMapInteraction: SubmitMapInteraction,
    private val execution: MapItemDetailShellExecution,
    private val observer: MapItemDetailShellStateObserver,
) : MapItemDetailShellController {
    private val lock = Any()
    private var state = MapItemDetailShellState(identity)
    private var closed = false
    private var loadGeneration = 0L
    private var readTask: MapItemDetailShellTask? = null

    override val currentState: MapItemDetailShellState
        get() = synchronized(lock) { state }

    override fun load() = startLoad(showInitialLoading = true)

    override fun refresh() = startLoad(showInitialLoading = false)

    override fun openInteraction(interaction: MapInteractionType) {
        val next = synchronized(lock) {
            if (closed || state.isSubmissionInFlight || !isAvailable(interaction)) return
            state.copy(activeForm = emptyForm(interaction), feedback = null).also { state = it }
        }
        notify(next)
    }

    override fun updateForm(form: MapItemInteractionForm) {
        val next = synchronized(lock) {
            if (closed || state.isSubmissionInFlight || state.activeForm?.interaction != form.interaction) return
            state.copy(activeForm = form, feedback = null).also { state = it }
        }
        notify(next)
    }

    override fun submitActiveForm() {
        val form: MapItemInteractionForm
        val command: app.peermesh.map.application.submission.MapInteractionCommand
        val started: MapItemDetailShellState
        synchronized(lock) {
            if (closed || state.isSubmissionInFlight) return
            form = state.activeForm ?: return
            if (!isAvailable(form.interaction)) {
                state = state.copy(feedback = MapItemDetailShellFeedback.InteractionUnavailable)
                notify(state)
                return
            }
            command = form.toCommandOrNull(identity) ?: run {
                state = state.copy(
                    feedback = MapItemDetailShellFeedback.InvalidInput(
                        MapInteractionInputFailure.INVALID_COMMAND_SHAPE,
                    ),
                )
                notify(state)
                return
            }
            // A submission owns the next persisted presentation. Any older read may have observed
            // pre-submission truth, so it must not be allowed to publish after this point.
            loadGeneration++
            readTask?.cancel()
            readTask = null
            started = state.copy(submittingInteraction = form.interaction, feedback = null)
            state = started
        }
        notify(started)
        execution.execute {
            val result = try {
                submitMapInteraction.submit(command)
            } catch (_: Exception) {
                MapInteractionSubmissionResult.Failed(
                    eventId = null,
                    reason = MapInteractionSubmissionFailure.INTERNAL_FAILURE,
                )
            }
            val refreshed = if (result.refreshRecommended) safeLoadAndEvaluate() else null
            execution.deliver {
                val next = synchronized(lock) {
                    if (closed) return@deliver
                    var value = state.copy(
                        submittingInteraction = null,
                        activeForm = if (result.clearsActiveForm()) null else state.activeForm,
                        feedback = result.toFeedback(),
                    )
                    if (refreshed != null) {
                        value = value.copy(content = refreshed.first, availability = refreshed.second)
                    }
                    state = value
                    value
                }
                notify(next)
            }
        }
    }

    override fun dismissForm() {
        val next = synchronized(lock) {
            if (closed || state.isSubmissionInFlight) return
            state.copy(activeForm = null).also { state = it }
        }
        notify(next)
    }

    override fun consumeFeedback() {
        val next = synchronized(lock) {
            if (closed || state.feedback == null) return
            state.copy(feedback = null).also { state = it }
        }
        notify(next)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            loadGeneration++
            readTask?.cancel()
            readTask = null
        }
        // A write already started is deliberately not cancelled: cancellation cannot imply rollback.
    }

    private fun startLoad(showInitialLoading: Boolean) {
        val generation: Long
        val loading: MapItemDetailShellState?
        synchronized(lock) {
            if (closed || state.isSubmissionInFlight) return
            readTask?.cancel()
            generation = ++loadGeneration
            loading = if (showInitialLoading) {
                state.copy(content = MapItemDetailShellContent.InitialLoading, availability = null).also { state = it }
            } else null
        }
        loading?.let(::notify)
        val task = execution.execute {
            val loaded = safeLoadAndEvaluate()
            execution.deliver {
                val next = synchronized(lock) {
                    if (closed || generation != loadGeneration) return@deliver
                    state.copy(content = loaded.first, availability = loaded.second).also { state = it }
                }
                notify(next)
            }
        }
        synchronized(lock) {
            if (closed || generation != loadGeneration) task.cancel() else readTask = task
        }
    }

    private fun safeLoadAndEvaluate(): Pair<MapItemDetailShellContent, app.peermesh.map.application.interaction.MapItemInteractionsAvailability?> {
        val result = try {
            loadMapItemDetail.load(identity)
        } catch (_: Exception) {
            MapItemDetailLoadResult.InternalReadFailure
        }
        val availability = try {
            evaluateMapItemInteractions.evaluate(result)
        } catch (_: Exception) {
            return MapItemDetailShellContent.ReadFailure to null
        }
        return result.toContent() to availability
    }

    private fun MapItemDetailLoadResult.toContent(): MapItemDetailShellContent = when (this) {
        is MapItemDetailLoadResult.Found -> MapItemDetailShellContent.Loaded(detail, DomainEnrichmentState.AVAILABLE)
        is MapItemDetailLoadResult.CanonicalOnly -> MapItemDetailShellContent.Loaded(detail, DomainEnrichmentState.MISSING)
        is MapItemDetailLoadResult.UnresolvedLegacy -> MapItemDetailShellContent.Loaded(detail, DomainEnrichmentState.UNRESOLVED_LEGACY)
        MapItemDetailLoadResult.CanonicalItemMissing -> MapItemDetailShellContent.TargetUnavailable
        MapItemDetailLoadResult.InvalidCanonicalPersistedState,
        is MapItemDetailLoadResult.InvalidIdentity,
        -> MapItemDetailShellContent.InvalidItemData
        is MapItemDetailLoadResult.InvalidDomainPersistedState -> MapItemDetailShellContent.InvalidDomainEnrichment(canonical)
        MapItemDetailLoadResult.InternalReadFailure -> MapItemDetailShellContent.ReadFailure
    }

    private fun isAvailable(interaction: MapInteractionType): Boolean =
        state.availability?.interactions?.singleOrNull { it.interaction == interaction }
            ?.let { it.isVisible && it.isEnabled } == true

    private fun emptyForm(interaction: MapInteractionType): MapItemInteractionForm = when (interaction) {
        MapInteractionType.Situation.Confirm -> MapItemInteractionForm.ConfirmSituation()
        MapInteractionType.Situation.AddUpdate -> MapItemInteractionForm.AddSituationUpdate()
        MapInteractionType.Situation.MarkNoLongerActive -> MapItemInteractionForm.ResolveSituation()
        MapInteractionType.Coordination.Acknowledge -> MapItemInteractionForm.AcknowledgeCoordination
        MapInteractionType.Coordination.AddNote -> MapItemInteractionForm.AddCoordinationNote()
    }

    private fun MapItemInteractionForm.toCommandOrNull(
        identity: MapItemIdentity,
    ): app.peermesh.map.application.submission.MapInteractionCommand? = when (this) {
        is MapItemInteractionForm.ConfirmSituation -> ConfirmSituationCommand(identity, informationSource, note.trim().ifBlank { null })
        is MapItemInteractionForm.AddSituationUpdate -> disposition?.let {
            AddSituationUpdateCommand(identity, content.trim(), it, informationSource)
        }
        is MapItemInteractionForm.ResolveSituation -> ResolveSituationCommand(identity, note.trim().ifBlank { null }, informationSource)
        MapItemInteractionForm.AcknowledgeCoordination -> AcknowledgeCoordinationCommand(identity)
        is MapItemInteractionForm.AddCoordinationNote -> AddCoordinationNoteCommand(identity, content.trim())
    }

    private fun MapInteractionSubmissionResult.toFeedback(): MapItemDetailShellFeedback = when (this) {
        is MapInteractionSubmissionResult.Accepted -> MapItemDetailShellFeedback.Accepted(deliveryWarning)
        is MapInteractionSubmissionResult.AlreadyRecorded -> MapItemDetailShellFeedback.AlreadyRecorded
        is MapInteractionSubmissionResult.Rejected -> MapItemDetailShellFeedback.Rejected(reason)
        is MapInteractionSubmissionResult.InvalidInput -> MapItemDetailShellFeedback.InvalidInput(failure)
        is MapInteractionSubmissionResult.Failed -> MapItemDetailShellFeedback.Failed(reason)
    }

    private fun MapInteractionSubmissionResult.clearsActiveForm(): Boolean = when (this) {
        is MapInteractionSubmissionResult.Accepted,
        is MapInteractionSubmissionResult.AlreadyRecorded,
        -> true
        is MapInteractionSubmissionResult.Rejected -> refreshRecommended
        is MapInteractionSubmissionResult.InvalidInput,
        is MapInteractionSubmissionResult.Failed,
        -> false
    }

    private fun notify(value: MapItemDetailShellState) = execution.deliver {
        if (!synchronized(lock) { closed }) observer.onStateChanged(value)
    }
}

/**
 * Explicit execution seam for blocking one-shot reads/submissions and main-thread state delivery.
 * Cancellation must suppress obsolete UI delivery, not imply rollback of an already-started write.
 */
interface MapItemDetailShellExecution {
    fun execute(block: () -> Unit): MapItemDetailShellTask

    fun deliver(block: () -> Unit)
}

fun interface MapItemDetailShellTask {
    fun cancel()
}
