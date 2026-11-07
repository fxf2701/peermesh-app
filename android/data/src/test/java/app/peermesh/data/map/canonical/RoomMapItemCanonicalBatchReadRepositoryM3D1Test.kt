package app.peermesh.data.map.canonical

import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.dao.MapDao
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import app.peermesh.data.map.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class RoomMapItemCanonicalBatchReadRepositoryM3D1Test {
    @Test fun mixedRequestIsDeduplicatedChunkedAndReconstructedByExactIdentity() {
        val dao = mock(MapDao::class.java)
        val markerCalls = mutableListOf<List<String>>()
        val areaCalls = mutableListOf<List<String>>()
        doAnswer { invocation ->
            (invocation.arguments[1] as List<String>).also(markerCalls::add)
                .map { marker(it) }.reversed()
        }.`when`(dao).findMarkersByIds(anyString(), anyList())
        doAnswer { invocation ->
            (invocation.arguments[1] as List<String>).also(areaCalls::add)
                .map { area(it) }.reversed()
        }.`when`(dao).findAreasByIds(anyString(), anyList())
        val repository = RoomMapItemCanonicalBatchReadRepository(dao, chunkSize = 2)
        val m1 = MapItemIdentity(MapItemType.MARKER, "same")
        val a1 = MapItemIdentity(MapItemType.AREA, "same")
        val m2 = MapItemIdentity(MapItemType.MARKER, "m2")
        val m3 = MapItemIdentity(MapItemType.MARKER, "m3")

        val result = repository.findBatch("g", listOf(m1, a1, m2, m1, m3))

        assertEquals(listOf(m1, a1, m2, m3), result.found.keys.toList())
        assertEquals(listOf(listOf("same", "m2"), listOf("m3")), markerCalls)
        assertEquals(listOf(listOf("same")), areaCalls)
        assertTrue(result.missing.isEmpty())
        assertTrue(result.invalid.isEmpty())
        val geometry = (result.found.getValue(a1) as AreaCanonicalRead).geometry
            as MapCanonicalAreaGeometry.Polygon
        assertEquals(
            listOf(
                MapCanonicalCoordinate(10.0, 106.0),
                MapCanonicalCoordinate(10.0, 107.0),
                MapCanonicalCoordinate(11.0, 107.0),
                MapCanonicalCoordinate(10.0, 106.0),
            ),
            geometry.rings.single(),
        )
        verify(dao, never()).findMarker(anyString(), anyString())
        verify(dao, never()).findArea(anyString(), anyString())
    }

    @Test fun missingAndMalformedRowsAreIsolated() {
        val dao = mock(MapDao::class.java)
        doAnswer { listOf(marker("ok"), marker("bad").copy(lat = Double.NaN)) }
            .`when`(dao).findMarkersByIds(anyString(), anyList())
        val repository = RoomMapItemCanonicalBatchReadRepository(dao)
        val ok = MapItemIdentity(MapItemType.MARKER, "ok")
        val bad = MapItemIdentity(MapItemType.MARKER, "bad")
        val missing = MapItemIdentity(MapItemType.MARKER, "missing")

        val result = repository.findBatch("g", listOf(ok, bad, missing))

        assertEquals(setOf(ok), result.found.keys)
        assertEquals(setOf(bad), result.invalid)
        assertEquals(setOf(missing), result.missing)
    }

    @Test fun duplicateRowsAreInvalidAndMalformedSiblingsDoNotSuppressOtherTypes() {
        val dao = mock(MapDao::class.java)
        doAnswer { listOf(marker("duplicate"), marker("duplicate"), marker("bad").copy(lat = Double.NaN)) }
            .`when`(dao).findMarkersByIds(anyString(), anyList())
        doAnswer { listOf(area("good-area")) }
            .`when`(dao).findAreasByIds(anyString(), anyList())
        val duplicate = MapItemIdentity(MapItemType.MARKER, "duplicate")
        val bad = MapItemIdentity(MapItemType.MARKER, "bad")
        val goodArea = MapItemIdentity(MapItemType.AREA, "good-area")

        val result = RoomMapItemCanonicalBatchReadRepository(dao).findBatch("g", listOf(duplicate, bad, goodArea))

        assertEquals(setOf(duplicate, bad), result.invalid)
        assertEquals(setOf(goodArea), result.found.keys)
    }

    @Test fun chunkBoundariesArePerTypeAndNeverFallBackToSingleReads() {
        listOf(1, 2, 3).forEach { count ->
            val dao = mock(MapDao::class.java)
            var markerCalls = 0
            doAnswer { invocation ->
                markerCalls++
                (invocation.arguments[1] as List<String>).map(::marker)
            }.`when`(dao).findMarkersByIds(anyString(), anyList())
            val identities = (0 until count).map { MapItemIdentity(MapItemType.MARKER, "m-$it") }

            val result = RoomMapItemCanonicalBatchReadRepository(dao, chunkSize = 2).findBatch("g", identities)

            assertEquals(count, result.found.size)
            assertEquals((count + 1) / 2, markerCalls)
            verify(dao, never()).findMarker(anyString(), anyString())
        }
    }

    @Test fun unrequestedRowOrOrdinaryDaoExceptionFailsBatchButFatalErrorPropagates() {
        val requested = MapItemIdentity(MapItemType.MARKER, "m")
        val badDao = mock(MapDao::class.java)
        doAnswer { listOf(marker("other")) }.`when`(badDao).findMarkersByIds(anyString(), anyList())
        assertTrue(RoomMapItemCanonicalBatchReadRepository(badDao).findBatch("g", listOf(requested)).readFailure)

        val throwingDao = mock(MapDao::class.java)
        doAnswer { throw IllegalStateException("db") }.`when`(throwingDao).findMarkersByIds(anyString(), anyList())
        assertTrue(RoomMapItemCanonicalBatchReadRepository(throwingDao).findBatch("g", listOf(requested)).readFailure)

        val fatalDao = mock(MapDao::class.java)
        doAnswer { throw AssertionError("fatal") }.`when`(fatalDao).findMarkersByIds(anyString(), anyList())
        var propagated = false
        try {
            RoomMapItemCanonicalBatchReadRepository(fatalDao).findBatch("g", listOf(requested))
        } catch (_: AssertionError) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test fun crossGroupAndMalformedAreaRowsAreInvalidPerRequestedIdentity() {
        val dao = mock(MapDao::class.java)
        doAnswer { listOf(marker("m").copy(gid = "other")) }
            .`when`(dao).findMarkersByIds(anyString(), anyList())
        doAnswer { listOf(area("a").copy(geometry_json = "not-json")) }
            .`when`(dao).findAreasByIds(anyString(), anyList())
        val marker = MapItemIdentity(MapItemType.MARKER, "m")
        val area = MapItemIdentity(MapItemType.AREA, "a")

        val result = RoomMapItemCanonicalBatchReadRepository(dao).findBatch("g", listOf(marker, area))

        assertEquals(setOf(marker, area), result.invalid)
        assertTrue(result.found.isEmpty())
        assertFalse(result.readFailure)
    }

    @Test fun emptyRequestDoesNotQueryDao() {
        val dao = mock(MapDao::class.java)
        val result = RoomMapItemCanonicalBatchReadRepository(dao).findBatch("g", emptyList())
        assertFalse(result.readFailure)
        assertTrue(result.found.isEmpty())
        verify(dao, never()).findMarkersByIds(anyString(), anyList())
        verify(dao, never()).findAreasByIds(anyString(), anyList())
    }

    private fun marker(id: String) = MapMarkerEntity(
        "g", id, "layer", 10.0, 106.0, 20.0, "label", "note", "risk", 1, "active",
        1, 2, "did:o", null, null, null, null, 2_000, "event", 2, 1, "did:o",
    )

    private fun area(id: String) = MapAreaEntity(
        "g", id, "layer", POLYGON, "label", "note", "risk", 1, "active",
        1, 2, "did:o", null, null, null, null, 2_000, "event", 2, 1, "did:o",
    )

    private companion object {
        const val POLYGON =
            """{"type":"Polygon","coordinates":[[[106.0,10.0],[107.0,10.0],[107.0,11.0],[106.0,10.0]]]}"""
    }
}
