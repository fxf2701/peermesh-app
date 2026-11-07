package app.peermesh.map.presentation.detail

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.map.MapItemWindows
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis
import app.peermesh.map.application.detail.MapCoordinatesPresentation
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemDetailLoadResult
import app.peermesh.map.application.detail.MapItemDomainPresentation
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.detail.MarkerCanonicalPresentation
import app.peermesh.map.application.interaction.EvaluateMapItemInteractions
import app.peermesh.map.application.interaction.MapInteractionAvailabilityState
import app.peermesh.map.application.interaction.MapInteractionType
import app.peermesh.map.application.interaction.MapItemInteractionAvailability
import app.peermesh.map.application.interaction.MapItemInteractionsAvailability
import app.peermesh.map.application.submission.MapInteractionCommand
import app.peermesh.map.application.submission.MapInteractionSubmissionResult
import app.peermesh.map.application.submission.SubmitMapInteraction
import app.peermesh.map.application.submission.ConfirmSituationCommand
import app.peermesh.map.composition.MapItemDetailShellDependencies
import app.peermesh.map.presentation.timeline.MapItemTimelineAppendState
import app.peermesh.map.presentation.timeline.MapItemTimelineContent
import app.peermesh.map.presentation.timeline.MapItemTimelineController
import app.peermesh.map.presentation.timeline.MapItemTimelineRefreshState
import app.peermesh.map.presentation.timeline.MapItemTimelineState
import app.peermesh.map.presentation.timeline.MapItemTimelineStateObserver

/** Debug-only Android instrumentation harness using accepted detail and timeline boundaries. */
class AcceptedConfirmRuntimeActivity : MapItemDetailActivity() {
    val trace = Trace()

    override fun createDetailDependencies() = MapItemDetailShellDependencies(
        loadMapItemDetail = { identity ->
            trace.detailLoads++
            trace.persistedIdentity = identity
            MapItemDetailLoadResult.Found(detail(identity, trace.persistedVerification))
        },
        evaluateMapItemInteractions = EvaluateMapItemInteractions { result ->
            MapItemInteractionsAvailability(
                (result as MapItemDetailLoadResult.Found).detail.identity,
                listOf(MapItemInteractionAvailability(MapInteractionType.Situation.Confirm, MapInteractionAvailabilityState.ENABLED)),
            )
        },
        submitMapInteraction = SubmitMapInteraction { command ->
            trace.submittedCommands += command
            check(command is ConfirmSituationCommand) {
                "Expected ConfirmSituationCommand, received ${command::class.simpleName}"
            }
            check(command.target == trace.persistedIdentity) {
                "Confirm target does not match loaded identity"
            }
            trace.persistedVerification = PersistedVerification(
                confirmationActorCount = 1,
                freshnessBasis = MapSituationConfirmationFreshnessBasis.Current(
                    latestCurrentConfirmationAtSeconds = null,
                ),
            )
            trace.acceptedSubmissionCount++
            MapInteractionSubmissionResult.Accepted("accepted-confirm")
        },
    )

    override fun createDetailExecution(): MapItemDetailShellExecution = ImmediateExecution

    override fun createTimelineController(
        identity: MapItemIdentity,
        observer: MapItemTimelineStateObserver,
    ): MapItemTimelineController = RecordingTimelineController(identity, observer, trace)

    private fun detail(identity: MapItemIdentity, verification: PersistedVerification) = MapItemDetail(
        identity,
        MarkerCanonicalPresentation(identity, "risk", "Runtime marker", null, MapItemCanonicalAvailability.ACTIVE, null,
            MapCoordinatesPresentation(10.0, 106.0), null),
        MapItemDomainPresentation(MapItemCategory.SITUATION, MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1,
            MapInformationSource.FIRSTHAND, MapSituationLifecycleStatus.ACTIVE, MapItemWindows(2_000, 1_500, 2_000),
            MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1, verification.confirmationActorCount, 0,
            verification.freshnessBasis),
    )

    data class PersistedVerification(
        val confirmationActorCount: Int,
        val freshnessBasis: MapSituationConfirmationFreshnessBasis,
    )

    class Trace {
        var detailLoads = 0
        var persistedVerification = PersistedVerification(
            confirmationActorCount = 0,
            freshnessBasis = MapSituationConfirmationFreshnessBasis.NoAcceptedPriorConfirmationEstablished,
        )
        var persistedIdentity: MapItemIdentity? = null
        var timelineInitialLoads = 0
        var timelineFirstPageRefreshes = 0
        var acceptedSubmissionCount = 0
        val submittedCommands = mutableListOf<MapInteractionCommand>()
    }

    private object ImmediateExecution : MapItemDetailShellExecution {
        override fun execute(block: () -> Unit): MapItemDetailShellTask { block(); return MapItemDetailShellTask {} }
        override fun deliver(block: () -> Unit) = block()
    }

    private class RecordingTimelineController(
        private val identity: MapItemIdentity,
        private val observer: MapItemTimelineStateObserver,
        private val trace: Trace,
    ) : MapItemTimelineController {
        override var currentState = state()
            private set
        override fun loadInitial() { trace.timelineInitialLoads++; publish() }
        override fun refreshFirstPage() { trace.timelineFirstPageRefreshes++; publish() }
        override fun loadOlder() = Unit
        override fun close() = Unit
        private fun publish() { currentState = state(); observer.onStateChanged(currentState) }
        private fun state() = MapItemTimelineState(identity, MapItemTimelineContent.Available(emptyList(), true),
            MapItemTimelineRefreshState.Idle, MapItemTimelineAppendState.Idle)
    }
}
