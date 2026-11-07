package app.peermesh.data.map.canonical

import app.peermesh.data.db.entity.MapAreaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemCanonicalGeometryM3D1Test {
    @Test fun closedRingsArePreservedWithoutNormalization() {
        val item = found(POLYGON)
        val geometry = item.geometry as MapCanonicalAreaGeometry.Polygon

        assertEquals(
            listOf(
                MapCanonicalCoordinate(10.0, 106.0),
                MapCanonicalCoordinate(10.0, 107.0),
                MapCanonicalCoordinate(11.0, 107.0),
                MapCanonicalCoordinate(10.0, 106.0),
            ),
            geometry.rings.single(),
        )
        assertEquals(4, geometry.rings.single().size)
        assertEquals(MapCanonicalAreaGeometrySummary.Polygon(3), item.geometrySummary)
        assertEquals(4, geometry.rings.single().size)
    }

    @Test fun polygonHoleCountsGeometricVerticesWithoutMutatingClosedRings() {
        val item = found(POLYGON_WITH_HOLE)
        val geometry = item.geometry as MapCanonicalAreaGeometry.Polygon

        assertEquals(MapCanonicalAreaGeometrySummary.Polygon(6), item.geometrySummary)
        assertEquals(listOf(4, 4), geometry.rings.map(List<MapCanonicalCoordinate>::size))
        assertTrue(geometry.rings.all { it.first() == it.last() })
    }

    @Test fun twoTriangleMultipolygonCountsPolygonsAndGeometricVertices() {
        val item = found(TWO_TRIANGLE_MULTIPOLYGON)
        val geometry = item.geometry as MapCanonicalAreaGeometry.MultiPolygon

        assertEquals(MapCanonicalAreaGeometrySummary.MultiPolygon(2, 6), item.geometrySummary)
        assertEquals(listOf(4, 4), geometry.polygons.map { it.single().size })
        assertTrue(geometry.polygons.all { polygon -> polygon.single().first() == polygon.single().last() })
    }

    @Test fun invalidLinearRingsAndCoordinatesAreRejected() {
        listOf(
            OPEN_RING,
            THREE_POSITIONS,
            TOO_FEW_DISTINCT_VERTICES,
            """{"type":"Polygon","coordinates":[[[106,10],[107,10],[107,NaN],[106,10]]]}""",
            """{"type":"Polygon","coordinates":[[[106,91],[107,10],[107,11],[106,91]]]}""",
            """{"type":"Polygon","coordinates":[[[181,10],[107,10],[107,11],[181,10]]]}""",
            MALFORMED_INNER_RING,
            MALFORMED_MULTIPOLYGON,
        ).forEach { raw -> assertInvalid(raw) }
    }

    @Test fun multipolygonPreservesPolygonRingCoordinateAndClosingOrder() {
        val geometry = found(MULTIPOLYGON).geometry as MapCanonicalAreaGeometry.MultiPolygon

        assertEquals(2, geometry.polygons.size)
        assertEquals(2, geometry.polygons.first().size)
        assertEquals(MapCanonicalCoordinate(10.0, 106.0), geometry.polygons[0][0][0])
        assertEquals(MapCanonicalCoordinate(10.0, 106.0), geometry.polygons[0][0].last())
        assertEquals(MapCanonicalCoordinate(20.0, 116.0), geometry.polygons[1][0][0])
        assertEquals(MapCanonicalCoordinate(20.0, 116.0), geometry.polygons[1][0].last())
    }

    private fun found(raw: String): AreaCanonicalRead {
        val result = MapItemCanonicalEntityMapper.area(area(raw)) as MapItemCanonicalLookupResult.Found
        return result.item as AreaCanonicalRead
    }

    private fun assertInvalid(raw: String) {
        assertTrue(MapItemCanonicalEntityMapper.area(area(raw)) is MapItemCanonicalLookupResult.InvalidRecord)
    }

    private fun area(geometry: String) = MapAreaEntity(
        "g", "a", "layer", geometry, "label", "note", "risk", 1, "active",
        1, 2, "did:o", null, null, null, null, 2_000, "event", 2, 1, "did:o",
    )

    private companion object {
        const val POLYGON =
            """{"type":"Polygon","coordinates":[[[106,10],[107,10],[107,11],[106,10]]]}"""
        const val OPEN_RING =
            """{"type":"Polygon","coordinates":[[[106,10],[107,10],[107,11]]]}"""
        const val THREE_POSITIONS =
            """{"type":"Polygon","coordinates":[[[106,10],[107,10],[106,10]]]}"""
        const val TOO_FEW_DISTINCT_VERTICES =
            """{"type":"Polygon","coordinates":[[[106,10],[107,10],[106,10],[106,10]]]}"""
        const val MALFORMED_INNER_RING =
            """{"type":"Polygon","coordinates":[[[106,10],[107,10],[107,11],[106,10]],[[106.2,10.2],[106.3,10.2],[106.3,10.3]]]}"""
        const val MALFORMED_MULTIPOLYGON =
            """{"type":"MultiPolygon","coordinates":[[[[106,10],[107,10],[107,11],[106,10]]],[[[116,20],[117,20],[117,21]]]]]}"""
        const val POLYGON_WITH_HOLE =
            """{"type":"Polygon","coordinates":[[[106,10],[107,10],[107,11],[106,10]],[[106.2,10.2],[106.3,10.2],[106.3,10.3],[106.2,10.2]]]}"""
        const val TWO_TRIANGLE_MULTIPOLYGON =
            """{"type":"MultiPolygon","coordinates":[[[[106,10],[107,10],[107,11],[106,10]]],[[[116,20],[117,20],[117,21],[116,20]]]]}"""
        const val MULTIPOLYGON =
            """{"type":"MultiPolygon","coordinates":[[[[106,10],[107,10],[107,11],[106,10]],[[106.2,10.2],[106.3,10.2],[106.3,10.3],[106.2,10.2]]],[[[116,20],[117,20],[117,21],[116,20]]]]}"""
    }
}
