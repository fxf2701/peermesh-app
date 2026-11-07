package app.peermesh.map.application.detail

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.data.db.MapItemCategoryOrigin
import app.peermesh.data.db.MapItemTemporalOrigin
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.map.MapItemBatchLookupResult
import app.peermesh.data.map.MapItemDomainState
import app.peermesh.data.map.MapItemDomainStateRepository
import app.peermesh.data.map.MapItemLookupResult
import app.peermesh.data.map.MapItemWindows
import app.peermesh.data.map.MapQueryResult
import app.peermesh.data.map.MapSituationConfirmationFreshnessBasis
import app.peermesh.data.map.canonical.AreaCanonicalRead
import app.peermesh.data.map.canonical.MapCanonicalAreaGeometrySummary
import app.peermesh.data.map.canonical.MapCanonicalAvailability
import app.peermesh.data.map.canonical.MapItemCanonicalLookupResult
import app.peermesh.data.map.canonical.MapItemCanonicalReadRepository
import app.peermesh.data.map.canonical.MarkerCanonicalRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMapItemDetailComposerM3A1Test {
    @Test fun markerCanonicalProjectionIsUiSafe() {
        val result = loader(markerCanonical(), foundDomain(situation())).load(MARKER_ID) as MapItemDetailLoadResult.Found
        val canonical = result.detail.canonical as MarkerCanonicalPresentation
        assertEquals(MARKER_ID, canonical.identity)
        assertEquals(MapCoordinatesPresentation(10.5, 106.6), canonical.coordinates)
        assertEquals(25.0, canonical.radiusMeters!!, 0.0)
        assertEquals("owner note", canonical.note)
    }

    @Test fun areaCanonicalProjectionIsUiSafe() {
        val result = loader(areaCanonical(), foundDomain(situation(type = MapItemType.AREA, id = "a")))
            .load(AREA_ID) as MapItemDetailLoadResult.Found
        val canonical = result.detail.canonical as AreaCanonicalPresentation
        assertEquals(MapAreaGeometrySummary.MultiPolygon(2, 6), canonical.geometrySummary)
        assertEquals(MapItemCanonicalAvailability.ACTIVE, canonical.availability)
    }

    @Test fun recognizedSituationDomainStateIsComposedWithoutRecalculation() {
        val result = loader(markerCanonical(), foundDomain(situation())).load(MARKER_ID) as MapItemDetailLoadResult.Found
        val domain = result.detail.domain!!
        assertEquals(MapItemCategory.SITUATION, domain.category)
        assertEquals(MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1, domain.categoryOrigin)
        assertEquals(MapInformationSource.FIRSTHAND, domain.creatorInformationSource)
        assertEquals(MapSituationLifecycleStatus.ACTIVE, domain.lifecycle)
    }

    @Test fun recognizedCoordinationDomainStateKeepsLifecycleAbsent() {
        val result = loader(markerCanonical(), foundDomain(coordination())).load(MARKER_ID) as MapItemDetailLoadResult.Found
        assertEquals(MapItemCategory.COORDINATION, result.detail.domain!!.category)
        assertNull(result.detail.domain!!.lifecycle)

        val corrupt = coordination().copy(lifecycle = MapSituationLifecycleStatus.ACTIVE)
        assertTrue(loader(markerCanonical(), foundDomain(corrupt)).load(MARKER_ID) is MapItemDetailLoadResult.InvalidDomainPersistedState)
    }

    @Test fun unresolvedLegacyStateRemainsExplicit() {
        val result = loader(markerCanonical(), foundDomain(unresolved())).load(MARKER_ID)
            as MapItemDetailLoadResult.UnresolvedLegacy
        assertNull(result.detail.domain!!.category)
        assertEquals(MapItemCategoryOrigin.UNRESOLVED, result.detail.domain!!.categoryOrigin)
    }

    @Test fun legacyCreatorSourceRemainsUnspecified() {
        val legacy = situation().copy(
            categoryOrigin = MapItemCategoryOrigin.LEGACY_KIND_DERIVED,
            creatorInformationSource = null,
        )
        val result = loader(markerCanonical(), foundDomain(legacy)).load(MARKER_ID) as MapItemDetailLoadResult.Found
        assertNull(result.detail.domain!!.creatorInformationSource)
    }

    @Test fun missingDomainEnrichmentReturnsRenderableCanonicalOnlyResult() {
        val result = loader(markerCanonical(), MapQueryResult.Success(MapItemLookupResult.Missing)).load(MARKER_ID)
            as MapItemDetailLoadResult.CanonicalOnly
        assertEquals(CanonicalOnlyReason.DOMAIN_STATE_MISSING, result.reason)
        assertTrue(result.detail.canonical is MarkerCanonicalPresentation)
        assertNull(result.detail.domain)
    }

    @Test fun invalidCanonicalStateFailsSafely() {
        assertEquals(
            MapItemDetailLoadResult.InvalidCanonicalPersistedState,
            loader(MapItemCanonicalLookupResult.InvalidRecord, foundDomain(situation())).load(MARKER_ID),
        )
        assertEquals(
            MapItemDetailLoadResult.CanonicalItemMissing,
            loader(MapItemCanonicalLookupResult.Missing, foundDomain(situation())).load(MARKER_ID),
        )
    }

    @Test fun invalidDomainStateIsNotConvertedToUnresolved() {
        val invalid = MapQueryResult.Success(
            MapItemLookupResult.Invalid(PersistenceParseResult.UnknownPersistedValue("category", "future")),
        )
        val result = loader(markerCanonical(), invalid).load(MARKER_ID)
        assertTrue(result is MapItemDetailLoadResult.InvalidDomainPersistedState)
        assertTrue((result as MapItemDetailLoadResult.InvalidDomainPersistedState).canonical is MarkerCanonicalPresentation)

        val malformedUnresolved = unresolved().copy(creatorInformationSource = MapInformationSource.UNCERTAIN)
        assertTrue(loader(markerCanonical(), foundDomain(malformedUnresolved)).load(MARKER_ID) is MapItemDetailLoadResult.InvalidDomainPersistedState)
    }

    @Test fun confirmationAndAcknowledgementCountsAreCopiedExactly() {
        val freshness = MapSituationConfirmationFreshnessBasis.Current(1_234)
        val state = situation().copy(
            confirmationActorCount = 7,
            acknowledgementActorCount = 11,
            confirmationFreshnessBasis = freshness,
        )
        val domain = (loader(markerCanonical(), foundDomain(state)).load(MARKER_ID) as MapItemDetailLoadResult.Found).detail.domain!!
        assertEquals(7, domain.confirmationActorCount)
        assertEquals(11, domain.acknowledgementActorCount)
        assertEquals(freshness, domain.confirmationFreshnessBasis)
    }

    @Test fun exactCanonicalAndDomainIdentityMustMatchRequest() {
        val wrongCanonical = markerCanonical(itemId = "other")
        assertEquals(
            MapItemDetailLoadResult.InvalidCanonicalPersistedState,
            loader(wrongCanonical, foundDomain(situation())).load(MARKER_ID),
        )

        val wrongDomain = situation().copy(gid = "other-group")
        assertTrue(loader(markerCanonical(), foundDomain(wrongDomain)).load(MARKER_ID) is MapItemDetailLoadResult.InvalidDomainPersistedState)
    }

    @Test fun repositoryReceivesExactGroupTypeAndItemId() {
        val canonical = FakeCanonicalRepository(markerCanonical())
        val domain = FakeDomainRepository(foundDomain(situation()))
        DefaultMapItemDetailComposer(canonical, domain).load(MARKER_ID)
        assertEquals("g", canonical.groupId)
        assertEquals(app.peermesh.data.map.MapItemIdentity(MapItemType.MARKER, "m"), canonical.identity)
        assertEquals("g", domain.groupId)
        assertEquals(app.peermesh.data.map.MapItemIdentity(MapItemType.MARKER, "m"), domain.identity)
    }

    @Test fun technicalReadFailuresAreTyped() {
        val canonical = object : MapItemCanonicalReadRepository {
            override fun find(groupId: String, identity: app.peermesh.data.map.MapItemIdentity): MapItemCanonicalLookupResult =
                throw IllegalStateException("database unavailable")
        }
        assertEquals(
            MapItemDetailLoadResult.InternalReadFailure,
            DefaultMapItemDetailComposer(canonical, FakeDomainRepository(foundDomain(situation()))).load(MARKER_ID),
        )
    }

    @Test fun composerHasNoActionHistoryDependency() {
        val dependencyTypes = DefaultMapItemDetailComposer::class.java.declaredConstructors.single().parameterTypes.toSet()
        assertEquals(setOf(MapItemCanonicalReadRepository::class.java, MapItemDomainStateRepository::class.java), dependencyTypes)
        assertFalse(dependencyTypes.any { it.name.contains("Action") || it.name.contains("Reducer") || it.name.contains("Processor") })
    }

    @Test fun publicContractsExposeNoRoomEntities() {
        val contracts = listOf(
            MapItemIdentity::class.java,
            MapItemDetail::class.java,
            MapItemDetailLoadResult::class.java,
            MapItemCanonicalPresentation::class.java,
            MapItemDomainPresentation::class.java,
        )
        contracts.flatMap { it.declaredFields.asList() }.forEach { field ->
            assertFalse("Room entity leaked by ${field.declaringClass.name}.${field.name}",
                field.type.name.contains(".data.db.entity."))
        }
    }

    private fun loader(
        canonical: MapItemCanonicalLookupResult,
        domain: MapQueryResult<MapItemLookupResult>,
    ) = DefaultMapItemDetailComposer(FakeCanonicalRepository(canonical), FakeDomainRepository(domain))

    private fun markerCanonical(itemId: String = "m") = MapItemCanonicalLookupResult.Found(
        MarkerCanonicalRead("g", MapItemType.MARKER, itemId, "risk", "Flood", "owner note",
            MapCanonicalAvailability.ACTIVE, 2_000, 10.5, 106.6, 25.0),
    )

    private fun areaCanonical() = MapItemCanonicalLookupResult.Found(
        AreaCanonicalRead("g", MapItemType.AREA, "a", "risk", "Flood zone", "owner note",
            MapCanonicalAvailability.ACTIVE, null, MapCanonicalAreaGeometrySummary.MultiPolygon(2, 6)),
    )

    private fun foundDomain(state: MapItemDomainState): MapQueryResult<MapItemLookupResult> =
        MapQueryResult.Success(MapItemLookupResult.Found(state))

    private fun situation(type: MapItemType = MapItemType.MARKER, id: String = "m") = MapItemDomainState(
        gid = "g",
        identity = app.peermesh.data.map.MapItemIdentity(type, id),
        category = MapItemCategory.SITUATION,
        categoryOrigin = MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1,
        creatorInformationSource = MapInformationSource.FIRSTHAND,
        lifecycle = MapSituationLifecycleStatus.ACTIVE,
        windows = MapItemWindows(2_000, 1_500, 2_000),
        temporalOrigin = MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1,
        confirmationActorCount = 2,
        acknowledgementActorCount = 3,
    )

    private fun coordination() = situation().copy(
        category = MapItemCategory.COORDINATION,
        creatorInformationSource = null,
        lifecycle = null,
    )

    private fun unresolved() = situation().copy(
        category = null,
        categoryOrigin = MapItemCategoryOrigin.UNRESOLVED,
        creatorInformationSource = null,
        lifecycle = null,
        temporalOrigin = MapItemTemporalOrigin.LEGACY_UNRESOLVED,
    )

    private class FakeCanonicalRepository(private val result: MapItemCanonicalLookupResult) : MapItemCanonicalReadRepository {
        var groupId: String? = null
        var identity: app.peermesh.data.map.MapItemIdentity? = null
        override fun find(groupId: String, identity: app.peermesh.data.map.MapItemIdentity): MapItemCanonicalLookupResult {
            this.groupId = groupId
            this.identity = identity
            return result
        }
    }

    private class FakeDomainRepository(private val result: MapQueryResult<MapItemLookupResult>) : MapItemDomainStateRepository {
        var groupId: String? = null
        var identity: app.peermesh.data.map.MapItemIdentity? = null
        override fun find(gid: String, identity: app.peermesh.data.map.MapItemIdentity): MapQueryResult<MapItemLookupResult> {
            groupId = gid
            this.identity = identity
            return result
        }
        override fun findBatch(
            gid: String,
            identities: Collection<app.peermesh.data.map.MapItemIdentity>,
        ): MapQueryResult<MapItemBatchLookupResult> = error("not used")
    }

    companion object {
        private val MARKER_ID = MapItemIdentity("g", MapItemType.MARKER, "m")
        private val AREA_ID = MapItemIdentity("g", MapItemType.AREA, "a")
    }
}
