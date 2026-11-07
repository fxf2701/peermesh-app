package app.peermesh.data.map.compatibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.core.map.MapItemCategory
import app.peermesh.data.map.processing.MapEventProcessingResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves recognized active legacy Situation targets use the existing category/action policy.
 *
 */
@RunWith(AndroidJUnit4::class)
class LegacySituationActionCompatibilityM2D3InstrumentationTest {
    @Test
    fun recognizedLegacySituationMarkerAcceptsCategoryCorrectAction() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("m")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.confirm("c", "m")) is MapEventProcessingResult.Accepted)
            assertEquals("applied", h.db.mapItemActionDao().findByActionId("g", "c")!!.materialization_state)
        }
    }

    @Test
    fun recognizedLegacySituationAreaAcceptsCategoryCorrectAction() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.area("a")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.confirmArea("c", "a")) is MapEventProcessingResult.Accepted)
            assertEquals("applied", h.db.mapItemActionDao().findByActionId("g", "c")!!.materialization_state)
        }
    }

    @Test
    fun legacyTargetCategoryComesOnlyFromMapItemKindCatalog() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("m", kind = "danger")) is MapEventProcessingResult.Accepted)
            val state = h.db.mapItemDomainStateDao().find("g", "marker", "m")!!
            assertEquals(MapItemKindCatalog.categoryFor("danger")!!.wireValue, state.category)
            assertEquals("legacy_kind_derived", state.category_origin)
        }
    }

    @Test
    fun legacyCreatorSourceRemainsUnspecifiedAfterAction() {
        M2D3RoomHarness().use { h ->
            assertTrue(h.process(h.marker("m")) is MapEventProcessingResult.Accepted)
            assertTrue(h.process(h.confirm("c", "m")) is MapEventProcessingResult.Accepted)
            assertNull(h.db.mapItemDomainStateDao().find("g", "marker", "m")!!.creator_information_source)
        }
    }

    @Test
    fun explicitDomainCategoryIsNotReplacedByLegacyFallbackOrCanonicalUpdate() {
        M2D3RoomHarness().use { h ->
            val add = EventFactory.mapMarkerAdd(
                "explicit-add", "owner", 100, 1, "m", "layer", 10.0, 106.0, null,
                "label", "note", "risk", domainExtension = MapCanonicalAddExtension(
                    MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND,
                ),
            )
            assertTrue(h.process(add) is MapEventProcessingResult.Accepted)
            val before = h.db.mapItemDomainStateDao().find("g", "marker", "m")!!
            assertEquals("explicit_domain_v1", before.category_origin)
            assertEquals("firsthand", before.creator_information_source)

            val update = EventFactory.mapMarkerUpdate(
                "legacy-shaped-update", "owner", 110, 2, "m", "layer", 11.0, 107.0,
                null, "changed", "changed", "risk",
            )
            assertTrue(h.process(update) is MapEventProcessingResult.Accepted)
            val after = h.db.mapItemDomainStateDao().find("g", "marker", "m")!!
            assertEquals(before.category, after.category)
            assertEquals(before.category_origin, after.category_origin)
            assertEquals(before.creator_information_source, after.creator_information_source)
        }
    }

}
