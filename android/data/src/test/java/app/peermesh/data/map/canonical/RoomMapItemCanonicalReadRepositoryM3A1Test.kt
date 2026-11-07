package app.peermesh.data.map.canonical

import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.dao.MapDao
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import app.peermesh.data.map.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class RoomMapItemCanonicalReadRepositoryM3A1Test {
    @Test fun markerIdentityQueriesOnlyMarkerTable() {
        val dao = mock(MapDao::class.java)
        `when`(dao.findMarker("g", "same")).thenReturn(marker())
        val result = RoomMapItemCanonicalReadRepository(dao).find("g", MapItemIdentity(MapItemType.MARKER, "same"))
        assertTrue((result as MapItemCanonicalLookupResult.Found).item is MarkerCanonicalRead)
        verify(dao).findMarker("g", "same")
        verify(dao, never()).findArea("g", "same")
    }

    @Test fun areaIdentityQueriesOnlyAreaTable() {
        val dao = mock(MapDao::class.java)
        `when`(dao.findArea("g", "same")).thenReturn(area())
        val result = RoomMapItemCanonicalReadRepository(dao).find("g", MapItemIdentity(MapItemType.AREA, "same"))
        assertTrue((result as MapItemCanonicalLookupResult.Found).item is AreaCanonicalRead)
        verify(dao).findArea("g", "same")
        verify(dao, never()).findMarker("g", "same")
    }

    @Test fun markerAndAreaSameRawIdRemainIsolated() {
        val dao = mock(MapDao::class.java)
        `when`(dao.findMarker("g", "same")).thenReturn(marker())
        `when`(dao.findArea("g", "same")).thenReturn(area())
        val repository = RoomMapItemCanonicalReadRepository(dao)
        val marker = (repository.find("g", MapItemIdentity(MapItemType.MARKER, "same")) as MapItemCanonicalLookupResult.Found).item
        val area = (repository.find("g", MapItemIdentity(MapItemType.AREA, "same")) as MapItemCanonicalLookupResult.Found).item
        assertEquals(MapItemType.MARKER, marker.itemType)
        assertEquals(MapItemType.AREA, area.itemType)
    }

    @Test fun missingTargetReturnsMissing() {
        val dao = mock(MapDao::class.java)
        assertEquals(
            MapItemCanonicalLookupResult.Missing,
            RoomMapItemCanonicalReadRepository(dao).find("g", MapItemIdentity(MapItemType.MARKER, "missing")),
        )
    }

    @Test fun invalidSiblingCannotCorruptExactTargetLoad() {
        val dao = mock(MapDao::class.java)
        `when`(dao.findMarker("g", "target")).thenReturn(marker(id = "target"))
        `when`(dao.findMarker("g", "bad")).thenReturn(marker(id = "bad", lat = 200.0))
        val result = RoomMapItemCanonicalReadRepository(dao).find("g", MapItemIdentity(MapItemType.MARKER, "target"))
        assertEquals("target", (result as MapItemCanonicalLookupResult.Found).item.itemId)
        verify(dao, never()).findMarker("g", "bad")
    }

    @Test fun mismatchedRowsAndTechnicalFailuresFailSafely() {
        val dao = mock(MapDao::class.java)
        `when`(dao.findMarker("g", "requested")).thenReturn(marker(id = "different"))
        assertEquals(
            MapItemCanonicalLookupResult.InvalidRecord,
            RoomMapItemCanonicalReadRepository(dao).find("g", MapItemIdentity(MapItemType.MARKER, "requested")),
        )
        `when`(dao.findArea("g", "boom")).thenThrow(IllegalStateException("db unavailable"))
        assertEquals(
            MapItemCanonicalLookupResult.ReadFailure,
            RoomMapItemCanonicalReadRepository(dao).find("g", MapItemIdentity(MapItemType.AREA, "boom")),
        )
    }

    private fun marker(id: String = "same", lat: Double = 10.0) = MapMarkerEntity(
        "g", id, "layer", lat, 106.0, 20.0, "label", "note", "risk", 1, "active",
        1, 1, "owner", null, null, null, null, null, "event", 1, 0, "owner",
    )

    private fun area(id: String = "same") = MapAreaEntity(
        "g", id, "layer", POLYGON, "label", "note", "risk", 1, "active",
        1, 1, "owner", null, null, null, null, null, "event", 1, 0, "owner",
    )

    companion object {
        private const val POLYGON =
            "{\"type\":\"Polygon\",\"coordinates\":[[[106.0,10.0],[107.0,10.0],[107.0,11.0],[106.0,10.0]]]}"
    }
}
