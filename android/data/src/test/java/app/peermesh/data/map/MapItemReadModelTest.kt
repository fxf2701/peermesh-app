package app.peermesh.data.map

import app.peermesh.core.map.MapItemType
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.dao.MapItemActionDao
import app.peermesh.data.db.entity.MapItemDomainStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class MapItemReadModelTest {
    @Test fun `invalid cursors and limits fail before SQL`() {
        val dao = mock(MapItemActionDao::class.java)
        val repository = RoomMapItemActionQueryRepository(dao)
        val id = MapItemIdentity(MapItemType.MARKER, "i")
        listOf(
            repository.scanPending("", null, 1), repository.scanPending("g", null, 0),
            repository.scanPending("g", MapActionOrderKey(0, 0, "did:a", "e"), 1),
            repository.appliedHistory("g", id, MapActionHistoryCursor(MapActionOrderKey(1, -1, "did:a", "e")), 1),
        ).forEach { assertTrue(it is MapQueryResult.InvalidInput) }
        verifyNoInteractions(dao)
    }

    @Test fun `known nullable state maps to public and internal projections`() {
        val row = domain()
        val public = (MapItemEntityMappers.domain(row) as PersistenceParseResult.Success).value
        val internal = (MapItemEntityMappers.snapshot(row) as PersistenceParseResult.Success).value
        assertNull(public.category)
        assertNull(public.lifecycle)
        assertEquals("did:owner", internal.ownerDid)
        assertEquals(MapActionOrderKey(10, 2, "did:owner", "add"), internal.canonicalAddOrder)
        assertFalse(MapItemDomainState::class.java.declaredFields.any { it.name.contains("owner", true) || it.name.contains("latest", true) })
    }

    @Test fun `unknown required and partial order fail safely`() {
        assertTrue(MapItemEntityMappers.domain(domain().copy(temporal_origin = "future")) is PersistenceParseResult.UnknownPersistedValue)
        assertTrue(MapItemEntityMappers.snapshot(domain().copy(canonical_add_actor = null)) is PersistenceParseResult.InvalidRecordShape)
    }

    private fun domain() = MapItemDomainStateEntity(
        "g", "marker", "i", null, "unresolved", null, null, "legacy_row_backfill", "did:owner",
        "add", 10, 2, "did:owner", null, null, null, null, "legacy_no_expiry", 2, 3,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
    )
}
