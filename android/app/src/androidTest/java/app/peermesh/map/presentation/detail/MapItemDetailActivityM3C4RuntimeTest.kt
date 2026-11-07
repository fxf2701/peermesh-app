package app.peermesh.map.presentation.detail

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.R
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis
import app.peermesh.map.application.interaction.MapInteractionAvailabilityState
import app.peermesh.map.application.interaction.MapItemInteractionAvailability
import app.peermesh.map.application.interaction.MapItemInteractionsAvailability
import app.peermesh.map.application.interaction.MapInteractionType
import app.peermesh.map.application.participation.MapCoordinationAcknowledgementEvidence
import app.peermesh.map.application.participation.MapCoordinationCategoryOrigin
import app.peermesh.map.application.participation.MapCoordinationNotePreview
import app.peermesh.map.application.participation.MapCoordinationNotePreviewProjectionResult
import app.peermesh.map.application.participation.MapCoordinationParticipationProjectionResult
import app.peermesh.map.application.participation.MapCoordinationParticipationPresentation
import app.peermesh.map.application.participation.MapCoordinationParticipationLimitation
import app.peermesh.map.application.participation.MapCoordinationTargetShape
import app.peermesh.map.application.timeline.MapItemActivityActor
import app.peermesh.map.application.verification.MapCreatorInformationSourcePresentation
import app.peermesh.map.application.verification.MapItemVerificationProjectionResult
import app.peermesh.map.application.verification.MapItemVerificationPresentation
import app.peermesh.map.application.verification.MapVerificationAbsenceReason
import app.peermesh.map.application.verification.MapVerificationEvidence
import app.peermesh.map.application.verification.MapVerificationFreshnessPresentation
import app.peermesh.map.application.verification.MapVerificationFreshnessProjectionResult
import app.peermesh.map.application.verification.MapVerificationFreshnessState
import app.peermesh.map.application.verification.MapVerificationCategoryOrigin
import app.peermesh.map.application.verification.MapVerificationTargetShape
import app.peermesh.map.application.verification.MapVerificationLimitation
import app.peermesh.map.presentation.community.MapItemCommunityProjectionSnapshot
import app.peermesh.map.presentation.community.MapItemCommunityTimelineProjection
import app.peermesh.map.presentation.timeline.MapItemTimelineController
import java.lang.reflect.Field
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapItemDetailActivityM3C4RuntimeTest {
    @Test fun situationAreaRendersAndroidResourcesAndReportedConditionWording() = withActivity { activity ->
        val projection = situationProjection(area = true)
        assertEquals(
            "area snapshots must use one item identity across verification and freshness",
            (projection.verification as MapItemVerificationProjectionResult.Available).presentation.identity,
            (projection.freshness as MapVerificationFreshnessProjectionResult.Available).presentation.identity,
        )
        render(activity, projection)

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.map_detail_community_section).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.map_detail_verification_heading).visibility)
        assertTrue(activity.text(R.id.map_detail_verification_body)
            .contains(activity.getString(R.string.map_detail_verification_area_limit)))
    }

    @Test fun resolvedSituationRendersResolvedPresentation() = withActivity { activity ->
        render(activity, situationProjection(resolved = true))

        assertTrue(activity.text(R.id.map_detail_verification_body)
            .contains(activity.getString(R.string.map_detail_verification_resolved)))
        assertTrue(activity.text(R.id.map_detail_freshness_body)
            .contains(activity.getString(R.string.map_detail_fresh_resolved)))
    }

    @Test fun coordinationRendersParticipationAndNoteButNotVerification() = withActivity { activity ->
        render(activity, coordinationProjection())

        assertEquals(View.GONE, activity.findViewById<View>(R.id.map_detail_verification_heading).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.map_detail_participation_heading).visibility)
        assertTrue(activity.text(R.id.map_detail_participation_body)
            .contains(activity.getString(R.string.map_detail_participation_current, 3)))
        assertTrue(activity.text(R.id.map_detail_note_preview_body).contains("Bring water"))
    }

    @Test fun confirmCtaReflectsAvailabilityAndInFlightStateAtRuntime() = withActivity { activity ->
        setDetailState(activity, MapItemDetailShellState(IDENTITY, availability = availability(enabled = true)))
        render(activity, situationProjection())
        renderInteractions(activity, MapItemDetailShellState(IDENTITY, availability = availability(enabled = true)))
        val button = activity.findViewById<Button>(R.id.map_detail_confirm)
        assertEquals(View.VISIBLE, button.visibility)
        assertTrue(button.isEnabled)

        val inFlight = MapItemDetailShellState(
            IDENTITY,
            content = MapItemDetailShellContent.InitialLoading,
            availability = availability(enabled = true),
            submittingInteraction = MapInteractionType.Situation.Confirm,
        )
        setDetailState(activity, inFlight)
        render(activity, situationProjection())
        renderInteractions(activity, inFlight)
        assertEquals(View.VISIBLE, button.visibility)
        assertFalse(button.isEnabled)
    }

    @Test fun confirmCtaIsHiddenForUnavailableResolvedAndCoordinationStates() = withActivity { activity ->
        val button = activity.findViewById<Button>(R.id.map_detail_confirm)
        val unavailable = MapItemDetailShellState(IDENTITY, availability = availability(enabled = false))
        setDetailState(activity, unavailable)
        render(activity, situationProjection())
        renderInteractions(activity, unavailable)
        assertEquals(View.GONE, button.visibility)

        setDetailState(activity, MapItemDetailShellState(IDENTITY, availability = availability(enabled = true)))
        render(activity, situationProjection(resolved = true))
        renderInteractions(activity, MapItemDetailShellState(IDENTITY, availability = availability(enabled = true)))
        assertEquals(View.GONE, button.visibility)

        render(activity, coordinationProjection())
        renderInteractions(activity, MapItemDetailShellState(IDENTITY, availability = availability(enabled = true)))
        assertEquals(View.GONE, button.visibility)
    }

    @Test fun confirmButtonDelegatesOnlyEnabledVisibleSituationConfirmToM3A() = withActivity { activity ->
        val controller = RecordingController()
        replaceController(activity, controller)
        val enabled = MapItemDetailShellState(IDENTITY, availability = availability(enabled = true))
        setDetailState(activity, enabled)
        render(activity, situationProjection())
        renderInteractions(activity, enabled)
        val button = activity.findViewById<Button>(R.id.map_detail_confirm)

        button.performClick()
        assertEquals(listOf(MapInteractionType.Situation.Confirm), controller.openedInteractions)
        assertEquals(0, controller.submissionCount)

        val inFlight = enabled.copy(submittingInteraction = MapInteractionType.Situation.Confirm)
        setDetailState(activity, inFlight)
        render(activity, situationProjection())
        renderInteractions(activity, inFlight)
        button.performClick()
        assertEquals(listOf(MapInteractionType.Situation.Confirm), controller.openedInteractions)

        val hidden = enabled.copy(availability = availability(enabled = false))
        setDetailState(activity, hidden)
        render(activity, situationProjection())
        renderInteractions(activity, hidden)
        button.performClick()
        assertEquals(listOf(MapInteractionType.Situation.Confirm), controller.openedInteractions)
        assertEquals(0, controller.submissionCount)
    }

    private fun render(activity: MapItemDetailActivity, projection: MapItemCommunityProjectionSnapshot) =
        invoke(activity, "renderCommunityPresentation", MapItemCommunityProjectionSnapshot::class.java, projection)

    private fun renderInteractions(activity: MapItemDetailActivity, state: MapItemDetailShellState) =
        invoke(activity, "renderInteractions", MapItemDetailShellState::class.java, state)

    private fun setDetailState(activity: MapItemDetailActivity, state: MapItemDetailShellState) {
        field(activity, "latestDetailState").set(activity, state)
    }

    private fun replaceController(activity: MapItemDetailActivity, replacement: MapItemDetailShellController) {
        field(activity, "controller").get(activity)
            .let { it as? MapItemDetailShellController }
            ?.close()
        field(activity, "timelineController").get(activity)
            .let { it as? MapItemTimelineController }
            ?.close()
        field(activity, "timelineController").set(activity, null)
        field(activity, "controller").set(activity, replacement)
    }

    private fun invoke(activity: MapItemDetailActivity, name: String, parameter: Class<*>, value: Any) {
        activity.javaClass.getDeclaredMethod(name, parameter).also { method: Method ->
            method.isAccessible = true
            method.invoke(activity, value)
        }
    }

    private fun field(activity: MapItemDetailActivity, name: String): Field =
        activity.javaClass.getDeclaredField(name).also { it.isAccessible = true }

    private fun withActivity(block: (MapItemDetailActivity) -> Unit) {
        ActivityScenario.launch(MapItemDetailActivity::class.java).use { scenario ->
            scenario.onActivity(block)
        }
    }

    private fun MapItemDetailActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    private fun availability(enabled: Boolean) = MapItemInteractionsAvailability(
        IDENTITY,
        listOf(MapItemInteractionAvailability(
            MapInteractionType.Situation.Confirm,
            if (enabled) MapInteractionAvailabilityState.ENABLED else MapInteractionAvailabilityState.HIDDEN,
        )),
    )

    private fun situationProjection(area: Boolean = false, resolved: Boolean = false): MapItemCommunityProjectionSnapshot.Projected {
        val identity = if (area) AREA_IDENTITY else IDENTITY
        return MapItemCommunityProjectionSnapshot.Projected(
            MapItemVerificationProjectionResult.Available(MapItemVerificationPresentation(
                identity,
                if (area) MapVerificationTargetShape.AREA else MapVerificationTargetShape.MARKER,
                MapVerificationCategoryOrigin.EXPLICIT_DOMAIN_V1,
                if (resolved) MapSituationLifecycleStatus.NO_LONGER_ACTIVE else MapSituationLifecycleStatus.ACTIVE,
                if (resolved) MapVerificationEvidence.Absent else MapVerificationEvidence.Present(2, 123),
                MapCreatorInformationSourcePresentation.Known(MapInformationSource.FIRSTHAND),
                if (area) MapVerificationLimitation.AREA_REPORTED_CONDITION_ONLY else null,
                MapSituationConfirmationFreshnessBasis.Unknown,
            )),
            MapVerificationFreshnessProjectionResult.Available(MapVerificationFreshnessPresentation(
                identity,
                if (resolved) MapVerificationFreshnessState.Absent(MapVerificationAbsenceReason.INVALIDATED_BY_RESOLUTION)
                else MapVerificationFreshnessState.Absent(MapVerificationAbsenceReason.NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED),
            )),
            MapCoordinationParticipationProjectionResult.NotApplicableToSituation,
            MapItemCommunityTimelineProjection.NotReady,
        )
    }

    private fun coordinationProjection() = MapItemCommunityProjectionSnapshot.Projected(
        MapItemVerificationProjectionResult.CoordinationNotApplicable,
        MapVerificationFreshnessProjectionResult.CoordinationNotApplicable,
        MapCoordinationParticipationProjectionResult.Available(MapCoordinationParticipationPresentation(
            IDENTITY,
            MapCoordinationTargetShape.MARKER,
            MapCoordinationCategoryOrigin.EXPLICIT_DOMAIN_V1,
            MapCoordinationAcknowledgementEvidence.Present(3),
            MapCoordinationParticipationLimitation.ACKNOWLEDGEMENT_IS_NON_VERIFYING,
        )),
        MapItemCommunityTimelineProjection.Available(MapCoordinationNotePreviewProjectionResult.Available(
            MapCoordinationNotePreview("entry", IDENTITY, MapItemActivityActor("actor", "Actor"), 50, "Bring water"),
        )),
    )

    companion object {
        private val IDENTITY = MapItemIdentity("group", MapItemType.MARKER, "item")
        private val AREA_IDENTITY = MapItemIdentity("group", MapItemType.AREA, "item")
    }

    private class RecordingController : MapItemDetailShellController {
        override val currentState = MapItemDetailShellState(IDENTITY)
        val openedInteractions = mutableListOf<MapInteractionType>()
        var submissionCount = 0

        override fun load() = Unit
        override fun refresh() = Unit
        override fun openInteraction(interaction: MapInteractionType) { openedInteractions += interaction }
        override fun updateForm(form: MapItemInteractionForm) = Unit
        override fun submitActiveForm() { submissionCount++ }
        override fun dismissForm() = Unit
        override fun consumeFeedback() = Unit
        override fun close() = Unit
    }
}
