package app.peermesh.map.application.interaction

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.map.MapItemWindows
import app.peermesh.map.application.detail.CanonicalOnlyReason
import app.peermesh.map.application.detail.MapCoordinatesPresentation
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemDetailLoadResult
import app.peermesh.map.application.detail.MapItemDomainPresentation
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.detail.MarkerCanonicalPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultEvaluateMapItemInteractionsM3A2Test {
    @Test fun foundReadsEachDependencyAndClockOnceAndSharesSnapshot() {
        val fixture = fixture(clockValue = 777)
        val result = fixture.useCase.evaluate(MapItemDetailLoadResult.Found(detail()))
        assertEquals(1, fixture.participationReads)
        assertEquals(1, fixture.supportReads)
        assertEquals(1, fixture.capabilityReads)
        assertEquals(1, fixture.clockReads)
        assertEquals(IDENTITY, result.identity)
        assertTrue(result.interactions.first { it.interaction == MapInteractionType.Situation.Confirm }.isEnabled)
        assertEquals(5, fixture.evaluatorInputs.single().let { input ->
            DefaultMapInteractionAvailabilityEvaluator().evaluate(input).interactions.size
        })
        assertEquals(777, fixture.evaluatorInputs.single().nowSeconds)
    }

    @Test fun everyM3A1LoadResultMapsToItsDistinctTargetState() {
        val canonical = canonical()
        val cases = listOf(
            MapItemDetailLoadResult.Found(detail()) to MapInteractionTargetState.Available::class.java,
            MapItemDetailLoadResult.UnresolvedLegacy(detail(domain = domain(category = null,
                categoryOrigin = MapItemCategoryOrigin.UNRESOLVED,
                temporalOrigin = MapItemTemporalOrigin.LEGACY_UNRESOLVED,
                lifecycle = null))) to MapInteractionTargetState.Available::class.java,
            MapItemDetailLoadResult.CanonicalOnly(MapItemDetail(IDENTITY, canonical, null),
                CanonicalOnlyReason.DOMAIN_STATE_MISSING) to MapInteractionTargetState.DomainStateMissing::class.java,
            MapItemDetailLoadResult.InvalidIdentity(app.peermesh.map.application.detail.InvalidIdentityReason.BLANK_GROUP_ID) to
                MapInteractionTargetState.InvalidIdentity::class.java,
            MapItemDetailLoadResult.CanonicalItemMissing to MapInteractionTargetState.CanonicalMissing::class.java,
            MapItemDetailLoadResult.InvalidCanonicalPersistedState to MapInteractionTargetState.InvalidCanonicalState::class.java,
            MapItemDetailLoadResult.InvalidDomainPersistedState(canonical) to MapInteractionTargetState.InvalidDomainState::class.java,
            MapItemDetailLoadResult.InternalReadFailure to MapInteractionTargetState.InternalReadFailure::class.java,
        )
        cases.forEach { (load, expected) ->
            val fixture = fixture()
            fixture.useCase.evaluate(load)
            assertEquals(expected, fixture.evaluatorInputs.single().target::class.java)
        }
    }

    @Test fun targetTypeConflictAndUnresolvedTemporalArePreserved() {
        val wrongCanonical = canonical(identity = IDENTITY.copy(itemType = MapItemType.AREA))
        val conflict = fixture().also { it.useCase.evaluate(MapItemDetailLoadResult.Found(detail(canonical = wrongCanonical))) }
        assertTrue(conflict.evaluatorInputs.single().target is MapInteractionTargetState.TargetTypeConflict)

        val unresolved = fixture().also { it.useCase.evaluate(MapItemDetailLoadResult.UnresolvedLegacy(
            detail(domain = domain(temporalOrigin = MapItemTemporalOrigin.LEGACY_UNRESOLVED)))) }
        val target = unresolved.evaluatorInputs.single().target as MapInteractionTargetState.Available
        assertTrue(target.detail.temporalState is MapInteractionTemporalState.Unresolved)
    }

    @Test fun providerFailuresFailClosedWithoutCrashing() {
        val fixture = fixture(throwProviders = true)
        val result = fixture.useCase.evaluate(MapItemDetailLoadResult.Found(detail()))
        val confirm = result.interactions.single { it.interaction == MapInteractionType.Situation.Confirm }
        assertEquals(MapInteractionAvailabilityReason.LOCAL_MEMBERSHIP_UNAVAILABLE, confirm.primaryReason)
        assertEquals(1, fixture.participationReads)
        assertEquals(1, fixture.supportReads)
        assertEquals(1, fixture.capabilityReads)
    }

    @Test fun targetReadBlockersPreserveReasonWithoutReadingAnyAdvisoryDependency() {
        val canonical = canonical()
        val cases = listOf(
            MapItemDetailLoadResult.InvalidIdentity(app.peermesh.map.application.detail.InvalidIdentityReason.BLANK_GROUP_ID) to
                MapInteractionAvailabilityReason.INVALID_TARGET_IDENTITY,
            MapItemDetailLoadResult.CanonicalItemMissing to MapInteractionAvailabilityReason.CANONICAL_TARGET_MISSING,
            MapItemDetailLoadResult.InvalidCanonicalPersistedState to MapInteractionAvailabilityReason.INVALID_CANONICAL_STATE,
            MapItemDetailLoadResult.CanonicalOnly(MapItemDetail(IDENTITY, canonical, null),
                CanonicalOnlyReason.DOMAIN_STATE_MISSING) to MapInteractionAvailabilityReason.DOMAIN_STATE_MISSING,
            MapItemDetailLoadResult.InvalidDomainPersistedState(canonical) to MapInteractionAvailabilityReason.INVALID_DOMAIN_STATE,
            MapItemDetailLoadResult.InternalReadFailure to MapInteractionAvailabilityReason.INTERNAL_READ_FAILURE,
        )
        cases.forEach { (loadResult, expectedReason) ->
            val fixture = fixture(throwClock = true, throwProviders = true)
            val result = fixture.useCase.evaluate(loadResult)
            assertEquals(expectedReason, result.interactions.first().primaryReason)
            assertEquals(0, fixture.clockReads)
            assertEquals(0, fixture.participationReads)
            assertEquals(0, fixture.supportReads)
            assertEquals(0, fixture.capabilityReads)
        }
    }

    @Test fun clockFailureForAvailableTargetFailsClosedBeforeProviderReads() {
        val fixture = fixture(throwClock = true, throwProviders = true)
        val result = fixture.useCase.evaluate(MapItemDetailLoadResult.Found(detail()))
        assertEquals(MapInteractionAvailabilityReason.INTERNAL_READ_FAILURE, result.interactions.first().primaryReason)
        assertEquals(1, fixture.clockReads)
        assertEquals(0, fixture.participationReads)
        assertEquals(0, fixture.supportReads)
        assertEquals(0, fixture.capabilityReads)
    }

    @Test fun orchestrationHasNoHistoryTransportProcessorOrSubmissionDependency() {
        val names = DefaultEvaluateMapItemInteractions::class.java.declaredConstructors.single()
            .parameterTypes.map { it.name }
        assertFalse(names.any { it.contains("ActionQuery") || it.contains("History") ||
            it.contains("Transport") || it.contains("Processor") || it.contains("EventCore") })
    }

    private fun fixture(
        clockValue: Long = 100,
        throwProviders: Boolean = false,
        throwClock: Boolean = false,
    ): Fixture = Fixture(clockValue, throwProviders, throwClock)

    private class Fixture(
        clockValue: Long,
        private val throwProviders: Boolean,
        private val throwClock: Boolean,
    ) {
        var participationReads = 0
        var supportReads = 0
        var capabilityReads = 0
        var clockReads = 0
        val evaluatorInputs = mutableListOf<MapItemInteractionEvaluationInput>()
        private val delegate = DefaultMapInteractionAvailabilityEvaluator()
        val useCase = DefaultEvaluateMapItemInteractions(
            participationProvider = {
                participationReads++
                if (throwProviders) error("membership unavailable")
                LocalMapParticipation.CurrentMember
            },
            localSupportProvider = {
                supportReads++
                if (throwProviders) error("support unavailable")
                LocalMapInteractionSupport.allSupported()
            },
            peerCapabilityProvider = {
                capabilityReads++
                if (throwProviders) error("capability unavailable")
                MapPeerCapabilitySummary(MapPeerCapabilityLevel.SUPPORTED)
            },
            clock = {
                clockReads++
                if (throwClock) error("clock unavailable")
                clockValue
            },
            evaluator = object : MapInteractionAvailabilityEvaluator {
                override fun evaluate(input: MapItemInteractionEvaluationInput): MapItemInteractionsAvailability {
                    evaluatorInputs += input
                    return delegate.evaluate(input)
                }
            },
        )
    }

    companion object {
        private val IDENTITY = MapItemIdentity("g", MapItemType.MARKER, "m")

        private fun canonical(identity: MapItemIdentity = IDENTITY) = MarkerCanonicalPresentation(
            identity, "risk", "Flood", null, MapItemCanonicalAvailability.ACTIVE, 1_000,
            MapCoordinatesPresentation(10.0, 106.0), null,
        )

        private fun domain(
            category: MapItemCategory? = MapItemCategory.SITUATION,
            categoryOrigin: MapItemCategoryOrigin = MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1,
            temporalOrigin: MapItemTemporalOrigin = MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1,
            lifecycle: MapSituationLifecycleStatus? = MapSituationLifecycleStatus.ACTIVE,
        ) = MapItemDomainPresentation(
            category, categoryOrigin, if (category == MapItemCategory.SITUATION) MapInformationSource.FIRSTHAND else null,
            lifecycle, MapItemWindows(1_000, 500, 1_000), temporalOrigin, 0, 0,
        )

        private fun detail(
            canonical: MarkerCanonicalPresentation = canonical(),
            domain: MapItemDomainPresentation = domain(),
        ) = MapItemDetail(IDENTITY, canonical, domain)
    }
}
