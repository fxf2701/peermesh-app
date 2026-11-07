package app.peermesh.data.map.compatibility

import app.peermesh.data.ingest.EventSources
import app.peermesh.data.map.processing.MapEventProcessingResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Extends M2D1 batch evidence with one legacy canonical sibling and one dedicated action sibling.
 *
 */
class MixedVersionDedicatedActionBatchCompatibilityTest {
    @Test
    fun legacyCanonicalAndDedicatedActionShareExistingMapBatchLane() {
        val legacy = M2D3JvmHarness.fixture("legacy_marker_add_v0.json")
        val action = M2D3JvmHarness.fixture("unknown_dedicated_action_for_old_peer.json")
        val seen = mutableListOf<String>()
        val result = M2D3JvmHarness.receiver { seen += it.core.id; MapEventProcessingResult.Accepted(it.core.id) }
            .onEnvelopeReceivedWithMeta(M2D3JvmHarness.batch(legacy, action), EventSources.MESH)
        assertTrue(result.accepted)
        assertEquals(listOf(legacy.id, action.id), seen)
    }

    @Test
    fun receiverRoutesBothSiblingsThroughExistingProductionProcessorSeam() {
        val cores = listOf(M2D3JvmHarness.fixture("legacy_area_add_v0.json"), M2D3JvmHarness.fixture("unknown_dedicated_action_for_old_peer.json"))
        val seen = mutableListOf<String>()
        M2D3JvmHarness.receiver { seen += it.core.id; MapEventProcessingResult.Accepted(it.core.id) }
            .onEnvelopeReceivedWithMeta(M2D3JvmHarness.batch(*cores.toTypedArray()), EventSources.MESH)
        assertEquals(cores.map { it.id }, seen)
    }

}
