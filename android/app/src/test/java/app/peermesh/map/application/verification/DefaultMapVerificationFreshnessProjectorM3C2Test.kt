package app.peermesh.map.application.verification

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis
import app.peermesh.map.application.detail.InvalidIdentityReason
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.composition.MapVerificationFreshnessPresentationFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMapVerificationFreshnessProjectorM3C2Test {
    private val projector = DefaultMapVerificationFreshnessProjector()

    @Test fun currentEvidenceUsesAcceptedM2CountAndSafeTimestamp() {
        assertState(
            MapVerificationFreshnessState.Current(4, 123),
            presentation(
                evidence = MapVerificationEvidence.Present(4, 123),
                basis = MapSituationConfirmationFreshnessBasis.Current(123),
            ),
        )
    }

    @Test fun activeAbsenceUsesExactSemanticReason() {
        assertState(
            MapVerificationFreshnessState.Absent(
                MapVerificationAbsenceReason.NO_ACCEPTED_PRIOR_CONFIRMATION_ESTABLISHED,
            ),
            presentation(basis = MapSituationConfirmationFreshnessBasis.NoAcceptedPriorConfirmationEstablished),
        )
        assertState(
            MapVerificationFreshnessState.Absent(MapVerificationAbsenceReason.INVALIDATED_BY_SITUATION_CHANGE),
            presentation(basis = MapSituationConfirmationFreshnessBasis.InvalidatedBySituationChange),
        )
    }

    @Test fun resolvedAbsenceDistinguishesPriorConfirmationFromNoPriorConfirmation() {
        assertState(
            MapVerificationFreshnessState.Absent(MapVerificationAbsenceReason.INVALIDATED_BY_RESOLUTION),
            presentation(
                lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                basis = MapSituationConfirmationFreshnessBasis.InvalidatedByResolution,
            ),
        )
        assertState(
            MapVerificationFreshnessState.Absent(
                MapVerificationAbsenceReason.RESOLVED_WITHOUT_PRIOR_CONFIRMATION,
            ),
            presentation(
                lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                basis = MapSituationConfirmationFreshnessBasis.NoAcceptedPriorConfirmationEstablished,
            ),
        )
    }

    @Test fun unknownAndContradictoryCombinationsRemainHonest() {
        val unknown = MapVerificationFreshnessState.Absent(MapVerificationAbsenceReason.UNKNOWN)
        assertState(unknown, presentation(basis = MapSituationConfirmationFreshnessBasis.Unknown))
        assertState(
            unknown,
            presentation(basis = MapSituationConfirmationFreshnessBasis.InvalidatedByResolution),
        )
        assertState(
            unknown,
            presentation(
                lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                basis = MapSituationConfirmationFreshnessBasis.InvalidatedBySituationChange,
            ),
        )
        assertState(
            unknown,
            presentation(
                evidence = MapVerificationEvidence.Present(2, null),
                basis = MapSituationConfirmationFreshnessBasis.InvalidatedBySituationChange,
            ),
        )
        assertState(
            unknown,
            presentation(
                evidence = MapVerificationEvidence.Present(2, 124),
                basis = MapSituationConfirmationFreshnessBasis.Current(123),
            ),
        )
        assertState(unknown, presentation(basis = MapSituationConfirmationFreshnessBasis.Current(null)))
    }

    @Test fun nonAvailableM3C1OutcomesArePreservedExactly() {
        assertEquals(
            MapVerificationFreshnessProjectionResult.CoordinationNotApplicable,
            projector.project(MapItemVerificationProjectionResult.CoordinationNotApplicable),
        )
        assertEquals(
            MapVerificationFreshnessProjectionResult.UnresolvedLegacyCategory,
            projector.project(MapItemVerificationProjectionResult.UnresolvedLegacyCategory),
        )
        assertEquals(
            MapVerificationFreshnessProjectionResult.Unavailable(MapVerificationUnavailableReason.INTERNAL_READ_FAILURE),
            projector.project(
                MapItemVerificationProjectionResult.Unavailable(MapVerificationUnavailableReason.INTERNAL_READ_FAILURE),
            ),
        )
        assertEquals(
            MapVerificationFreshnessProjectionResult.InvalidIdentity(InvalidIdentityReason.BLANK_ITEM_ID),
            projector.project(MapItemVerificationProjectionResult.InvalidIdentity(InvalidIdentityReason.BLANK_ITEM_ID)),
        )
    }

    @Test fun projectorAndFactoryHaveNoDependencies() {
        assertTrue(DefaultMapVerificationFreshnessProjector::class.java.declaredConstructors.single().parameterTypes.isEmpty())
        assertTrue(MapVerificationFreshnessPresentationFactory.create() is DefaultMapVerificationFreshnessProjector)
    }

    private fun assertState(expected: MapVerificationFreshnessState, presentation: MapItemVerificationPresentation) {
        val result = projector.project(MapItemVerificationProjectionResult.Available(presentation))
            as MapVerificationFreshnessProjectionResult.Available
        assertEquals(expected, result.presentation.state)
        assertEquals(IDENTITY, result.presentation.identity)
    }

    private fun presentation(
        lifecycle: MapSituationLifecycleStatus = MapSituationLifecycleStatus.ACTIVE,
        evidence: MapVerificationEvidence = MapVerificationEvidence.Absent,
        basis: MapSituationConfirmationFreshnessBasis,
    ) = MapItemVerificationPresentation(
        identity = IDENTITY,
        targetShape = MapVerificationTargetShape.MARKER,
        categoryOrigin = MapVerificationCategoryOrigin.EXPLICIT_DOMAIN_V1,
        lifecycle = lifecycle,
        evidence = evidence,
        creatorSource = MapCreatorInformationSourcePresentation.Known(MapInformationSource.FIRSTHAND),
        limitation = null,
        freshnessBasis = basis,
    )

    companion object {
        private val IDENTITY = MapItemIdentity("g", MapItemType.MARKER, "i")
    }
}
