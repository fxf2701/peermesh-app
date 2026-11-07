package app.peermesh.map.application.interaction

import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMapInteractionAvailabilityEvaluatorM3A2Test {
    private val evaluator = DefaultMapInteractionAvailabilityEvaluator()

    @Test fun situationAndCoordinationExposeOnlyTheirCompatibleFamilies() {
        val situation = evaluate(detail(category = MapItemCategory.SITUATION, lifecycle = MapSituationLifecycleStatus.ACTIVE))
        assertStates(situation, enabled = SITUATION, hidden = COORDINATION)

        val coordination = evaluate(detail(category = MapItemCategory.COORDINATION, lifecycle = null))
        assertStates(coordination, enabled = COORDINATION, hidden = SITUATION)
    }

    @Test fun unresolvedCategoryBlocksAllBeforeDeletion() {
        val result = evaluate(detail(category = null, deleted = true, lifecycle = null))
        result.interactions.forEach {
            assertEquals(MapInteractionAvailabilityState.UNAVAILABLE_UNRESOLVED, it.state)
            assertEquals(MapInteractionAvailabilityReason.CATEGORY_UNRESOLVED, it.primaryReason)
        }
    }

    @Test fun incompatibleActionIsHiddenBeforeDeletion() {
        val result = evaluate(detail(category = MapItemCategory.SITUATION, deleted = true))
        assertEquals(MapInteractionAvailabilityReason.TARGET_DELETED, result[MapInteractionType.Situation.Confirm].primaryReason)
        assertEquals(MapInteractionAvailabilityReason.ACTION_INCOMPATIBLE_WITH_CATEGORY,
            result[MapInteractionType.Coordination.Acknowledge].primaryReason)
    }

    @Test fun targetFailuresRemainDistinctAndFailClosed() {
        val cases = listOf(
            MapInteractionTargetState.InvalidIdentity to MapInteractionAvailabilityReason.INVALID_TARGET_IDENTITY,
            MapInteractionTargetState.CanonicalMissing to MapInteractionAvailabilityReason.CANONICAL_TARGET_MISSING,
            MapInteractionTargetState.TargetTypeConflict to MapInteractionAvailabilityReason.TARGET_TYPE_CONFLICT,
            MapInteractionTargetState.InvalidCanonicalState to MapInteractionAvailabilityReason.INVALID_CANONICAL_STATE,
            MapInteractionTargetState.DomainStateMissing to MapInteractionAvailabilityReason.DOMAIN_STATE_MISSING,
            MapInteractionTargetState.InvalidDomainState to MapInteractionAvailabilityReason.INVALID_DOMAIN_STATE,
            MapInteractionTargetState.InternalReadFailure to MapInteractionAvailabilityReason.INTERNAL_READ_FAILURE,
        )
        cases.forEach { (target, reason) ->
            evaluate(target).interactions.forEach { assertEquals(reason, it.primaryReason) }
        }
    }

    @Test fun resolvedSituationKeepsConfirmAndUpdateDisabledButHidesRepeatedResolution() {
        val result = evaluate(detail(lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
            expires = 1, cutoff = 1))
        listOf(MapInteractionType.Situation.Confirm, MapInteractionType.Situation.AddUpdate).forEach {
            assertEquals(MapInteractionAvailabilityState.DISABLED, result[it].state)
            assertEquals(MapInteractionAvailabilityReason.SITUATION_RESOLVED, result[it].primaryReason)
        }
        assertEquals(MapInteractionAvailabilityState.HIDDEN, result[MapInteractionType.Situation.MarkNoLongerActive].state)
        assertEquals(MapInteractionAvailabilityReason.SITUATION_ALREADY_RESOLVED,
            result[MapInteractionType.Situation.MarkNoLongerActive].primaryReason)
    }

    @Test fun unresolvedSituationLifecycleAndUnexpectedCoordinationLifecycleFailClosed() {
        assertEquals(MapInteractionAvailabilityReason.SITUATION_LIFECYCLE_UNRESOLVED,
            evaluate(detail(lifecycle = null))[MapInteractionType.Situation.Confirm].primaryReason)
        assertEquals(MapInteractionAvailabilityReason.UNEXPECTED_COORDINATION_LIFECYCLE,
            evaluate(detail(category = MapItemCategory.COORDINATION,
                lifecycle = MapSituationLifecycleStatus.ACTIVE))[MapInteractionType.Coordination.AddNote].primaryReason)
    }

    @Test fun expiryAndInteractionCutoffAreExclusiveAndExpiryWins() {
        assertTrue(evaluate(detail(expires = 101, cutoff = 101), now = 100)[MapInteractionType.Situation.Confirm].isEnabled)
        assertReason(detail(expires = 100, cutoff = 101), 100, MapInteractionAvailabilityReason.TARGET_EXPIRED)
        assertReason(detail(expires = 99, cutoff = 101), 100, MapInteractionAvailabilityReason.TARGET_EXPIRED)
        assertTrue(evaluate(detail(expires = 200, cutoff = 101), now = 100)[MapInteractionType.Situation.Confirm].isEnabled)
        assertReason(detail(expires = 200, cutoff = 100), 100, MapInteractionAvailabilityReason.INTERACTION_WINDOW_CLOSED)
        assertReason(detail(expires = 200, cutoff = 99), 100, MapInteractionAvailabilityReason.INTERACTION_WINDOW_CLOSED)
        assertReason(detail(expires = 99, cutoff = 99), 100, MapInteractionAvailabilityReason.TARGET_EXPIRED)
    }

    @Test fun ownerEditCutoffDoesNotBlockAndUnresolvedTemporalDoes() {
        assertTrue(evaluate(detail(ownerEdit = 1), now = 100)[MapInteractionType.Situation.AddUpdate].isEnabled)
        val unresolved = detail().copy(temporalState = MapInteractionTemporalState.Unresolved)
        assertEquals(MapInteractionAvailabilityReason.TEMPORAL_STATE_UNRESOLVED,
            evaluate(unresolved)[MapInteractionType.Situation.Confirm].primaryReason)
    }

    @Test fun recognizedSafeNoExpiryWindowsStayOpen() {
        assertTrue(evaluate(detail(expires = null, ownerEdit = null, cutoff = null), now = 10_000)
            [MapInteractionType.Situation.Confirm].isEnabled)
    }

    @Test fun membershipOutcomesAreDistinctAndPrecedeSupportOrCapability() {
        val cases = listOf(
            LocalMapParticipation.NotMember to MapInteractionAvailabilityReason.LOCAL_ACTOR_NOT_MEMBER,
            LocalMapParticipation.Unknown to MapInteractionAvailabilityReason.LOCAL_MEMBERSHIP_UNKNOWN,
            LocalMapParticipation.Unavailable to MapInteractionAvailabilityReason.LOCAL_MEMBERSHIP_UNAVAILABLE,
        )
        cases.forEach { (membership, reason) ->
            val result = evaluate(detail(), participation = membership, support = LocalMapInteractionSupport(emptySet()),
                capability = MapPeerCapabilityLevel.UNKNOWN)[MapInteractionType.Situation.Confirm]
            assertEquals(reason, result.primaryReason)
            assertNull(result.capabilityWarning)
        }
    }

    @Test fun localSupportIsPerInteractionAndDistinctFromCompatibility() {
        val support = LocalMapInteractionSupport(MapInteractionType.all.toSet() - MapInteractionType.Situation.AddUpdate)
        assertEquals(MapInteractionAvailabilityState.UNSUPPORTED_LOCALLY,
            evaluate(detail(), support = support)[MapInteractionType.Situation.AddUpdate].state)
        assertTrue(evaluate(detail(), support = support)[MapInteractionType.Situation.Confirm].isEnabled)
        assertEquals(MapInteractionAvailabilityReason.LOCAL_FEATURE_UNSUPPORTED,
            evaluate(detail(), support = support)[MapInteractionType.Situation.AddUpdate].primaryReason)
    }

    @Test fun capabilityLevelsWarnWithoutBlockingAndNeverOverrideSemanticBlocker() {
        val warnings = mapOf(
            MapPeerCapabilityLevel.SUPPORTED to null,
            MapPeerCapabilityLevel.MIXED to MapInteractionCapabilityWarning.PEER_SUPPORT_MIXED,
            MapPeerCapabilityLevel.UNKNOWN to MapInteractionCapabilityWarning.PEER_SUPPORT_UNKNOWN,
            MapPeerCapabilityLevel.UNAVAILABLE to MapInteractionCapabilityWarning.PEER_CAPABILITY_UNAVAILABLE,
        )
        warnings.forEach { (level, warning) ->
            val availability = evaluate(detail(), capability = level)[MapInteractionType.Situation.Confirm]
            assertTrue(availability.isEnabled)
            assertEquals(warning, availability.capabilityWarning)
        }
        val deleted = evaluate(detail(deleted = true), capability = MapPeerCapabilityLevel.UNKNOWN)
            .get(MapInteractionType.Situation.Confirm)
        assertEquals(MapInteractionAvailabilityReason.TARGET_DELETED, deleted.primaryReason)
        assertNull(deleted.capabilityWarning)
    }

    @Test fun priorityResolvedBeforeExpiryExpiryBeforeMembershipMembershipBeforeSupport() {
        assertEquals(MapInteractionAvailabilityReason.SITUATION_RESOLVED,
            evaluate(detail(lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE, expires = 1), now = 100,
                participation = LocalMapParticipation.NotMember)[MapInteractionType.Situation.Confirm].primaryReason)
        assertEquals(MapInteractionAvailabilityReason.TARGET_EXPIRED,
            evaluate(detail(expires = 1), now = 100, participation = LocalMapParticipation.NotMember)
                [MapInteractionType.Situation.Confirm].primaryReason)
        assertEquals(MapInteractionAvailabilityReason.LOCAL_ACTOR_NOT_MEMBER,
            evaluate(detail(), participation = LocalMapParticipation.NotMember,
                support = LocalMapInteractionSupport(emptySet()))[MapInteractionType.Situation.Confirm].primaryReason)
    }

    private fun assertReason(detail: MapItemAvailabilityDetail, now: Long, reason: MapInteractionAvailabilityReason) {
        assertEquals(reason, evaluate(detail, now)[MapInteractionType.Situation.Confirm].primaryReason)
    }

    private fun assertStates(result: MapItemInteractionsAvailability, enabled: Set<MapInteractionType>, hidden: Set<MapInteractionType>) {
        enabled.forEach { assertTrue(result[it].isEnabled) }
        hidden.forEach {
            assertEquals(MapInteractionAvailabilityState.HIDDEN, result[it].state)
            assertFalse(result[it].isVisible)
        }
    }

    private fun evaluate(
        detail: MapItemAvailabilityDetail,
        now: Long = 100,
        participation: LocalMapParticipation = LocalMapParticipation.CurrentMember,
        support: LocalMapInteractionSupport = LocalMapInteractionSupport.allSupported(),
        capability: MapPeerCapabilityLevel = MapPeerCapabilityLevel.SUPPORTED,
    ) = evaluate(MapInteractionTargetState.Available(detail), now, participation, support, capability)

    private fun evaluate(
        target: MapInteractionTargetState,
        now: Long = 100,
        participation: LocalMapParticipation = LocalMapParticipation.CurrentMember,
        support: LocalMapInteractionSupport = LocalMapInteractionSupport.allSupported(),
        capability: MapPeerCapabilityLevel = MapPeerCapabilityLevel.SUPPORTED,
    ) = evaluator.evaluate(MapItemInteractionEvaluationInput(target, participation, support,
        MapPeerCapabilitySummary(capability), now))

    private fun detail(
        category: MapItemCategory? = MapItemCategory.SITUATION,
        lifecycle: MapSituationLifecycleStatus? = MapSituationLifecycleStatus.ACTIVE,
        deleted: Boolean = false,
        expires: Long? = 1_000,
        ownerEdit: Long? = 1_000,
        cutoff: Long? = 1_000,
    ) = MapItemAvailabilityDetail(
        MapItemIdentity("g", MapItemType.MARKER, "m"),
        if (deleted) MapItemCanonicalAvailability.DELETED else MapItemCanonicalAvailability.ACTIVE,
        category, lifecycle, MapInteractionTemporalState.Resolved(expires, ownerEdit, cutoff),
    )

    private operator fun MapItemInteractionsAvailability.get(type: MapInteractionType) =
        interactions.single { it.interaction == type }

    companion object {
        private val SITUATION = setOf(MapInteractionType.Situation.Confirm,
            MapInteractionType.Situation.AddUpdate, MapInteractionType.Situation.MarkNoLongerActive)
        private val COORDINATION = setOf(MapInteractionType.Coordination.Acknowledge,
            MapInteractionType.Coordination.AddNote)
    }
}
