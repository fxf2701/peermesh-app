package app.peermesh.data.map.compatibility

import app.peermesh.core.types.EventTypes
import app.peermesh.core.validation.EventValidator
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Locks legacy area ADD/UPD/DEL compatibility without re-proving area reducer ordering.
 *
 */
class LegacyCanonicalAreaCompatibilityTest {
    @Test
    fun legacyAreaAddWithoutDomainExtensionsIsAccepted() {
        assertAccepted("legacy_area_add_v0.json", EventTypes.MAP_AREA_ADD)
    }

    @Test
    fun legacyAreaUpdateWithoutDomainExtensionsIsAccepted() {
        assertAccepted("legacy_area_update_v0.json", EventTypes.MAP_AREA_UPD)
    }

    @Test
    fun legacyAreaDeleteWithoutDomainExtensionsIsAccepted() {
        assertAccepted("legacy_area_delete_v0.json", EventTypes.MAP_AREA_DEL)
    }

    @Test
    fun legacyAreaFixturePreservesIdentityGeometryAndKnownFields() {
        val core = M2D3JvmHarness.fixture("legacy_area_add_v0.json")
        val data = core.data as JsonObject
        assertEquals("legacy-area-1", core.ref.target)
        assertNotNull(data["geometry"])
        assertEquals("1", data["confidence"].toString())
        assertFalse(data.keys.any { it in M2D3JvmHarness.optionalDomainFields })
    }

    private fun assertAccepted(name: String, type: String) {
        val core = M2D3JvmHarness.fixture(name)
        assertEquals(type, core.type)
        assertTrue(EventValidator.validate(core))
    }

}
