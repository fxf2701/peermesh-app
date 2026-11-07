package app.peermesh.data.map.compatibility

import app.peermesh.data.ingest.EventSources
import app.peermesh.data.map.processing.MapEventProcessingResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Extends M2D1 batch evidence with legacy and domain-aware canonical siblings.
 *
 */
class MixedVersionCanonicalBatchCompatibilityTest {
    @Test
    fun legacyAndDomainAwareCanonicalEventsShareExistingMapBatchLane() {
        val cores = listOf(M2D3JvmHarness.fixture("legacy_marker_add_v0.json"), M2D3JvmHarness.fixture("domain_v1_area_add_for_old_peer.json"))
        val seen = mutableListOf<String>()
        val result = M2D3JvmHarness.receiver { seen += it.core.id; MapEventProcessingResult.Accepted(it.core.id) }
            .onEnvelopeReceivedWithMeta(M2D3JvmHarness.batch(*cores.toTypedArray()), EventSources.MESH)
        assertTrue(result.accepted)
        assertEquals(cores.map { it.id }, seen)
    }

    @Test
    fun receiverPreservesEachMixedVersionCanonicalSiblingIdentity() {
        val cores = listOf(M2D3JvmHarness.fixture("legacy_area_add_v0.json"), M2D3JvmHarness.fixture("domain_v1_marker_add_for_old_peer.json"))
        val seen = mutableListOf<String>()
        M2D3JvmHarness.receiver { seen += it.core.id; MapEventProcessingResult.Accepted(it.core.id) }
            .onEnvelopeReceivedWithMeta(M2D3JvmHarness.batch(*cores.toTypedArray()), EventSources.MESH)
        assertEquals(cores.map { it.id }, seen)
    }

}
