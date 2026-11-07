package app.peermesh.map.application.scene

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
import app.peermesh.data.map.MapItemIdentity as DataIdentity
import app.peermesh.data.map.MapItemLookupResult
import app.peermesh.data.map.MapItemWindows
import app.peermesh.data.map.MapQueryResult
import app.peermesh.data.map.canonical.AreaCanonicalRead
import app.peermesh.data.map.canonical.MapCanonicalAreaGeometry
import app.peermesh.data.map.canonical.MapCanonicalAreaGeometrySummary
import app.peermesh.data.map.canonical.MapCanonicalAvailability
import app.peermesh.data.map.canonical.MapCanonicalCoordinate
import app.peermesh.data.map.canonical.MapItemCanonicalBatchLookupResult
import app.peermesh.data.map.canonical.MapItemCanonicalBatchReadRepository
import app.peermesh.data.map.canonical.MarkerCanonicalRead
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.interaction.MapAvailabilityClock
import app.peermesh.map.application.participation.DefaultMapCoordinationParticipationProjector
import app.peermesh.map.application.verification.DefaultMapItemVerificationProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultLoadMapSceneItemsM3D1Test {
    @Test fun invalidAndEmptyRequestsDoNoWork() {
        val fixture = Fixture()
        assertEquals(
            MapSceneLoadResult.InvalidRequest(MapSceneInvalidRequestReason.BlankGroupId),
            fixture.loader.load("", emptyList()),
        )
        assertEquals(
            MapSceneLoadResult.InvalidRequest(MapSceneInvalidRequestReason.GroupMismatch),
            fixture.loader.load("g", listOf(id("m").copy(groupId = "other"))),
        )
        assertEquals(MapSceneLoadResult.Success(emptyList()), fixture.loader.load("g", emptyList()))
        assertEquals(0, fixture.canonical.calls)
        assertEquals(0, fixture.domain.calls)
        assertEquals(0, fixture.clock.calls)
    }

    @Test fun mixedShapesPreserveFirstOccurrenceOrderAndExactSameRawIdIdentity() {
        val marker = id("same")
        val area = id("same", MapItemType.AREA)
        val fixture = Fixture(
            canonicalResult = canonicalFound(marker to markerCanonical("same"), area to areaCanonical("same")),
            domainResult = domainFound(marker to situation("same"), area to coordination("same", MapItemType.AREA)),
        )

        val result = success(fixture.loader.load("g", listOf(area, marker, area)))

        assertEquals(listOf(area, marker), result.map(MapSceneItemLoadResult::identity))
        assertTrue((result[0] as MapSceneItemLoadResult.Found).item.geometry is MapSceneGeometry.Area)
        assertTrue((result[1] as MapSceneItemLoadResult.Found).item.geometry is MapSceneGeometry.Marker)
        assertEquals(1, fixture.canonical.calls)
        assertEquals(1, fixture.domain.calls)
        assertEquals(1, fixture.clock.calls)
    }

    @Test fun boundedRequestPolicyRejectsRawAndUniqueOversizeWithoutDependencies() {
        val atLimit = (0 until DefaultLoadMapSceneItems.MAX_SCENE_ITEM_IDENTITIES).map { id("m-$it") }
        val accepted = Fixture(
            canonicalResult = MapItemCanonicalBatchLookupResult(emptyMap(), atLimit.mapTo(linkedSetOf()) { it.data }, emptySet()),
            domainResult = MapQueryResult.Success(
                MapItemBatchLookupResult(emptyMap(), atLimit.mapTo(linkedSetOf()) { it.data }, emptyMap()),
            ),
        )
        assertTrue(accepted.loader.load("g", atLimit) is MapSceneLoadResult.Success)
        assertEquals(1, accepted.canonical.calls)

        val uniqueOversize = Fixture()
        val tooManyUnique = (0..DefaultLoadMapSceneItems.MAX_SCENE_ITEM_IDENTITIES).map { id("m-$it") }
        assertEquals(
            MapSceneLoadResult.InvalidRequest(MapSceneInvalidRequestReason.TooManyIdentities),
            uniqueOversize.loader.load("g", tooManyUnique),
        )
        assertEquals(0, uniqueOversize.canonical.calls)
        assertEquals(0, uniqueOversize.domain.calls)
        assertEquals(0, uniqueOversize.clock.calls)

        val rawOversize = Fixture()
        val duplicates = List(DefaultLoadMapSceneItems.MAX_RAW_SCENE_ITEM_IDENTITIES + 1) { id("same") }
        assertEquals(
            MapSceneLoadResult.InvalidRequest(MapSceneInvalidRequestReason.TooManyIdentities),
            rawOversize.loader.load("g", duplicates),
        )
        assertEquals(0, rawOversize.canonical.calls)
        assertEquals(0, rawOversize.domain.calls)
        assertEquals(0, rawOversize.clock.calls)
    }

    @Test fun perItemOutcomesAreIndependentAndDomainAuthorityIsNotGuessed() {
        val found = id("found")
        val canonicalOnly = id("canonical-only")
        val invalidCanonical = id("bad-canonical")
        val invalidDomain = id("bad-domain")
        val domainOnly = id("domain-only")
        val absent = id("absent")
        val unresolved = id("legacy")
        val canonical = MapItemCanonicalBatchLookupResult(
            found = mapOf(
                found.data to markerCanonical("found"),
                canonicalOnly.data to markerCanonical("canonical-only"),
                invalidDomain.data to markerCanonical("bad-domain"),
                unresolved.data to markerCanonical("legacy"),
            ),
            missing = setOf(domainOnly.data, absent.data),
            invalid = setOf(invalidCanonical.data),
        )
        val domain = MapItemBatchLookupResult(
            found = mapOf(
                found.data to situation("found"),
                domainOnly.data to situation("domain-only"),
                unresolved.data to unresolved("legacy"),
            ),
            missing = setOf(canonicalOnly.data, invalidCanonical.data, absent.data),
            invalid = mapOf(invalidDomain.data to failure()),
        )
        val fixture = Fixture(canonical, MapQueryResult.Success(domain))

        val result = success(
            fixture.loader.load(
                "g",
                listOf(found, canonicalOnly, invalidCanonical, invalidDomain, domainOnly, absent, unresolved),
            ),
        )

        assertTrue(result[0] is MapSceneItemLoadResult.Found)
        assertTrue(result[1] is MapSceneItemLoadResult.CanonicalOnly)
        assertTrue(result[2] is MapSceneItemLoadResult.InvalidCanonical)
        assertTrue(result[3] is MapSceneItemLoadResult.InvalidDomain)
        assertTrue(result[4] is MapSceneItemLoadResult.InvalidDomain)
        assertTrue(result[5] is MapSceneItemLoadResult.Missing)
        assertTrue(result[6] is MapSceneItemLoadResult.UnresolvedLegacy)
    }

    @Test fun invalidLifecycleCategoryAndCountsFailAsInvalidDomain() {
        val coordinationLifecycle = id("coord-life")
        val situationNoLifecycle = id("sit-life")
        val negativeCount = id("negative")
        val identities = listOf(coordinationLifecycle, situationNoLifecycle, negativeCount)
        val fixture = Fixture(
            canonicalResult = canonicalFound(*identities.map { it to markerCanonical(it.itemId) }.toTypedArray()),
            domainResult = domainFound(
                coordinationLifecycle to coordination("coord-life").copy(lifecycle = MapSituationLifecycleStatus.ACTIVE),
                situationNoLifecycle to situation("sit-life").copy(lifecycle = null),
                negativeCount to situation("negative").copy(confirmationActorCount = -1),
            ),
        )

        val result = success(fixture.loader.load("g", identities))

        assertTrue(result.all { it is MapSceneItemLoadResult.InvalidDomain })
    }

    @Test fun identityMismatchesUseCoarsePerItemFailuresWithoutAffectingValidSibling() {
        val badCanonical = id("bad-canonical")
        val badDomain = id("bad-domain")
        val valid = id("valid")
        val fixture = Fixture(
            canonicalResult = canonicalFound(
                badCanonical to markerCanonical("wrong-canonical-id"),
                badDomain to markerCanonical("bad-domain"),
                valid to markerCanonical("valid"),
            ),
            domainResult = domainFound(
                badCanonical to situation("bad-canonical"),
                badDomain to situation("wrong-domain-id"),
                valid to situation("valid"),
            ),
        )

        val result = success(fixture.loader.load("g", listOf(badCanonical, badDomain, valid)))

        assertTrue(result[0] is MapSceneItemLoadResult.InvalidCanonical)
        assertTrue(result[1] is MapSceneItemLoadResult.InvalidDomain)
        assertTrue(result[2] is MapSceneItemLoadResult.Found)
    }

    @Test fun repositoryContractViolationsAndConflictingBucketsFailSafely() {
        val requested = id("m")
        val unrequested = DataIdentity(MapItemType.MARKER, "other")
        val fixture = Fixture(
            canonicalResult = MapItemCanonicalBatchLookupResult(
                found = mapOf(unrequested to markerCanonical("other")),
                missing = setOf(requested.data),
                invalid = emptySet(),
            ),
        )
        assertEquals(
            MapSceneLoadResult.InternalFailure(MapSceneLoadFailure.InvalidRepositoryResult),
            fixture.loader.load("g", listOf(requested)),
        )

        val conflict = Fixture(
            canonicalResult = MapItemCanonicalBatchLookupResult(
                found = mapOf(requested.data to markerCanonical("m")),
                missing = setOf(requested.data),
                invalid = emptySet(),
            ),
            domainResult = domainFound(requested to situation()),
        )
        assertTrue(success(conflict.loader.load("g", listOf(requested))).single() is MapSceneItemLoadResult.InvalidCanonical)
    }

    @Test fun batchFailuresPreserveProvenanceAndFatalErrorsPropagate() {
        val requested = id("m")
        assertEquals(
            MapSceneLoadResult.InternalFailure(MapSceneLoadFailure.CanonicalReadUnavailable),
            Fixture(canonicalException = IllegalStateException("canonical")).loader.load("g", listOf(requested)),
        )
        assertEquals(
            MapSceneLoadResult.InternalFailure(MapSceneLoadFailure.DomainStateReadUnavailable),
            Fixture(domainException = IllegalStateException("domain")).loader.load("g", listOf(requested)),
        )
        assertEquals(
            MapSceneLoadResult.InternalFailure(MapSceneLoadFailure.ClockUnavailable),
            Fixture(clockException = IllegalStateException("clock")).loader.load("g", listOf(requested)),
        )
        assertEquals(
            MapSceneLoadResult.InternalFailure(MapSceneLoadFailure.CanonicalReadUnavailable),
            Fixture(canonicalResult = MapItemCanonicalBatchLookupResult(emptyMap(), emptySet(), emptySet(), readFailure = true))
                .loader.load("g", listOf(requested)),
        )
        assertEquals(
            MapSceneLoadResult.InternalFailure(MapSceneLoadFailure.DomainStateReadUnavailable),
            Fixture(domainResult = MapQueryResult.InvalidInput(failure())).loader.load("g", listOf(requested)),
        )
        val unrequestedDomain = Fixture(
            domainResult = MapQueryResult.Success(
                MapItemBatchLookupResult(
                    found = mapOf(DataIdentity(MapItemType.MARKER, "other") to situation("other")),
                    missing = setOf(requested.data),
                    invalid = emptyMap(),
                ),
            ),
        )
        assertEquals(
            MapSceneLoadResult.InternalFailure(MapSceneLoadFailure.InvalidRepositoryResult),
            unrequestedDomain.loader.load("g", listOf(requested)),
        )
        listOf("canonical", "domain", "clock").forEach { target ->
            var propagated = false
            try {
                Fixture(
                    canonicalException = if (target == "canonical") AssertionError(target) else null,
                    domainException = if (target == "domain") AssertionError(target) else null,
                    clockException = if (target == "clock") AssertionError(target) else null,
                ).loader.load("g", listOf(requested))
            } catch (_: AssertionError) {
                propagated = true
            }
            assertTrue("$target fatal error", propagated)
        }
    }

    @Test fun oneClockSnapshotDrivesTemporalPresentationForEveryItem() {
        val expired = id("expired")
        val closed = id("closed")
        val fixture = Fixture(
            canonicalResult = canonicalFound(
                expired to markerCanonical("expired"),
                closed to markerCanonical("closed"),
            ),
            domainResult = domainFound(
                expired to situation("expired").copy(windows = MapItemWindows(100, 50, 100)),
                closed to situation("closed").copy(windows = MapItemWindows(300, 50, 100)),
            ),
            now = 100,
        )
        val result = success(fixture.loader.load("g", listOf(expired, closed)))
            .map { (it as MapSceneItemLoadResult.Found).item.temporal }
        assertEquals(listOf(MapSceneTemporalPresentation.Expired, MapSceneTemporalPresentation.InteractionClosed), result)
        assertEquals(1, fixture.clock.calls)
    }

    @Test fun temporalPrecedenceKeepsDeletedAndResolvedDistinct() {
        val deleted = id("deleted")
        val resolved = id("resolved")
        val fixture = Fixture(
            canonicalResult = canonicalFound(
                deleted to markerCanonical("deleted", MapCanonicalAvailability.DELETED),
                resolved to markerCanonical("resolved"),
            ),
            domainResult = domainFound(
                deleted to unresolved("deleted").copy(windows = MapItemWindows(1, 1, 1)),
                resolved to situation("resolved").copy(
                    lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
                    windows = MapItemWindows(1, 1, 1),
                ),
            ),
            now = 1,
        )

        val temporal = success(fixture.loader.load("g", listOf(deleted, resolved)))
            .map {
                when (it) {
                    is MapSceneItemLoadResult.Found -> it.item.temporal
                    is MapSceneItemLoadResult.UnresolvedLegacy -> it.item.temporal
                    else -> error("expected projected item")
                }
            }

        assertEquals(listOf(MapSceneTemporalPresentation.Deleted, MapSceneTemporalPresentation.Resolved), temporal)
    }

    private class Fixture(
        canonicalResult: MapItemCanonicalBatchLookupResult = canonicalFound(id("m") to markerCanonical()),
        domainResult: MapQueryResult<MapItemBatchLookupResult> = domainFound(id("m") to situation()),
        canonicalException: Throwable? = null,
        domainException: Throwable? = null,
        clockException: Throwable? = null,
        now: Long = 1_000,
    ) {
        val canonical = FakeCanonical(canonicalResult, canonicalException)
        val domain = FakeDomain(domainResult, domainException)
        val clock = FakeClock(now, clockException)
        val loader = DefaultLoadMapSceneItems(
            canonical,
            domain,
            clock,
            DefaultMapItemVerificationProjector(),
            DefaultMapCoordinationParticipationProjector(),
        )
    }

    private class FakeCanonical(
        private val result: MapItemCanonicalBatchLookupResult,
        private val failure: Throwable?,
    ) : MapItemCanonicalBatchReadRepository {
        var calls = 0
        override fun findBatch(groupId: String, identities: Collection<DataIdentity>): MapItemCanonicalBatchLookupResult {
            calls++
            failure?.let { throw it }
            return result
        }
    }

    private class FakeDomain(
        private val result: MapQueryResult<MapItemBatchLookupResult>,
        private val failure: Throwable?,
    ) : MapItemDomainStateRepository {
        var calls = 0
        override fun findBatch(gid: String, identities: Collection<DataIdentity>): MapQueryResult<MapItemBatchLookupResult> {
            calls++
            failure?.let { throw it }
            return result
        }
        override fun find(gid: String, identity: DataIdentity): MapQueryResult<MapItemLookupResult> =
            error("single-item fallback is forbidden")
    }

    private class FakeClock(
        private val now: Long,
        private val failure: Throwable?,
    ) : MapAvailabilityClock {
        var calls = 0
        override fun nowSeconds(): Long {
            calls++
            failure?.let { throw it }
            return now
        }
    }

    private companion object {
        fun id(value: String, type: MapItemType = MapItemType.MARKER) = MapItemIdentity("g", type, value)
        val MapItemIdentity.data get() = DataIdentity(itemType, itemId)

        fun markerCanonical(
            value: String = "m",
            availability: MapCanonicalAvailability = MapCanonicalAvailability.ACTIVE,
        ) = MarkerCanonicalRead(
            "g", MapItemType.MARKER, value, "risk", "label", "note",
            availability, 2_000, 10.0, 106.0, 25.0,
        )

        fun areaCanonical(value: String) = AreaCanonicalRead(
            "g", MapItemType.AREA, value, "risk", "area", "note",
            MapCanonicalAvailability.ACTIVE, 2_000,
            MapCanonicalAreaGeometrySummary.Polygon(3),
            MapCanonicalAreaGeometry.Polygon(
                listOf(
                    listOf(
                        MapCanonicalCoordinate(10.0, 106.0),
                        MapCanonicalCoordinate(10.0, 107.0),
                        MapCanonicalCoordinate(11.0, 107.0),
                    ),
                ),
            ),
        )

        fun situation(value: String = "m") = MapItemDomainState(
            "g", DataIdentity(MapItemType.MARKER, value), MapItemCategory.SITUATION,
            MapItemCategoryOrigin.EXPLICIT_DOMAIN_V1, MapInformationSource.FIRSTHAND,
            MapSituationLifecycleStatus.ACTIVE, MapItemWindows(2_000, 1_500, 2_000),
            MapItemTemporalOrigin.EXPLICIT_DOMAIN_V1, 2, 0,
        )

        fun coordination(value: String, type: MapItemType = MapItemType.MARKER) = situation(value).copy(
            identity = DataIdentity(type, value),
            category = MapItemCategory.COORDINATION,
            creatorInformationSource = null,
            lifecycle = null,
            confirmationActorCount = 0,
            acknowledgementActorCount = 3,
        )

        fun unresolved(value: String) = situation(value).copy(
            category = null,
            categoryOrigin = MapItemCategoryOrigin.UNRESOLVED,
            creatorInformationSource = null,
            lifecycle = null,
            temporalOrigin = MapItemTemporalOrigin.LEGACY_UNRESOLVED,
        )

        fun canonicalFound(vararg values: Pair<MapItemIdentity, app.peermesh.data.map.canonical.MapItemCanonicalRead>) =
            MapItemCanonicalBatchLookupResult(
                found = values.associate { it.first.data to it.second },
                missing = emptySet(),
                invalid = emptySet(),
            )

        fun domainFound(vararg values: Pair<MapItemIdentity, MapItemDomainState>): MapQueryResult<MapItemBatchLookupResult> =
            MapQueryResult.Success(
                MapItemBatchLookupResult(
                    found = values.associate { it.first.data to it.second },
                    missing = emptySet(),
                    invalid = emptyMap(),
                ),
            )

        fun failure(): PersistenceParseResult<Nothing> =
            PersistenceParseResult.InvalidRecordShape("invalid")

        fun success(result: MapSceneLoadResult): List<MapSceneItemLoadResult> =
            (result as MapSceneLoadResult.Success).items
    }
}
