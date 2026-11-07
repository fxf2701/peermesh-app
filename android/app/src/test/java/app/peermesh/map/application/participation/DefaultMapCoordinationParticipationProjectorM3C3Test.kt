package app.peermesh.map.application.participation

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.map.MapItemWindows
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
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMapCoordinationParticipationProjectorM3C3Test {
    private val projector = DefaultMapCoordinationParticipationProjector()

    @Test fun explicitCoordinationMapsExactPositiveCountAndZeroToPresentAndAbsent() {
        assertEquals(
            MapCoordinationAcknowledgementEvidence.Present(7),
            available(found(domain(acknowledgementCount = 7))).evidence,
        )
        assertEquals(
            MapCoordinationAcknowledgementEvidence.Absent,
            available(found(domain(acknowledgementCount = 0))).evidence,
        )
    }

    @Test fun recognizedLegacyCoordinationPreservesOrigin() {
        assertEquals(
            MapCoordinationCategoryOrigin.LEGACY_KIND_DERIVED,
            available(found(domain(origin = MapItemCategoryOrigin.LEGACY_KIND_DERIVED))).categoryOrigin,
        )
    }

    @Test fun negativeCountAndUnexpectedLifecycleAreInvalidDomainState() {
        assertInvalid(found(domain(acknowledgementCount = -1)))
        assertInvalid(found(domain(lifecycle = MapSituationLifecycleStatus.ACTIVE)))
    }

    @Test fun coordinationWithoutLifecycleIsAccepted() {
        assertTrue(projector.project(found(domain(lifecycle = null))) is MapCoordinationParticipationProjectionResult.Available)
    }

    @Test fun situationIsNotApplicableAndUnresolvedLegacyRemainsExplicit() {
        assertEquals(
            MapCoordinationParticipationProjectionResult.NotApplicableToSituation,
            projector.project(found(domain(category = MapItemCategory.SITUATION,
                creatorSource = MapInformationSource.FIRSTHAND,
                lifecycle = MapSituationLifecycleStatus.ACTIVE))),
        )
        val unresolved = domain(category = null, origin = MapItemCategoryOrigin.UNRESOLVED)
        assertEquals(
            MapCoordinationParticipationProjectionResult.UnresolvedLegacyCategory,
            projector.project(MapItemDetailLoadResult.UnresolvedLegacy(detail(MARKER, unresolved))),
        )
    }

    @Test fun malformedFoundAndUnresolvedSnapshotsRemainInvalidDomainState() {
        assertInvalid(MapItemDetailLoadResult.Found(detail(MARKER, null)))
        assertInvalid(found(domain(
            category = MapItemCategory.SITUATION,
            origin = MapItemCategoryOrigin.UNRESOLVED,
            creatorSource = MapInformationSource.FIRSTHAND,
            lifecycle = MapSituationLifecycleStatus.ACTIVE,
        )))
        assertInvalid(MapItemDetailLoadResult.UnresolvedLegacy(detail(
            MARKER,
            domain(category = MapItemCategory.COORDINATION, origin = MapItemCategoryOrigin.UNRESOLVED),
        )))
        assertInvalid(MapItemDetailLoadResult.UnresolvedLegacy(detail(
            MARKER,
            domain(category = null, origin = MapItemCategoryOrigin.UNRESOLVED).copy(confirmationActorCount = -1),
        )))
        assertInvalid(MapItemDetailLoadResult.UnresolvedLegacy(detail(
            MARKER,
            domain(category = null, origin = MapItemCategoryOrigin.UNRESOLVED).copy(acknowledgementActorCount = -1),
        )))
    }

    @Test fun unavailableInvalidAndInternalDetailResultsRemainDistinct() {
        assertEquals(
            MapCoordinationParticipationProjectionResult.DomainStateUnavailable,
            projector.project(MapItemDetailLoadResult.CanonicalOnly(detail(MARKER, null), CanonicalOnlyReason.DOMAIN_STATE_MISSING)),
        )
        assertEquals(MapCoordinationParticipationProjectionResult.TargetUnavailable,
            projector.project(MapItemDetailLoadResult.CanonicalItemMissing))
        assertEquals(MapCoordinationParticipationProjectionResult.InvalidCanonicalPersistedState,
            projector.project(MapItemDetailLoadResult.InvalidCanonicalPersistedState))
        assertEquals(MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState,
            projector.project(MapItemDetailLoadResult.InvalidDomainPersistedState(canonical(MARKER))))
        assertEquals(MapCoordinationParticipationProjectionResult.InternalProjectionFailure,
            projector.project(MapItemDetailLoadResult.InternalReadFailure))
        assertEquals(
            MapCoordinationParticipationProjectionResult.InvalidIdentity(InvalidIdentityReason.BLANK_ITEM_ID),
            projector.project(MapItemDetailLoadResult.InvalidIdentity(InvalidIdentityReason.BLANK_ITEM_ID)),
        )
    }

    @Test fun markerAndAreaWithSameRawIdPreserveExactIdentityAndShape() {
        val marker = available(found(domain(), MARKER))
        val area = available(found(domain(), AREA))
        assertEquals(MARKER, marker.identity)
        assertEquals(AREA, area.identity)
        assertEquals(MapCoordinationTargetShape.MARKER, marker.targetShape)
        assertEquals(MapCoordinationTargetShape.AREA, area.targetShape)
        assertFalse(marker.identity == area.identity)
    }

    @Test fun mismatchedCanonicalIdentityIsInvalidCanonicalState() {
        val malformed = MapItemDetail(MARKER, canonical(AREA), domain())
        assertEquals(MapCoordinationParticipationProjectionResult.InvalidCanonicalPersistedState,
            projector.project(MapItemDetailLoadResult.Found(malformed)))
    }

    @Test fun contractsContainNoFreshnessVerificationTrustThresholdOrPersistenceTypes() {
        val names = listOf(
            MapCoordinationParticipationPresentation::class.java,
            MapCoordinationAcknowledgementEvidence.Present::class.java,
        ).flatMap { type -> type.declaredFields.map { it.name } + type.declaredMethods.map { it.name } }
        listOf("freshness", "verification", "trust", "score", "threshold", "quorum", "timeline", "room")
            .forEach { token -> assertFalse("Forbidden semantic $token in $names", names.any { it.contains(token, true) }) }
        val exposedTypes = MapCoordinationParticipationPresentation::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .flatMap { listOf(it.returnType.name) + it.parameterTypes.map(Class<*>::getName) }
        assertFalse(exposedTypes.any { it.contains(".data.db.") || it.contains("transport", true) })
    }

    @Test fun projectorHasOnlyAllowedBoundaryDependencies() {
        assertTrue(DefaultMapCoordinationParticipationProjector::class.java.declaredConstructors.single().parameterTypes.isEmpty())
        assertTrue(MapCoordinationParticipationPresentationFactory.create() is DefaultMapCoordinationParticipationProjector)
        assertEquals(
            MapItemCategoryOrigin::class.java,
            MapItemDomainPresentation::class.java.getMethod("getCategoryOrigin").returnType,
        )
        assertPublicBoundaryIsClean(
            DefaultMapCoordinationParticipationProjector::class.java,
            ProjectMapCoordinationParticipation::class.java,
            MapCoordinationParticipationProjectionResult::class.java,
            MapCoordinationParticipationProjectionResult.Available::class.java,
            MapCoordinationParticipationProjectionResult.InvalidIdentity::class.java,
            MapCoordinationParticipationPresentation::class.java,
            MapCoordinationAcknowledgementEvidence.Present::class.java,
        )
        assertImplementationHasNoForbiddenDependency(DefaultMapCoordinationParticipationProjector::class.java)
    }

    private fun assertPublicBoundaryIsClean(vararg contracts: Class<*>) {
        val exposed = contracts.flatMap { contract ->
            contract.interfaces.toList() +
                contract.declaredConstructors.flatMap { it.parameterTypes.toList() } +
                contract.declaredFields.flatMap { listOf(it.type) } +
                contract.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.flatMap {
                    listOf(it.returnType) + it.parameterTypes
                }
        }.map(Class<*>::getName)
        forbiddenTypeTokens.forEach { token ->
            assertFalse("Forbidden public dependency $token in $exposed", exposed.any { it.contains(token) })
        }
    }

    private fun assertImplementationHasNoForbiddenDependency(type: Class<*>) {
        val path = "/${type.name.replace('.', '/')}.class"
        val bytecode = checkNotNull(type.getResourceAsStream(path), { "Missing class bytes for ${type.name}" })
            .use { String(it.readBytes(), Charsets.ISO_8859_1) }
        forbiddenImplementationTokens.forEach { token ->
            assertFalse("Forbidden implementation dependency $token", bytecode.contains(token))
        }
    }

    private fun available(result: MapItemDetailLoadResult) =
        (projector.project(result) as MapCoordinationParticipationProjectionResult.Available).presentation

    private fun assertInvalid(result: MapItemDetailLoadResult) = assertEquals(
        MapCoordinationParticipationProjectionResult.InvalidDomainPersistedState,
        projector.project(result),
    )

    private fun found(domain: MapItemDomainPresentation, identity: MapItemIdentity = MARKER) =
        MapItemDetailLoadResult.Found(detail(identity, domain))

    private fun detail(identity: MapItemIdentity, domain: MapItemDomainPresentation?) =
        MapItemDetail(identity, canonical(identity), domain)

    private fun canonical(identity: MapItemIdentity) = when (identity.itemType) {
        MapItemType.MARKER -> MarkerCanonicalPresentation(identity, "coordination", "label", null,
            MapItemCanonicalAvailability.ACTIVE, null, MapCoordinatesPresentation(10.0, 106.0), null)
        MapItemType.AREA -> AreaCanonicalPresentation(identity, "coordination", "label", null,
            MapItemCanonicalAvailability.ACTIVE, null, MapAreaGeometrySummary.Polygon(4))
    }

    private fun domain(
        category: MapItemCategory? = MapItemCategory.COORDINATION,
        origin: MapItemCategoryOrigin = MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1,
        creatorSource: MapInformationSource? = null,
        lifecycle: MapSituationLifecycleStatus? = null,
        acknowledgementCount: Int = 3,
    ) = MapItemDomainPresentation(category, origin, creatorSource, lifecycle,
        MapItemWindows(2_000, 1_500, 2_000), MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1,
        confirmationActorCount = 0, acknowledgementActorCount = acknowledgementCount)

    companion object {
        private val forbiddenTypeTokens = listOf(
            ".data.db.dao.", ".data.db.entity.", "Repository", "androidx.room", "android.app",
            "androidx.lifecycle", "android.app.Activity", "androidx.fragment", "androidx.compose", ".submission.",
            "MapItemTimelineController", "MapItemTimelineState", "LoadMapItemActivity", "Cursor",
            ".ble.", ".relay.", ".transport.",
        )
        private val forbiddenImplementationTokens = listOf(
            "app/peermesh/data/db/dao/", "app/peermesh/data/db/entity/", "Repository",
            "androidx/room/", "android/app/", "androidx/lifecycle/", "androidx/fragment/",
            "androidx/compose/", "map/application/submission/", "MapItemTimelineController",
            "MapItemTimelineState", "LoadMapItemActivity", "MapItemActivityCursor", "/ble/", "/relay/",
            "/transport/",
        )
        private val MARKER = MapItemIdentity("group", MapItemType.MARKER, "same")
        private val AREA = MapItemIdentity("group", MapItemType.AREA, "same")
    }
}
