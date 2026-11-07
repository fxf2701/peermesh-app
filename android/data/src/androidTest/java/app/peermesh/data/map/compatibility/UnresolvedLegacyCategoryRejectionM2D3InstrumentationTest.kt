package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.data.map.processing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises live legacy category derivation through RoomMapEventProcessor, including the
 * type-support guard shared semantically with the schema-51 migration path.
 *
 */
@RunWith(AndroidJUnit4::class)
class UnresolvedLegacyCategoryRejectionM2D3InstrumentationTest {
    @Test
    fun unknownLegacyMarkerKindRejectsDedicatedActionWithoutCanonicalMutation() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("m", kind = "future_kind")) is MapEventProcessingResult.Accepted)
            val before = h.db.mapDao().findMarker("g", "m")
            assertNull(h.db.mapItemDomainStateDao().find("g", "marker", "m")!!.category)
            val result = h.process(h.confirm("c", "m"))
            assertEquals(MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED, (result as MapEventProcessingResult.Rejected).reason)
            assertEquals(before, h.db.mapDao().findMarker("g", "m"))
        }
    }

    @Test
    fun unsupportedLegacyAreaKindRejectsDedicatedActionWithoutCanonicalMutation() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.area("a", kind = "camp")) is MapEventProcessingResult.Accepted)
            val before = h.db.mapDao().findArea("g", "a")!!
            assertNull("unsupported marker-only kind must remain unresolved for an area", h.db.mapItemDomainStateDao().find("g", "area", "a")!!.category)
            val result = h.process(h.ack("ack", "a", area = true))
            assertEquals(MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED, (result as MapEventProcessingResult.Rejected).reason)
            assertEquals(before, h.db.mapDao().findArea("g", "a"))
        }
    }

    @Test
    fun unresolvedTargetRejectionDoesNotAffectValidSibling() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("bad", kind = "future_kind")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.marker("good")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.confirm("bad-action", "bad")) is MapEventProcessingResult.Rejected)
            assertTrue(h.process(h.confirm("good-action", "good")) is MapEventProcessingResult.Accepted)
            assertEquals("applied", h.db.mapItemActionDao().findByActionId("g", "good-action")!!.materialization_state)
        }
    }

    @Test
    fun supportedMarkerAndAreaKindsDeriveCatalogCategoriesOnAddAndKeepThemOnUpdate() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("m", kind = "risk")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.area("a", kind = "risk")) is MapEventProcessingResult.Accepted)
            assertEquals(MapItemKindCatalog.categoryFor("risk")!!.wireValue, h.db.mapItemDomainStateDao().find("g", "marker", "m")!!.category)
            assertEquals(MapItemKindCatalog.categoryFor("risk")!!.wireValue, h.db.mapItemDomainStateDao().find("g", "area", "a")!!.category)

            val update = EventFactory.mapMarkerUpdate("upd", "owner", 110, 2, "m", "layer", 11.0, 107.0, null, null, null, "risk")
            assertTrue(h.process(update) is MapEventProcessingResult.Accepted)
            assertEquals("situation", h.db.mapItemDomainStateDao().find("g", "marker", "m")!!.category)
        }
    }

    @Test
    fun currentCatalogHasNoAreaOnlyKindsAndMarkerOnlyKindsAreRejectedForAreas() {
        val areaOnly = MapItemKindCatalog.canonicalKinds.filter {
            MapItemKindCatalog.supportsArea(it) && !MapItemKindCatalog.supportsMarker(it)
        }
        assertTrue("new area-only kinds require a positive marker-rejection fixture", areaOnly.isEmpty())
        assertTrue(MapItemKindCatalog.supportsMarker("camp"))
        assertFalse(MapItemKindCatalog.supportsArea("camp"))
    }

}
