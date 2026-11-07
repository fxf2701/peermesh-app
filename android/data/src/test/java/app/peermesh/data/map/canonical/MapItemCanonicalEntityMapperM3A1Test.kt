package app.peermesh.data.map.canonical

import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapItemCanonicalEntityMapperM3A1Test {
    @Test fun validMarkerMapsToCanonicalRead() {
        val item = found(MapItemCanonicalEntityMapper.marker(marker(kind = "danger"))) as MarkerCanonicalRead
        assertEquals("g", item.groupId)
        assertEquals(MapItemType.MARKER, item.itemType)
        assertEquals("m", item.itemId)
        assertEquals("risk", item.normalizedKind)
        assertEquals(MapCanonicalAvailability.ACTIVE, item.availability)
        assertEquals(10.5, item.latitude, 0.0)
        assertEquals(106.6, item.longitude, 0.0)
        assertEquals(25.0, item.radiusMeters!!, 0.0)
    }

    @Test fun invalidMarkerCoordinatesAreRejected() {
        listOf(
            marker(lat = Double.NaN),
            marker(lat = 91.0),
            marker(lon = Double.POSITIVE_INFINITY),
            marker(lon = -181.0),
        ).forEach { assertInvalid(MapItemCanonicalEntityMapper.marker(it)) }
    }

    @Test fun invalidMarkerRadiusIsRejected() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertInvalid(MapItemCanonicalEntityMapper.marker(marker(radius = it)))
        }
        assertNull((found(MapItemCanonicalEntityMapper.marker(marker(radius = null))) as MarkerCanonicalRead).radiusMeters)
    }

    @Test fun validAreaMapsToSafeGeometrySummary() {
        val polygon = found(MapItemCanonicalEntityMapper.area(area())) as AreaCanonicalRead
        assertEquals(MapCanonicalAreaGeometrySummary.Polygon(3), polygon.geometrySummary)

        val multi = found(MapItemCanonicalEntityMapper.area(area(geometry = MULTI_POLYGON))) as AreaCanonicalRead
        assertEquals(MapCanonicalAreaGeometrySummary.MultiPolygon(2, 6), multi.geometrySummary)
    }

    @Test fun malformedAreaGeometryIsRejected() {
        listOf(
            "not-json",
            "[]",
            "{\"type\":\"LineString\",\"coordinates\":[[106,10],[107,11]]}",
            "{\"type\":\"Polygon\",\"coordinates\":[[[106,10],[107,10]]]}",
            UNCLOSED_POLYGON,
            "{\"type\":\"Polygon\",\"coordinates\":[[[181,10],[107,10],[107,11],[181,10]]]}",
        ).forEach { assertInvalid(MapItemCanonicalEntityMapper.area(area(geometry = it))) }
    }

    @Test fun unknownRequiredStatusIsRejected() {
        assertInvalid(MapItemCanonicalEntityMapper.marker(marker(status = "expired")))
        assertInvalid(MapItemCanonicalEntityMapper.area(area(status = "future")))
        assertEquals(
            MapCanonicalAvailability.DELETED,
            (found(MapItemCanonicalEntityMapper.area(area(status = "deleted"))) as AreaCanonicalRead).availability,
        )
    }

    @Test fun unknownLegacyKindIsNotGuessedIntoDomainCategory() {
        val item = found(MapItemCanonicalEntityMapper.marker(marker(kind = "future_kind"))) as MarkerCanonicalRead
        assertEquals("future_kind", item.normalizedKind)
        assertTrue(MapItemCanonicalRead::class.java.declaredMethods.none { it.name.contains("category", ignoreCase = true) })
    }

    @Test fun blankIdentityAndInvalidExpiryAreRejected() {
        assertInvalid(MapItemCanonicalEntityMapper.marker(marker(gid = "")))
        assertInvalid(MapItemCanonicalEntityMapper.area(area(id = " ")))
        assertInvalid(MapItemCanonicalEntityMapper.marker(marker(expiresAt = 0)))
        assertInvalid(MapItemCanonicalEntityMapper.area(area(expiresAt = -1)))
    }

    private fun marker(
        gid: String = "g",
        id: String = "m",
        lat: Double = 10.5,
        lon: Double = 106.6,
        radius: Double? = 25.0,
        kind: String? = "risk",
        status: String = "active",
        expiresAt: Long? = 2_000,
    ) = MapMarkerEntity(
        gid, id, "layer", lat, lon, radius, "label", "owner note", kind, 2, status,
        1_000, 1_100, "did:owner", null, null, null, null, expiresAt,
        "event", 1_100, 1, "did:owner",
    )

    private fun area(
        gid: String = "g",
        id: String = "a",
        geometry: String = POLYGON,
        kind: String? = "risk",
        status: String = "active",
        expiresAt: Long? = 2_000,
    ) = MapAreaEntity(
        gid, id, "layer", geometry, "label", "owner note", kind, 1, status,
        1_000, 1_100, "did:owner", null, null, null, null, expiresAt,
        "event", 1_100, 1, "did:owner",
    )

    private fun found(result: MapItemCanonicalLookupResult) =
        (result as MapItemCanonicalLookupResult.Found).item

    private fun assertInvalid(result: MapItemCanonicalLookupResult) =
        assertEquals(MapItemCanonicalLookupResult.InvalidRecord, result)

    companion object {
        private const val POLYGON =
            "{\"type\":\"Polygon\",\"coordinates\":[[[106.0,10.0],[107.0,10.0],[107.0,11.0],[106.0,10.0]]]}"
        private const val UNCLOSED_POLYGON =
            "{\"type\":\"Polygon\",\"coordinates\":[[[106.0,10.0],[107.0,10.0],[107.0,11.0]]]}"
        private const val MULTI_POLYGON =
            "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[106.0,10.0],[107.0,10.0],[107.0,11.0],[106.0,10.0]]],[[[108.0,10.0],[109.0,10.0],[109.0,11.0],[108.0,10.0]]]]}"
    }
}
