package app.peermesh.map.presentation.detail

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.map.MapItemWindows
import app.peermesh.map.application.detail.AreaCanonicalPresentation
import app.peermesh.map.application.detail.CanonicalOnlyReason
import app.peermesh.map.application.detail.InvalidIdentityReason
import app.peermesh.map.application.detail.LoadMapItemDetail
import app.peermesh.map.application.detail.MapAreaGeometrySummary
import app.peermesh.map.application.detail.MapCoordinatesPresentation
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemDetailLoadResult
import app.peermesh.map.application.detail.MapItemDomainPresentation
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.detail.MarkerCanonicalPresentation
import app.peermesh.map.application.interaction.EvaluateMapItemInteractions
import app.peermesh.map.application.interaction.MapInteractionAvailabilityReason
import app.peermesh.map.application.interaction.MapInteractionAvailabilityState
import app.peermesh.map.application.interaction.MapInteractionCapabilityWarning
import app.peermesh.map.application.interaction.MapInteractionType
import app.peermesh.map.application.interaction.MapItemInteractionAvailability
import app.peermesh.map.application.interaction.MapItemInteractionsAvailability
import app.peermesh.map.application.submission.AcknowledgeCoordinationCommand
import app.peermesh.map.application.submission.AddCoordinationNoteCommand
import app.peermesh.map.application.submission.AddSituationUpdateCommand
import app.peermesh.map.application.submission.ConfirmSituationCommand
import app.peermesh.map.application.submission.MapInteractionCommand
import app.peermesh.map.application.submission.MapInteractionDeliveryWarning
import app.peermesh.map.application.submission.MapInteractionRejection
import app.peermesh.map.application.submission.MapInteractionSubmissionResult
import app.peermesh.map.application.submission.ResolveSituationCommand
import app.peermesh.map.application.submission.SubmitMapInteraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMapItemDetailShellControllerM3A4Test {
    @Test fun exactMarkerAndAreaIdentitiesIncludingSameRawIdRemainIsolated() {
        val seen = mutableListOf<MapItemIdentity>()
        listOf(MARKER, AREA).forEach { identity ->
            fixture(identity = identity, loader = LoadMapItemDetail { requested ->
                seen += requested
                MapItemDetailLoadResult.Found(detail(requested))
            }).controller.load()
        }
        assertEquals(listOf(MARKER, AREA), seen)
        assertEquals(MARKER.itemId, AREA.itemId)
        assertTrue(MARKER.itemType != AREA.itemType)
    }

    @Test fun loadMapsEveryM3A1ResultAndPassesExactResultToM3A2() {
        val canonical = markerCanonical(MARKER)
        val found = MapItemDetailLoadResult.Found(detail(MARKER))
        val cases = listOf(
            found to MapItemDetailShellContent.Loaded::class.java,
            MapItemDetailLoadResult.CanonicalOnly(detail(MARKER, domain = null), CanonicalOnlyReason.DOMAIN_STATE_MISSING) to
                MapItemDetailShellContent.Loaded::class.java,
            MapItemDetailLoadResult.UnresolvedLegacy(detail(MARKER, domain = unresolvedDomain())) to
                MapItemDetailShellContent.Loaded::class.java,
            MapItemDetailLoadResult.CanonicalItemMissing to MapItemDetailShellContent.TargetUnavailable::class.java,
            MapItemDetailLoadResult.InvalidCanonicalPersistedState to MapItemDetailShellContent.InvalidItemData::class.java,
            MapItemDetailLoadResult.InvalidDomainPersistedState(canonical) to
                MapItemDetailShellContent.InvalidDomainEnrichment::class.java,
            MapItemDetailLoadResult.InvalidIdentity(InvalidIdentityReason.BLANK_ITEM_ID) to
                MapItemDetailShellContent.InvalidItemData::class.java,
            MapItemDetailLoadResult.InternalReadFailure to MapItemDetailShellContent.ReadFailure::class.java,
        )
        cases.forEach { (result, expected) ->
            var evaluated: MapItemDetailLoadResult? = null
            val fx = fixture(
                loader = LoadMapItemDetail { result },
                evaluator = EvaluateMapItemInteractions { input -> evaluated = input; enabledAvailability(MARKER) },
            )
            fx.controller.load()
            assertEquals(expected, fx.controller.currentState.content::class.java)
            assertSame(result, evaluated)
        }
        val invalid = fixture(loader = LoadMapItemDetail {
            MapItemDetailLoadResult.InvalidDomainPersistedState(canonical)
        }).also { it.controller.load() }.controller.currentState.content
        assertTrue(invalid is MapItemDetailShellContent.InvalidDomainEnrichment)
        assertFalse(invalid is MapItemDetailShellContent.Loaded)
    }

    @Test fun evaluatorAvailabilityIsForwardedWithoutControllerPolicyDuplication() {
        val custom = MapItemInteractionsAvailability(MARKER, listOf(
            MapItemInteractionAvailability(
                MapInteractionType.Situation.Confirm,
                MapInteractionAvailabilityState.COMPATIBILITY_LIMITED,
                capabilityWarning = MapInteractionCapabilityWarning.PEER_SUPPORT_UNKNOWN,
            ),
            MapItemInteractionAvailability(
                MapInteractionType.Situation.AddUpdate,
                MapInteractionAvailabilityState.DISABLED,
                MapInteractionAvailabilityReason.TARGET_EXPIRED,
            ),
        ))
        val fx = fixture(evaluator = EvaluateMapItemInteractions { custom })
        fx.controller.load()
        assertSame(custom, fx.controller.currentState.availability)
        fx.controller.openInteraction(MapInteractionType.Situation.AddUpdate)
        assertNull(fx.controller.currentState.activeForm)
        fx.controller.openInteraction(MapInteractionType.Situation.Confirm)
        assertTrue(fx.controller.currentState.activeForm is MapItemInteractionForm.ConfirmSituation)
    }

    @Test fun semanticAvailabilityModesRemainExactControllerState() {
        val cases = listOf(
            MapInteractionAvailabilityState.ENABLED to null,
            MapInteractionAvailabilityState.DISABLED to MapInteractionAvailabilityReason.SITUATION_RESOLVED,
            MapInteractionAvailabilityState.HIDDEN to MapInteractionAvailabilityReason.ACTION_INCOMPATIBLE_WITH_CATEGORY,
            MapInteractionAvailabilityState.UNAVAILABLE_UNRESOLVED to MapInteractionAvailabilityReason.CATEGORY_UNRESOLVED,
            MapInteractionAvailabilityState.DISABLED to MapInteractionAvailabilityReason.TARGET_EXPIRED,
            MapInteractionAvailabilityState.DISABLED to MapInteractionAvailabilityReason.INTERACTION_WINDOW_CLOSED,
            MapInteractionAvailabilityState.DISABLED to MapInteractionAvailabilityReason.LOCAL_ACTOR_NOT_MEMBER,
            MapInteractionAvailabilityState.DISABLED to MapInteractionAvailabilityReason.LOCAL_MEMBERSHIP_UNKNOWN,
        )
        cases.forEach { (state, reason) ->
            val expected = MapItemInteractionsAvailability(MARKER, listOf(
                MapItemInteractionAvailability(MapInteractionType.Situation.Confirm, state, reason),
            ))
            val fx = fixture(evaluator = EvaluateMapItemInteractions { expected })
            fx.controller.load()
            assertSame(expected, fx.controller.currentState.availability)
        }
        val capability = enabledAvailability(MARKER).copy(interactions = listOf(
            MapItemInteractionAvailability(
                MapInteractionType.Coordination.Acknowledge,
                MapInteractionAvailabilityState.COMPATIBILITY_LIMITED,
                capabilityWarning = MapInteractionCapabilityWarning.PEER_SUPPORT_UNKNOWN,
            ),
        ))
        val fx = fixture(evaluator = EvaluateMapItemInteractions { capability })
        fx.controller.load()
        assertSame(capability, fx.controller.currentState.availability)
        assertTrue(fx.controller.currentState.availability!!.interactions.single().isEnabled)
    }

    @Test fun allFiveFormsMapToExactTypedCommandsAndBlankOptionalTextNormalizes() {
        val submitted = mutableListOf<MapInteractionCommand>()
        val fx = fixture(submitter = SubmitMapInteraction {
            submitted += it
            MapInteractionSubmissionResult.Failed(null, app.peermesh.map.application.submission.MapInteractionSubmissionFailure.INTERNAL_FAILURE)
        })
        fx.controller.load()
        submit(fx.controller, MapInteractionType.Situation.Confirm,
            MapItemInteractionForm.ConfirmSituation(MapInformationSource.FIRSTHAND, "   "))
        submit(fx.controller, MapInteractionType.Situation.AddUpdate,
            MapItemInteractionForm.AddSituationUpdate(" changed ", MapSituationUpdateType.SITUATION_CHANGED,
                MapInformationSource.REPORTED_BY_OTHERS))
        submit(fx.controller, MapInteractionType.Situation.MarkNoLongerActive,
            MapItemInteractionForm.ResolveSituation("  ", MapInformationSource.UNCERTAIN))
        submit(fx.controller, MapInteractionType.Coordination.Acknowledge,
            MapItemInteractionForm.AcknowledgeCoordination)
        submit(fx.controller, MapInteractionType.Coordination.AddNote,
            MapItemInteractionForm.AddCoordinationNote(" coordinate here "))

        assertEquals(5, submitted.size)
        assertEquals(ConfirmSituationCommand(MARKER, MapInformationSource.FIRSTHAND, null), submitted[0])
        assertEquals(AddSituationUpdateCommand(MARKER, "changed", MapSituationUpdateType.SITUATION_CHANGED,
            MapInformationSource.REPORTED_BY_OTHERS), submitted[1])
        assertEquals(ResolveSituationCommand(MARKER, null, MapInformationSource.UNCERTAIN), submitted[2])
        assertEquals(AcknowledgeCoordinationCommand(MARKER), submitted[3])
        assertEquals(AddCoordinationNoteCommand(MARKER, "coordinate here"), submitted[4])
    }

    @Test fun missingRequiredUpdateTypeIsLocalFeedbackAndNeverSubmitted() {
        var submissions = 0
        val fx = fixture(submitter = SubmitMapInteraction { submissions++; accepted() })
        fx.controller.load()
        fx.controller.openInteraction(MapInteractionType.Situation.AddUpdate)
        fx.controller.updateForm(MapItemInteractionForm.AddSituationUpdate("content", null))
        fx.controller.submitActiveForm()
        assertEquals(0, submissions)
        assertTrue(fx.controller.currentState.feedback is MapItemDetailShellFeedback.InvalidInput)
        assertFalse(fx.controller.currentState.isSubmissionInFlight)
    }

    @Test fun oneInFlightSubmissionIsGuardedAndStateIsNotOptimisticallyMutated() {
        val execution = QueuedExecution()
        var submissions = 0
        val fx = fixture(execution = execution, submitter = SubmitMapInteraction { submissions++; accepted() })
        fx.controller.load()
        execution.runNext()
        val before = fx.controller.currentState.content
        fx.controller.openInteraction(MapInteractionType.Situation.Confirm)
        fx.controller.submitActiveForm()
        fx.controller.submitActiveForm()
        assertTrue(fx.controller.currentState.isSubmissionInFlight)
        assertSame(before, fx.controller.currentState.content)
        assertEquals(0, submissions)
        execution.runNext()
        assertEquals(1, submissions)
        assertFalse(fx.controller.currentState.isSubmissionInFlight)
    }

    @Test fun acceptedAndAcceptedDeliveryWarningPreserveSuccessAndRefreshPersistedTruth() {
        listOf<MapInteractionSubmissionResult>(
            accepted(),
            MapInteractionSubmissionResult.Accepted("e", MapInteractionDeliveryWarning.ENQUEUE_FAILED),
        ).forEach { outcome ->
            var loads = 0
            val fx = fixture(
                loader = LoadMapItemDetail { loads++; MapItemDetailLoadResult.Found(detail(MARKER, confirmations = loads)) },
                submitter = SubmitMapInteraction { outcome },
            )
            fx.controller.load()
            submit(fx.controller, MapInteractionType.Situation.Confirm, MapItemInteractionForm.ConfirmSituation())
            assertEquals(2, loads)
            val feedback = fx.controller.currentState.feedback as MapItemDetailShellFeedback.Accepted
            assertEquals((outcome as MapInteractionSubmissionResult.Accepted).deliveryWarning, feedback.deliveryWarning)
            assertEquals(2, loadedDetail(fx).domain!!.confirmationActorCount)
        }
    }

    @Test fun duplicateAndStaleRejectionRefreshOnlyWhenRecommendedWithoutSecondEffect() {
        val outcomes = listOf(
            MapInteractionSubmissionResult.AlreadyRecorded("e", refreshRecommended = true) to true,
            MapInteractionSubmissionResult.Rejected("e", MapInteractionRejection.TARGET_RESOLVED, true) to true,
            MapInteractionSubmissionResult.Rejected("e", MapInteractionRejection.OTHER, false) to false,
        )
        outcomes.forEach { (outcome, refreshes) ->
            var loads = 0
            var submissions = 0
            val fx = fixture(
                loader = LoadMapItemDetail { loads++; MapItemDetailLoadResult.Found(detail(MARKER)) },
                submitter = SubmitMapInteraction { submissions++; outcome },
            )
            fx.controller.load()
            submit(fx.controller, MapInteractionType.Situation.Confirm, MapItemInteractionForm.ConfirmSituation())
            assertEquals(1, submissions)
            assertEquals(if (refreshes) 2 else 1, loads)
            if (outcome is MapInteractionSubmissionResult.AlreadyRecorded) {
                assertSame(MapItemDetailShellFeedback.AlreadyRecorded, fx.controller.currentState.feedback)
            }
        }
    }

    @Test fun submissionRefreshWinsWhenOlderRefreshPublishesAfterward() {
        val execution = DelayedDeliveryExecution()
        var loads = 0
        val fx = fixture(
            execution = execution,
            loader = LoadMapItemDetail {
                loads++
                MapItemDetailLoadResult.Found(detail(MARKER, confirmations = when (loads) {
                    1 -> 1 // initial state
                    2 -> 2 // older refresh reads pre-submission truth A
                    3 -> 3 // submission refresh reads authoritative truth B
                    else -> error("unexpected load")
                }))
            },
            submitter = SubmitMapInteraction { accepted() },
        )
        fx.controller.load()
        execution.runWorker(0)

        execution.delayDeliveries = true
        fx.controller.refresh()
        execution.runWorker(0) // older refresh reads A and pauses before UI delivery

        fx.controller.openInteraction(MapInteractionType.Situation.Confirm)
        fx.controller.submitActiveForm()
        assertEquals(2, execution.cancelledTaskCount) // prior completed read, then the obsolete refresh
        execution.runWorker(0) // accepted submission refresh reads B
        execution.runDelivery(3) // publish B before the stale refresh callback
        assertEquals(3, loadedDetail(fx).domain!!.confirmationActorCount)

        execution.runDelivery(0) // stale A delivery arrives late and must be ignored
        assertEquals(3, loadedDetail(fx).domain!!.confirmationActorCount)
    }

    @Test fun refreshedSemanticRejectionClearsNowUnsupportedActiveForm() {
        var evaluations = 0
        val disabledConfirm = MapItemInteractionsAvailability(MARKER, listOf(
            MapItemInteractionAvailability(
                MapInteractionType.Situation.Confirm,
                MapInteractionAvailabilityState.DISABLED,
                MapInteractionAvailabilityReason.SITUATION_RESOLVED,
            ),
        ))
        val fx = fixture(
            evaluator = EvaluateMapItemInteractions {
                evaluations++
                if (evaluations == 1) enabledAvailability(MARKER) else disabledConfirm
            },
            submitter = SubmitMapInteraction {
                MapInteractionSubmissionResult.Rejected(
                    eventId = "e",
                    reason = MapInteractionRejection.TARGET_RESOLVED,
                    refreshRecommended = true,
                )
            },
        )
        fx.controller.load()
        fx.controller.openInteraction(MapInteractionType.Situation.Confirm)
        fx.controller.submitActiveForm()

        assertNull(fx.controller.currentState.activeForm)
        assertSame(disabledConfirm, fx.controller.currentState.availability)
        assertTrue(fx.controller.currentState.feedback is MapItemDetailShellFeedback.Rejected)
    }

    @Test fun newerLoadWinsAndCloseSuppressesObsoleteDelivery() {
        val execution = QueuedExecution()
        var loads = 0
        val fx = fixture(execution = execution, loader = LoadMapItemDetail {
            loads++
            MapItemDetailLoadResult.Found(detail(MARKER, confirmations = loads))
        })
        fx.controller.load()
        fx.controller.refresh()
        execution.runAt(0) // obsolete generation
        assertTrue(fx.controller.currentState.content is MapItemDetailShellContent.InitialLoading)
        execution.runAt(0)
        assertEquals(2, loadedDetail(fx).domain!!.confirmationActorCount)

        fx.controller.refresh()
        val stable = fx.controller.currentState
        fx.controller.close()
        execution.runAt(0)
        assertEquals(stable, fx.controller.currentState)
    }

    @Test fun ordinaryFailuresMapSafelyWhileFatalErrorsPropagate() {
        val loadFailure = fixture(loader = LoadMapItemDetail { throw IllegalStateException("read") })
        loadFailure.controller.load()
        assertTrue(loadFailure.controller.currentState.content is MapItemDetailShellContent.ReadFailure)

        val submitFailure = fixture(submitter = SubmitMapInteraction { throw IllegalStateException("write") })
        submitFailure.controller.load()
        submit(submitFailure.controller, MapInteractionType.Situation.Confirm,
            MapItemInteractionForm.ConfirmSituation())
        assertTrue(submitFailure.controller.currentState.feedback is MapItemDetailShellFeedback.Failed)

        try {
            fixture(loader = LoadMapItemDetail { throw FatalTestError() }).controller.load()
            throw AssertionError("fatal error was swallowed")
        } catch (_: FatalTestError) {
            // expected
        }
    }

    @Test fun navigationCodecContainsOnlyExactIdentityAndRejectsInvalidArguments() {
        val encoded = DefaultMapItemDetailNavigationArguments.encode(AREA)
        assertEquals(3, encoded.size)
        assertEquals(AREA, DefaultMapItemDetailNavigationArguments.decode(encoded))
        assertNull(DefaultMapItemDetailNavigationArguments.decode(encoded -
            DefaultMapItemDetailNavigationArguments.EXTRA_ITEM_TYPE))
        assertNull(DefaultMapItemDetailNavigationArguments.decode(encoded +
            (DefaultMapItemDetailNavigationArguments.EXTRA_ITEM_TYPE to "unknown")))
    }

    private fun submit(
        controller: MapItemDetailShellController,
        type: MapInteractionType,
        form: MapItemInteractionForm,
    ) {
        controller.openInteraction(type)
        controller.updateForm(form)
        controller.submitActiveForm()
    }

    private fun loadedDetail(fx: Fixture) =
        (fx.controller.currentState.content as MapItemDetailShellContent.Loaded).detail

    private fun fixture(
        identity: MapItemIdentity = MARKER,
        loader: LoadMapItemDetail = LoadMapItemDetail { MapItemDetailLoadResult.Found(detail(it)) },
        evaluator: EvaluateMapItemInteractions = EvaluateMapItemInteractions { enabledAvailability(it.identityOr(MARKER)) },
        submitter: SubmitMapInteraction = SubmitMapInteraction { accepted() },
        execution: MapItemDetailShellExecution = ImmediateExecution,
    ): Fixture {
        val states = mutableListOf<MapItemDetailShellState>()
        val controller = DefaultMapItemDetailShellController(
            identity, loader, evaluator, submitter, execution,
            MapItemDetailShellStateObserver { states += it },
        )
        return Fixture(controller, states)
    }

    private fun MapItemDetailLoadResult.identityOr(fallback: MapItemIdentity): MapItemIdentity = when (this) {
        is MapItemDetailLoadResult.Found -> detail.identity
        is MapItemDetailLoadResult.CanonicalOnly -> detail.identity
        is MapItemDetailLoadResult.UnresolvedLegacy -> detail.identity
        else -> fallback
    }

    private fun enabledAvailability(identity: MapItemIdentity) = MapItemInteractionsAvailability(
        identity,
        MapInteractionType.all.map {
            MapItemInteractionAvailability(it, MapInteractionAvailabilityState.ENABLED)
        },
    )

    private fun detail(
        identity: MapItemIdentity,
        domain: MapItemDomainPresentation? = domain(),
        confirmations: Int = domain?.confirmationActorCount ?: 0,
    ): MapItemDetail {
        val adjusted = domain?.copy(confirmationActorCount = confirmations)
        val canonical = if (identity.itemType == MapItemType.MARKER) markerCanonical(identity) else
            AreaCanonicalPresentation(identity, "risk", "Area", "owner note",
                MapItemCanonicalAvailability.ACTIVE, 2_000, MapAreaGeometrySummary.Polygon(4))
        return MapItemDetail(identity, canonical, adjusted)
    }

    private fun markerCanonical(identity: MapItemIdentity) = MarkerCanonicalPresentation(
        identity, "risk", "Marker", "owner note", MapItemCanonicalAvailability.ACTIVE, 2_000,
        MapCoordinatesPresentation(10.0, 106.0), 20.0,
    )

    private fun domain() = MapItemDomainPresentation(
        MapItemCategory.SITUATION,
        MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1,
        MapInformationSource.FIRSTHAND,
        MapSituationLifecycleStatus.ACTIVE,
        MapItemWindows(2_000, 1_500, 2_000),
        MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1,
        1,
        0,
    )

    private fun unresolvedDomain() = domain().copy(
        category = null,
        categoryOrigin = MapItemCategoryOrigin.UNRESOLVED,
        creatorInformationSource = null,
        lifecycle = null,
        temporalOrigin = MapItemTemporalOrigin.LEGACY_UNRESOLVED,
    )

    private data class Fixture(
        val controller: DefaultMapItemDetailShellController,
        val states: List<MapItemDetailShellState>,
    )

    private object ImmediateExecution : MapItemDetailShellExecution {
        override fun execute(block: () -> Unit): MapItemDetailShellTask {
            block()
            return MapItemDetailShellTask {}
        }
        override fun deliver(block: () -> Unit) = block()
    }

    private class QueuedExecution : MapItemDetailShellExecution {
        private val blocks = mutableListOf<() -> Unit>()
        override fun execute(block: () -> Unit): MapItemDetailShellTask {
            blocks += block
            return MapItemDetailShellTask { }
        }
        override fun deliver(block: () -> Unit) = block()
        fun runNext() = runAt(0)
        fun runAt(index: Int) = blocks.removeAt(index).invoke()
    }

    private class DelayedDeliveryExecution : MapItemDetailShellExecution {
        private val workers = mutableListOf<() -> Unit>()
        private val deliveries = mutableListOf<() -> Unit>()
        var delayDeliveries = false
        var cancelledTaskCount = 0

        override fun execute(block: () -> Unit): MapItemDetailShellTask {
            workers += block
            return MapItemDetailShellTask { cancelledTaskCount++ }
        }

        override fun deliver(block: () -> Unit) {
            if (delayDeliveries) deliveries += block else block()
        }

        fun runWorker(index: Int) = workers.removeAt(index).invoke()
        fun runDelivery(index: Int) = deliveries.removeAt(index).invoke()
    }

    private class FatalTestError : Error()

    companion object {
        private val MARKER = MapItemIdentity("g", MapItemType.MARKER, "same")
        private val AREA = MapItemIdentity("g", MapItemType.AREA, "same")
        private fun accepted() = MapInteractionSubmissionResult.Accepted("e")
    }
}
