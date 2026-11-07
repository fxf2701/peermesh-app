package app.peermesh.data.map.compatibility

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Models an old peer reading only known canonical fields while ignoring optional M2 extensions.
 *
 * Contract-modeled old-peer known-field projection. It deliberately does not execute a current
 * parser as an old-peer substitute; provenance and model limits are documented in the manifest.
 */
class DomainAwareCanonicalOldPeerFixtureTest {
    @Test
    fun oldPeerReadsKnownFieldsFromDomainAwareMarkerAdd() {
        assertKnownFields("domain_v1_marker_add_for_old_peer.json", setOf("marker_id", "layer_id", "lat", "lon", "kind", "confidence"))
    }

    @Test
    fun oldPeerReadsKnownFieldsFromDomainAwareAreaAdd() {
        assertKnownFields("domain_v1_area_add_for_old_peer.json", setOf("area_id", "layer_id", "geometry", "kind", "confidence"))
    }

    @Test
    fun oldPeerReadsKnownFieldsFromDomainAwareMarkerUpdate() {
        assertKnownFields("domain_v1_marker_update_for_old_peer.json", setOf("marker_id", "lat", "lon", "confidence"))
    }

    @Test
    fun oldPeerReadsKnownFieldsFromDomainAwareAreaUpdate() {
        assertKnownFields("domain_v1_area_update_for_old_peer.json", setOf("area_id", "geometry", "confidence"))
    }

    @Test
    fun optionalExtensionsDoNotReplaceRequiredLegacyCanonicalFields() {
        domainNames.forEach { name ->
            val original = M2D3JvmHarness.fixture(name).data as JsonObject
            val projected = M2D3JvmHarness.knownFieldProjection(M2D3JvmHarness.fixture(name)).data as JsonObject
            assertTrue(name, original.keys.containsAll(projected.keys))
            assertEquals(name, original.filterKeys { it !in M2D3JvmHarness.optionalDomainFields }, projected)
        }
    }

    @Test
    fun oldPeerMayIgnoreEachKnownOptionalDomainExtensionSafely() {
        val original = M2D3JvmHarness.fixture(domainNames.first())
        listOf("map_domain_v", "item_category", "information_source").forEach { ignored ->
            val data = JsonObject((original.data as JsonObject) - ignored)
            assertTrue(ignored, M2D3JvmHarness.modeledPreM2ValidatorAccepts(original.copy(data = data)))
        }
    }

    @Test
    fun oldPeerRetainsCanonicalStateWithoutMaterializingNewDomainState() {
        domainNames.forEach { assertTrue(it, M2D3JvmHarness.modeledPreM2ValidatorAccepts(M2D3JvmHarness.knownFieldProjection(M2D3JvmHarness.fixture(it)))) }
    }

    @Test
    fun domainAwareCanonicalTrafficDoesNotChangeLegacyConfidence() {
        domainNames.forEach { name ->
            val original = M2D3JvmHarness.fixture(name).data as JsonObject
            val projected = M2D3JvmHarness.knownFieldProjection(M2D3JvmHarness.fixture(name)).data as JsonObject
            assertEquals(name, original["confidence"], projected["confidence"])
        }
    }

    @Test
    fun domainAwareCanonicalTrafficDoesNotCreateLegacyContributionRows() {
        domainNames.forEach { name ->
            val projected = M2D3JvmHarness.knownFieldProjection(M2D3JvmHarness.fixture(name)).data as JsonObject
            assertNull(name, projected["action_note"])
            assertNull(name, projected["vote"])
            assertNull(name, projected["contribution"])
        }
    }

    private fun assertKnownFields(name: String, required: Set<String>) {
        val core = M2D3JvmHarness.fixture(name)
        val projected = M2D3JvmHarness.knownFieldProjection(core)
        assertTrue(required.all { (projected.data as JsonObject).containsKey(it) })
        assertTrue(M2D3JvmHarness.modeledPreM2ValidatorAccepts(projected))
    }

    private val domainNames = listOf(
        "domain_v1_marker_add_for_old_peer.json", "domain_v1_marker_update_for_old_peer.json",
        "domain_v1_area_add_for_old_peer.json", "domain_v1_area_update_for_old_peer.json",
    )
}
