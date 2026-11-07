package app.peermesh

import app.peermesh.core.map.MapItemKindCatalog
import app.peermesh.core.map.MapItemCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapKindsCategoryContractTest {
    @Test
    fun `all marker and area options have centralized categories`() {
        val uiKinds = MapKinds.allMarkerKindKeys() + MapKinds.allAreaKindKeys()

        assertEquals(MapItemKindCatalog.canonicalKinds, uiKinds)
        uiKinds.forEach { kind ->
            assertNotNull("Missing category for map kind: $kind", MapKinds.categoryForKey(kind))
        }
    }

    @Test
    fun `all existing legacy aliases remain category compatible`() {
        MapItemKindCatalog.legacyAliases.forEach { alias ->
            assertEquals(MapItemKindCatalog.normalize(alias), MapKinds.normalizeKey(alias))
            assertNotNull("Missing category for legacy map kind: $alias", MapKinds.categoryForKey(alias))
            assertNotNull("Missing label for legacy map kind: $alias", MapKinds.labelForKey(alias))
        }
    }

    @Test
    fun `urban coordination marker creation offers urban vocabulary without outdoor fallback`() {
        val options = MapKinds.markerCreationOptions(
            MapKinds.PROFILE_URBAN,
            MapItemCategory.COORDINATION,
        )

        assertEquals(listOf(MapItemKindCatalog.MEETING_POINT, MapItemKindCatalog.WAYPOINT), options.map { it.key })
        assertTrue(options.all { MapKinds.categoryForKey(it.key) == MapItemCategory.COORDINATION })
        assertTrue(options.all { MapItemKindCatalog.supportsMarker(it.key) })
        assertTrue(options.all { it.label.isNotBlank() && it.label != it.key })
    }

    @Test
    fun `outdoor coordination marker creation keeps outdoor vocabulary`() {
        val options = MapKinds.markerCreationOptions(
            MapKinds.PROFILE_OUTDOOR,
            MapItemCategory.COORDINATION,
        )

        assertEquals(listOf(MapItemKindCatalog.CAMP, MapItemKindCatalog.WAYPOINT), options.map { it.key })
    }

    @Test
    fun `creation options never mix categories or unsupported shapes`() {
        MapItemCategory.entries.forEach { category ->
            MapKinds.markerCreationOptions(MapKinds.PROFILE_OUTDOOR, category).forEach { option ->
                assertEquals(category, MapKinds.categoryForKey(option.key))
                assertTrue(MapItemKindCatalog.supportsMarker(option.key))
            }
            MapKinds.areaCreationOptions(MapKinds.PROFILE_OUTDOOR, category).forEach { option ->
                assertEquals(category, MapKinds.categoryForKey(option.key))
                assertTrue(MapItemKindCatalog.supportsArea(option.key))
            }
        }
    }

    @Test
    fun `coordination area creation exposes profile appropriate canonical kinds`() {
        val urban = MapKinds.areaCreationOptions(MapKinds.PROFILE_URBAN, MapItemCategory.COORDINATION)
        val outdoor = MapKinds.areaCreationOptions(MapKinds.PROFILE_OUTDOOR, MapItemCategory.COORDINATION)

        assertTrue(urban.map { it.key }.containsAll(listOf("assembly_zone", "event_zone")))
        assertTrue(outdoor.map { it.key }.containsAll(listOf("assembly_zone", "trail_sector", "event_zone", "rescue_staging")))
        (urban + outdoor).forEach { option ->
            assertEquals(MapItemCategory.COORDINATION, MapKinds.categoryForKey(option.key))
            assertTrue(MapItemKindCatalog.supportsArea(option.key))
            assertTrue(option.label.isNotBlank() && option.label != option.key)
        }
    }
}
