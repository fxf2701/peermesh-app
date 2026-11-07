package app.peermesh.map.application.verification

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.map.MapItemWindows
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis
import app.peermesh.map.application.detail.AreaCanonicalPresentation
import app.peermesh.map.application.detail.CanonicalOnlyReason
import app.peermesh.map.application.detail.InvalidIdentityReason
import app.peermesh.map.application.detail.MapAreaGeometrySummary
import app.peermesh.map.application.detail.MapCoordinatesPresentation
import app.peermesh.map.application.detail.MapItemCanonicalAvailability
import app.peermesh.map.application.detail.MapItemDetail
import app.peermesh.map.application.detail.MapItemDetailLoadResult
import app.peermesh.map.application.detail.MapItemDomainPresentation
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.detail.MarkerCanonicalPresentation
import app.peermesh.map.composition.MapItemVerificationPresentationFactory
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultMapItemVerificationProjectorM3C1Test {
    private val projector = DefaultMapItemVerificationProjector()

    @Test fun activeSituationUsesExactCurrentCountAndExplicitCreatorSource() {
        val result = available(found(domain(confirmationCount = 7, acknowledgementCount = 99)))
        val evidence = result.evidence as MapVerificationEvidence.Present

        assertEquals(7, evidence.actorCount)
        assertNull(evidence.latestCurrentConfirmationAtSeconds)
        assertEquals(MapSituationLifecycleStatus.ACTIVE, result.lifecycle)
        assertEquals(MapVerificationCategoryOrigin.EXPLICIT_DOMAIN_V1, result.categoryOrigin)
        assertEquals(
            MapCreatorInformationSourcePresentation.Known(MapInformationSource.FIRSTHAND),
            result.creatorSource,
        )
    }

    @Test fun currentFreshnessBasisTransportsSafeTimestampIntoPresentEvidence() {
        val basis = MapSituationConfirmationFreshnessBasis.Current(456)
        val result = available(found(domain(freshnessBasis = basis)))

        assertEquals(basis, result.freshnessBasis)
        assertEquals(MapVerificationEvidence.Present(2, 456), result.evidence)
    }

    @Test fun presentEvidenceRejectsNonpositiveActorCountsAndNegativeTimestamp() {
        assertInvalidConstruction { MapVerificationEvidence.Present(0, null) }
        assertInvalidConstruction { MapVerificationEvidence.Present(-1, null) }
        assertInvalidConstruction { MapVerificationEvidence.Present(1, -1L) }
    }

    @Test fun presentEvidenceAcceptsPositiveCountWithNullOrNonnegativeTimestamp() {
        assertEquals(MapVerificationEvidence.Present(1, null), MapVerificationEvidence.Present(1, null))
        assertEquals(MapVerificationEvidence.Present(2, 0L), MapVerificationEvidence.Present(2, 0L))
    }

    @Test fun presentationRejectsPresentEvidenceForResolvedLifecycle() {
        assertInvalidConstruction {
            presentation(
                lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                evidence = MapVerificationEvidence.Present(1, null),
            )
        }
    }

    @Test fun activePresentationAcceptsBothEvidenceStates() {
        presentation(evidence = MapVerificationEvidence.Present(1, null))
        presentation(evidence = MapVerificationEvidence.Absent)
    }

    @Test fun presentationRejectsIdentityTargetShapeMismatches() {
        assertInvalidConstruction { presentation(targetShape = MapVerificationTargetShape.AREA) }
        assertInvalidConstruction {
            presentation(
                identity = AREA,
                targetShape = MapVerificationTargetShape.MARKER,
                limitation = null,
            )
        }
    }

    @Test fun presentationRejectsContradictoryAreaLimitations() {
        assertInvalidConstruction {
            presentation(limitation = MapVerificationLimitation.AREA_REPORTED_CONDITION_ONLY)
        }
        assertInvalidConstruction {
            presentation(
                identity = AREA,
                targetShape = MapVerificationTargetShape.AREA,
                limitation = null,
            )
        }
    }

    @Test fun validMarkerAndAreaPresentationsConstruct() {
        presentation()
        presentation(
            identity = AREA,
            targetShape = MapVerificationTargetShape.AREA,
            limitation = MapVerificationLimitation.AREA_REPORTED_CONDITION_ONLY,
        )
    }

    @Test fun explicitDomainPresentationRejectsUnspecifiedLegacyCreatorSource() {
        assertInvalidConstruction {
            presentation(creatorSource = MapCreatorInformationSourcePresentation.UnspecifiedLegacy)
        }
    }

    @Test fun explicitKnownAndLegacyUnspecifiedCreatorSourcesConstruct() {
        presentation(
            categoryOrigin = MapVerificationCategoryOrigin.EXPLICIT_DOMAIN_V1,
            creatorSource = MapCreatorInformationSourcePresentation.Known(MapInformationSource.FIRSTHAND),
        )
        presentation(
            categoryOrigin = MapVerificationCategoryOrigin.LEGACY_KIND_DERIVED,
            creatorSource = MapCreatorInformationSourcePresentation.UnspecifiedLegacy,
        )
    }

    @Test fun zeroCurrentCountIsExplicitlyAbsentAndAcknowledgementsAreIgnored() {
        val result = available(found(domain(confirmationCount = 0, acknowledgementCount = 500)))
        assertEquals(MapVerificationEvidence.Absent, result.evidence)
    }

    @Test fun resolvedSituationWithPositiveCountFailsSafe() {
        val result = available(
            found(
                domain(
                    lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                    confirmationCount = 12,
                ),
            ),
        )
        assertEquals(MapSituationLifecycleStatus.NO_LONGER_ACTIVE, result.lifecycle)
        assertEquals(MapVerificationEvidence.Absent, result.evidence)
    }

    @Test fun coordinationIsExplicitlyNotApplicableRegardlessOfAcknowledgementCount() {
        val coordination = domain(
            category = MapItemCategory.COORDINATION,
            creatorSource = null,
            lifecycle = null,
            confirmationCount = 0,
            acknowledgementCount = 41,
        )
        assertEquals(
            MapItemVerificationProjectionResult.CoordinationNotApplicable,
            projector.project(found(coordination)),
        )
    }

    @Test fun unresolvedLegacyDoesNotInferCategoryFromKindOrCounts() {
        val unresolved = domain(
            category = null,
            categoryOrigin = MapItemCategoryOrigin.UNRESOLVED,
            creatorSource = null,
            lifecycle = null,
            confirmationCount = 23,
        )
        val detail = detail(MARKER, unresolved, normalizedKind = "situation_emergency")
        assertEquals(
            MapItemVerificationProjectionResult.UnresolvedLegacyCategory,
            projector.project(MapItemDetailLoadResult.UnresolvedLegacy(detail)),
        )
    }

    @Test fun canonicalOnlyMissingAndInvalidResultsRemainDistinct() {
        val canonicalOnly = MapItemDetailLoadResult.CanonicalOnly(
            detail(MARKER, null),
            CanonicalOnlyReason.DOMAIN_STATE_MISSING,
        )
        assertUnavailable(canonicalOnly, MapVerificationUnavailableReason.DOMAIN_STATE_MISSING)
        assertUnavailable(
            MapItemDetailLoadResult.CanonicalItemMissing,
            MapVerificationUnavailableReason.CANONICAL_ITEM_MISSING,
        )
        assertUnavailable(
            MapItemDetailLoadResult.InvalidCanonicalPersistedState,
            MapVerificationUnavailableReason.INVALID_CANONICAL_PERSISTED_STATE,
        )
        assertUnavailable(
            MapItemDetailLoadResult.InvalidDomainPersistedState(canonical(MARKER)),
            MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE,
        )
        assertUnavailable(
            MapItemDetailLoadResult.InternalReadFailure,
            MapVerificationUnavailableReason.INTERNAL_READ_FAILURE,
        )
    }

    @Test fun invalidIdentityReasonIsPreserved() {
        assertEquals(
            MapItemVerificationProjectionResult.InvalidIdentity(InvalidIdentityReason.BLANK_ITEM_ID),
            projector.project(MapItemDetailLoadResult.InvalidIdentity(InvalidIdentityReason.BLANK_ITEM_ID)),
        )
    }

    @Test fun malformedTypedDomainSnapshotsRemainInvalid() {
        assertUnavailable(
            found(domain(confirmationCount = -1)),
            MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE,
        )
        assertUnavailable(
            found(domain(creatorSource = null)),
            MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE,
        )
        assertUnavailable(
            found(domain(lifecycle = null)),
            MapVerificationUnavailableReason.INVALID_DOMAIN_PERSISTED_STATE,
        )
    }

    @Test fun recognizedLegacySituationKeepsUnspecifiedCreatorSourceAndOrigin() {
        val result = available(
            found(
                domain(
                    categoryOrigin = MapItemCategoryOrigin.LEGACY_KIND_DERIVED,
                    creatorSource = null,
                ),
            ),
        )
        assertEquals(MapVerificationCategoryOrigin.LEGACY_KIND_DERIVED, result.categoryOrigin)
        assertEquals(MapCreatorInformationSourcePresentation.UnspecifiedLegacy, result.creatorSource)
    }

    @Test fun markerAndAreaSameRawIdRemainTypedAndIsolated() {
        val marker = available(found(domain(), MARKER))
        val areaIdentity = MapItemIdentity("g", MapItemType.AREA, "same")
        val area = available(found(domain(), areaIdentity))

        assertEquals(MapVerificationTargetShape.MARKER, marker.targetShape)
        assertNull(marker.limitation)
        assertEquals(MapVerificationTargetShape.AREA, area.targetShape)
        assertEquals(MapVerificationLimitation.AREA_REPORTED_CONDITION_ONLY, area.limitation)
        assertFalse(marker.identity == area.identity)
    }

    @Test fun mismatchedCanonicalShapeFailsAsInvalidCanonicalState() {
        val mismatched = MapItemDetail(MARKER, canonical(MapItemIdentity("g", MapItemType.AREA, "same")), domain())
        assertUnavailable(
            MapItemDetailLoadResult.Found(mismatched),
            MapVerificationUnavailableReason.INVALID_CANONICAL_PERSISTED_STATE,
        )
    }

    @Test fun publicContractHasNoThresholdScoreOrOrderingTuple() {
        val fields = listOf(
            MapItemVerificationPresentation::class.java,
            MapVerificationEvidence.Present::class.java,
        ).flatMap { it.declaredFields.map { field -> field.name } }
        listOf("score", "percentage", "threshold", "quorum", "verified", "lamport", "order", "eventId").forEach {
            token -> assertFalse("Forbidden presentation field $token in $fields", fields.any { it.contains(token, true) })
        }

        val exposedTypes = MapItemVerificationPresentation::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .flatMap { listOf(it.returnType.name) + it.parameterTypes.map(Class<*>::getName) }
        assertFalse(exposedTypes.any { it.contains("MapActionOrderKey") || it.contains(".data.db.entity.") })
    }

    @Test fun projectorAndFactoryHaveNoRepositoryTimelineAndroidOrRoomDependencies() {
        assertTrue(DefaultMapItemVerificationProjector::class.java.declaredConstructors.single().parameterTypes.isEmpty())
        assertTrue(MapItemVerificationPresentationFactory.create() is DefaultMapItemVerificationProjector)

        val production = projectRoot().resolve("app/src/main/java/app/peermesh/map/application/verification")
            .walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf(
            "MapItemActionQueryRepository", "LoadMapItemActivity", "MapItemTimelineController",
            "SubmitMapInteraction", "RoomMapEventProcessor", ".data.db.dao.", ".data.db.entity.",
            "android.app.", "androidx.fragment", "androidx.compose", ".ble.", ".relay.",
        ).forEach { token -> assertFalse("Out-of-scope dependency $token", production.contains(token)) }
    }

    private fun available(result: MapItemDetailLoadResult): MapItemVerificationPresentation =
        (projector.project(result) as MapItemVerificationProjectionResult.Available).presentation

    private fun assertUnavailable(result: MapItemDetailLoadResult, reason: MapVerificationUnavailableReason) {
        assertEquals(MapItemVerificationProjectionResult.Unavailable(reason), projector.project(result))
    }

    private fun presentation(
        identity: MapItemIdentity = MARKER,
        targetShape: MapVerificationTargetShape = MapVerificationTargetShape.MARKER,
        categoryOrigin: MapVerificationCategoryOrigin = MapVerificationCategoryOrigin.EXPLICIT_DOMAIN_V1,
        lifecycle: MapSituationLifecycleStatus = MapSituationLifecycleStatus.ACTIVE,
        evidence: MapVerificationEvidence = MapVerificationEvidence.Absent,
        creatorSource: MapCreatorInformationSourcePresentation =
            MapCreatorInformationSourcePresentation.Known(MapInformationSource.FIRSTHAND),
        limitation: MapVerificationLimitation? = null,
    ) = MapItemVerificationPresentation(
        identity = identity,
        targetShape = targetShape,
        categoryOrigin = categoryOrigin,
        lifecycle = lifecycle,
        evidence = evidence,
        creatorSource = creatorSource,
        limitation = limitation,
    )

    private fun assertInvalidConstruction(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun found(
        domain: MapItemDomainPresentation,
        identity: MapItemIdentity = MARKER,
    ) = MapItemDetailLoadResult.Found(detail(identity, domain))

    private fun detail(
        identity: MapItemIdentity,
        domain: MapItemDomainPresentation?,
        normalizedKind: String? = "flood",
    ) = MapItemDetail(identity, canonical(identity, normalizedKind), domain)

    private fun canonical(identity: MapItemIdentity, normalizedKind: String? = "flood") =
        when (identity.itemType) {
            MapItemType.MARKER -> MarkerCanonicalPresentation(
                identity,
                normalizedKind,
                "label",
                null,
                MapItemCanonicalAvailability.ACTIVE,
                null,
                MapCoordinatesPresentation(10.0, 106.0),
                null,
            )
            MapItemType.AREA -> AreaCanonicalPresentation(
                identity,
                normalizedKind,
                "label",
                null,
                MapItemCanonicalAvailability.ACTIVE,
                null,
                MapAreaGeometrySummary.Polygon(4),
            )
        }

    private fun domain(
        category: MapItemCategory? = MapItemCategory.SITUATION,
        categoryOrigin: MapItemCategoryOrigin = MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1,
        creatorSource: MapInformationSource? = MapInformationSource.FIRSTHAND,
        lifecycle: MapSituationLifecycleStatus? = MapSituationLifecycleStatus.ACTIVE,
        confirmationCount: Int = 2,
        acknowledgementCount: Int = 3,
        freshnessBasis: MapSituationConfirmationFreshnessBasis =
            MapSituationConfirmationFreshnessBasis.Unknown,
    ) = MapItemDomainPresentation(
        category,
        categoryOrigin,
        creatorSource,
        lifecycle,
        MapItemWindows(2_000, 1_500, 2_000),
        MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1,
        confirmationCount,
        acknowledgementCount,
        freshnessBasis,
    )

    private fun projectRoot(): File {
        val current = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(current) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").exists() }
            ?: error("Android project root not found from $current")
    }

    companion object {
        private val MARKER = MapItemIdentity("g", MapItemType.MARKER, "same")
        private val AREA = MapItemIdentity("g", MapItemType.AREA, "same")
    }
}
