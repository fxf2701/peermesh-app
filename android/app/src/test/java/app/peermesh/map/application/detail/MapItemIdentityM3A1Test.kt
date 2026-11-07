package app.peermesh.map.application.detail

import app.peermesh.core.map.MapItemType
import app.peermesh.data.map.MapItemBatchLookupResult
import app.peermesh.data.map.MapItemDomainStateRepository
import app.peermesh.data.map.MapItemLookupResult
import app.peermesh.data.map.MapQueryResult
import app.peermesh.data.map.canonical.MapItemCanonicalLookupResult
import app.peermesh.data.map.canonical.MapItemCanonicalReadRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MapItemIdentityM3A1Test {
    @Test fun markerIdentityContainsGroupTypeAndItemId() {
        assertEquals(MapItemIdentity("group-a", MapItemType.MARKER, "same"),
            MapItemIdentity("group-a", MapItemType.MARKER, "same"))
    }

    @Test fun areaIdentityContainsGroupTypeAndItemId() {
        assertEquals("group-a", MapItemIdentity("group-a", MapItemType.AREA, "area-a").groupId)
        assertEquals(MapItemType.AREA, MapItemIdentity("group-a", MapItemType.AREA, "area-a").itemType)
        assertEquals("area-a", MapItemIdentity("group-a", MapItemType.AREA, "area-a").itemId)
    }

    @Test fun markerAndAreaWithSameRawIdRemainIsolated() {
        assertNotEquals(
            MapItemIdentity("g", MapItemType.MARKER, "same"),
            MapItemIdentity("g", MapItemType.AREA, "same"),
        )
        assertNotEquals(
            MapItemIdentity("g-1", MapItemType.MARKER, "same"),
            MapItemIdentity("g-2", MapItemType.MARKER, "same"),
        )
    }

    @Test fun blankIdentityInputsFailSafelyThroughLoadBoundary() {
        val canonical = CountingCanonicalRepository()
        val domain = CountingDomainRepository()
        val loader = DefaultMapItemDetailComposer(canonical, domain)
        assertEquals(
            MapItemDetailLoadResult.InvalidIdentity(InvalidIdentityReason.BLANK_GROUP_ID),
            loader.load(MapItemIdentity("   ", MapItemType.MARKER, "id")),
        )
        assertEquals(
            MapItemDetailLoadResult.InvalidIdentity(InvalidIdentityReason.BLANK_ITEM_ID),
            loader.load(MapItemIdentity("group", MapItemType.AREA, "\t")),
        )
        assertEquals(0, canonical.calls)
        assertEquals(0, domain.calls)
    }

    private class CountingCanonicalRepository : MapItemCanonicalReadRepository {
        var calls = 0
        override fun find(groupId: String, identity: app.peermesh.data.map.MapItemIdentity): MapItemCanonicalLookupResult {
            calls++
            return MapItemCanonicalLookupResult.Missing
        }
    }

    private class CountingDomainRepository : MapItemDomainStateRepository {
        var calls = 0
        override fun find(gid: String, identity: app.peermesh.data.map.MapItemIdentity): MapQueryResult<MapItemLookupResult> {
            calls++
            return MapQueryResult.Success(MapItemLookupResult.Missing)
        }
        override fun findBatch(
            gid: String,
            identities: Collection<app.peermesh.data.map.MapItemIdentity>,
        ): MapQueryResult<MapItemBatchLookupResult> = error("not used")
    }
}
