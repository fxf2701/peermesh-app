package app.peermesh.data.map.compatibility

import app.peermesh.core.types.EventTypes
import app.peermesh.core.validation.EventValidator
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Locks legacy marker ADD/UPD/DEL compatibility without re-proving canonical reducer semantics.
 *
 */
class LegacyCanonicalMarkerCompatibilityTest {
    @Test
    fun legacyMarkerAddWithoutDomainExtensionsIsAccepted() {
        assertAccepted("legacy_marker_add_v0.json", EventTypes.MAP_MARKER_ADD)
    }

    @Test
    fun legacyMarkerUpdateWithoutDomainExtensionsIsAccepted() {
        assertAccepted("legacy_marker_update_v0.json", EventTypes.MAP_MARKER_UPD)
    }

    @Test
    fun legacyMarkerDeleteWithoutDomainExtensionsIsAccepted() {
        assertAccepted("legacy_marker_delete_v0.json", EventTypes.MAP_MARKER_DEL)
    }

    @Test
    fun legacyMarkerFixturePreservesIdentityAndKnownFields() {
        val core = M2D3JvmHarness.fixture("legacy_marker_add_v0.json")
        val data = core.data as JsonObject
        assertEquals("legacy-marker-add", core.id)
        assertEquals("legacy-marker-1", core.ref.target)
        assertEquals("did:legacy-owner", core.actor.did)
        assertEquals("2", data["confidence"].toString())
        assertFalse(data.keys.any { it in M2D3JvmHarness.optionalDomainFields })
    }

    private fun assertAccepted(name: String, type: String) {
        val core = M2D3JvmHarness.fixture(name)
        assertEquals(type, core.type)
        assertTrue(EventValidator.validate(core))
    }

}
