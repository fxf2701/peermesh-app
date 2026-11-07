package app.peermesh.map.application.rendering

import app.peermesh.core.map.MapItemType
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.scene.LoadMapSceneItems
import app.peermesh.map.application.scene.MapSceneGeometry
import app.peermesh.map.application.scene.MapSceneItem
import app.peermesh.map.application.scene.MapSceneItemLoadResult
import app.peermesh.map.application.scene.MapSceneLoadFailure
import app.peermesh.map.application.scene.MapSceneLoadResult
import app.peermesh.map.application.scene.MapSceneTemporalPresentation
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult
import app.peermesh.map.application.verification.MapVerificationUnavailableReason
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultGroupMapControllerM3D2Test {
    @Test fun successfulLoadPublishesOnPresentationExecutorInDeterministicExactIdentityOrder() {
        val area = id("same", MapItemType.AREA)
        val marker = id("same")
        val fixture = Fixture { _, _ ->
            MapSceneLoadResult.Success(listOf(found(area), found(marker)))
        }
        val states = mutableListOf<GroupMapState>()
        fixture.controller.addListener { states += it }

        fixture.controller.refresh(request(setOf(area, marker)))
        assertTrue(states.isEmpty())
        fixture.presentation.runNext()
        assertTrue(states.last().operation is GroupMapOperationState.Loading)
        fixture.background.runNext()
        fixture.presentation.runNext()

        val accepted = fixture.controller.currentState()
        val snapshot = checkNotNull(accepted.renderSnapshot)
        assertEquals(listOf(area, marker), snapshot.items.map { it.identity })
        assertEquals(2, snapshot.items.size)
        assertEquals(1, fixture.loads)
    }

    @Test fun duplicateSceneResultIsTypedBuildFailureAndRetainsPreviousSnapshot() {
        var results = listOf(found(id("same")))
        val fixture = Fixture { _, _ -> MapSceneLoadResult.Success(results) }
        fixture.refreshAndDrain(setOf(id("same")))
        val accepted = checkNotNull(fixture.controller.currentState().renderSnapshot)

        results = listOf(found(id("same")), found(id("same")))
        fixture.refreshAndDrain(setOf(id("same")))

        val state = fixture.controller.currentState()
        assertEquals(accepted, state.renderSnapshot)
        val failed = state.operation as GroupMapOperationState.Failed
        assertEquals(
            GroupMapFailure.ExecutionFailure(GroupMapOperation.BUILD_RENDER_SNAPSHOT),
            failed.failure,
        )
        assertTrue(failed.retainedContent)
    }

    @Test fun initialFailureIsEmptyAndLaterFailureRetainsAcceptedSnapshot() {
        var result: MapSceneLoadResult = MapSceneLoadResult.InternalFailure(
            MapSceneLoadFailure.CanonicalReadUnavailable,
        )
        val fixture = Fixture { _, _ -> result }
        fixture.refreshAndDrain()
        var state = fixture.controller.currentState()
        assertEquals(GroupMapContentState.Empty, state.content)
        assertFalse((state.operation as GroupMapOperationState.Failed).retainedContent)

        result = MapSceneLoadResult.Success(listOf(found(id("a"))))
        fixture.refreshAndDrain()
        val accepted = fixture.controller.currentState().renderSnapshot
        result = MapSceneLoadResult.InternalFailure(MapSceneLoadFailure.ClockUnavailable)
        fixture.controller.refresh(request(setOf(id("a"))))
        fixture.presentation.runNext()
        assertEquals(accepted, fixture.controller.currentState().renderSnapshot)
        fixture.background.runNext()
        fixture.presentation.runNext()
        state = fixture.controller.currentState()
        assertEquals(accepted, state.renderSnapshot)
        assertTrue((state.operation as GroupMapOperationState.Failed).retainedContent)
    }

    @Test fun newerRequestWinsAndLateSuccessOrFailureIsIgnored() {
        val fixture = Fixture { _, identities ->
            MapSceneLoadResult.Success(listOf(found(identities.single())))
        }
        fixture.controller.refresh(request(setOf(id("old"))))
        fixture.controller.refresh(request(setOf(id("new"))))
        fixture.presentation.runAll()
        fixture.background.runLast()
        fixture.presentation.runAll()
        fixture.background.runNext()
        fixture.presentation.runAll()
        assertEquals(
            listOf(id("new")),
            fixture.controller.currentState().renderSnapshot!!.items.map { it.identity },
        )
    }

    @Test fun selectionAndFocusRebuildWithoutReloadAndPersistOnlyForRenderableExactIdentity() {
        var result = MapSceneLoadResult.Success(listOf(found(id("x")), found(id("x", MapItemType.AREA))))
        val fixture = Fixture { _, _ -> result }
        fixture.refreshAndDrain(setOf(id("x"), id("x", MapItemType.AREA)))
        val loads = fixture.loads
        fixture.controller.select(id("x"))
        fixture.controller.focus(id("x"))
        fixture.presentation.runAll()
        assertEquals(loads, fixture.loads)
        assertEquals(id("x"), fixture.controller.currentState().selection.selectedIdentity)
        assertEquals(
            MapItemEmphasis.SELECTED_AND_FOCUSED,
            fixture.controller.currentState().renderSnapshot!!.items
                .single { it.identity == id("x") }.visual.emphasis,
        )
        assertEquals(
            MapItemEmphasis.NORMAL,
            fixture.controller.currentState().renderSnapshot!!.items
                .single { it.identity.itemType == MapItemType.AREA }.visual.emphasis,
        )

        fixture.refreshAndDrain(setOf(id("x"), id("x", MapItemType.AREA)))
        assertEquals(id("x"), fixture.controller.currentState().selection.selectedIdentity)
        result = MapSceneLoadResult.Success(
            listOf(MapSceneItemLoadResult.InvalidDomain(id("x")), found(id("x", MapItemType.AREA))),
        )
        fixture.refreshAndDrain(setOf(id("x"), id("x", MapItemType.AREA)))
        assertNull(fixture.controller.currentState().selection.selectedIdentity)
        assertNull(fixture.controller.currentState().selection.focusedIdentity)
    }

    @Test fun partialFailuresKeepDiagnosticsAndUnrelatedRenderableItems() {
        val valid = id("valid")
        val missing = id("missing")
        val invalid = id("invalid")
        val fixture = Fixture { _, _ ->
            MapSceneLoadResult.Success(
                listOf(
                    found(valid),
                    MapSceneItemLoadResult.Missing(missing),
                    MapSceneItemLoadResult.InvalidCanonical(invalid),
                ),
            )
        }
        fixture.refreshAndDrain(setOf(valid, missing, invalid))
        val content = fixture.controller.currentState().content as GroupMapContentState.Accepted
        assertEquals(MapSceneItemOutcome.MISSING, content.itemOutcomes[missing])
        assertEquals(MapSceneItemOutcome.INVALID_CANONICAL, content.itemOutcomes[invalid])
        assertEquals(listOf(valid), fixture.controller.currentState().renderSnapshot!!.items.map { it.identity })
    }

    @Test fun duplicateListenersRemovalCloseAndLateCallbacksAreSafe() {
        val fixture = Fixture { _, _ -> MapSceneLoadResult.Success(listOf(found(id("x")))) }
        var calls = 0
        val listener = GroupMapStateListener { calls++ }
        fixture.controller.addListener(listener)
        fixture.controller.addListener(listener)
        fixture.controller.refresh(request(setOf(id("x"))))
        fixture.presentation.runNext()
        assertEquals(1, calls)
        fixture.controller.removeListener(listener)
        fixture.controller.removeListener(listener)
        fixture.background.runNext()
        fixture.controller.close()
        fixture.controller.close()
        fixture.presentation.runAll()
        assertEquals(1, calls)
        assertNull(fixture.controller.currentState().acceptedRequestId)
    }

    @Test fun ordinaryExceptionBecomesFailureAndFatalErrorPropagates() {
        val ordinary = Fixture { _, _ -> throw IllegalStateException("read") }
        ordinary.refreshAndDrain()
        assertTrue(ordinary.controller.currentState().operation is GroupMapOperationState.Failed)

        val fatal = Fixture { _, _ -> throw AssertionError("fatal") }
        fatal.controller.refresh(request(emptySet()))
        fatal.presentation.runNext()
        try {
            fatal.background.runNext()
            throw AssertionError("Expected fatal error")
        } catch (expected: AssertionError) {
            assertEquals("fatal", expected.message)
        }
    }

    private class Fixture(
        private val loader: (String, Collection<MapItemIdentity>) -> MapSceneLoadResult,
    ) {
        val background = QueueExecutor()
        val presentation = QueueExecutor()
        var loads = 0
        val controller = DefaultGroupMapController(
            LoadMapSceneItems { group, identities ->
                loads++
                loader(group, identities)
            },
            FakePolicy(),
            background,
            presentation,
        )

        fun refreshAndDrain(identities: Set<MapItemIdentity> = setOf(id("x"))) {
            controller.refresh(request(identities))
            presentation.runAll()
            background.runAll()
            presentation.runAll()
        }
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks += command
        }
        fun runNext() = tasks.removeFirst().run()
        fun runLast() = tasks.removeLast().run()
        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    private class FakePolicy : MapItemVisualPolicy {
        override fun evaluate(
            item: MapSceneItem,
            emphasis: MapItemEmphasis,
            environment: MapRenderEnvironment,
            itemOutcome: MapSceneItemOutcome,
        ) = MapItemVisualPolicyResult.Renderable(
            MapItemRenderSpec(
                item.identity,
                item.geometry,
                MapItemRenderContent(item.normalizedKind, item.label),
                MapItemVisualSpec(
                    MapCategoryVisualStyle.CANONICAL_ONLY,
                    null,
                    MapTemporalVisualStyle.UNRESOLVED,
                    MapSituationEvidenceVisualStyle.UNAVAILABLE,
                    MapCoordinationParticipationVisualStyle.UNAVAILABLE,
                    setOf(MapItemLimitationVisualStyle.CANONICAL_ONLY),
                    emphasis,
                    MapItemProminence.STANDARD,
                    MapLabelMode.HIDDEN,
                    MapRadiusMode.HIDDEN,
                ),
                MapItemRenderInteractionSpec(true, true),
                MapItemAccessibilitySpec(
                    MapAccessibilityCategory.UNRESOLVED_ITEM,
                    listOf(MapAccessibilityStateToken.LIMITED_INFORMATION),
                ),
            ),
        )
    }

    companion object {
        private fun id(value: String, type: MapItemType = MapItemType.MARKER) =
            MapItemIdentity("g", type, value)

        private fun request(identities: Set<MapItemIdentity>) = GroupMapViewportRequest(
            "g",
            identities,
            MapRenderEnvironment(MapZoomBand.NEAR, MapDensityBand.SPARSE),
        )

        private fun found(identity: MapItemIdentity) = MapSceneItemLoadResult.CanonicalOnly(
            MapSceneItem(
                identity,
                "kind",
                "Label",
                null,
                MapItemCanonicalAvailability.ACTIVE,
                null,
                when (identity.itemType) {
                    MapItemType.MARKER -> MapSceneGeometry.Marker(1.0, 2.0, null)
                    MapItemType.AREA -> MapSceneGeometry.Area(
                        app.peermesh.map.application.scene.MapSceneAreaGeometry.Polygon(emptyList()),
                    )
                },
                null,
                MapSceneTemporalPresentation.Unresolved,
                MapItemVerificationProjectionResult.Unavailable(
                    MapVerificationUnavailableReason.DOMAIN_STATE_MISSING,
                ),
                MapCoordinationParticipationProjectionResult.DomainStateUnavailable,
            ),
        )
    }
}
