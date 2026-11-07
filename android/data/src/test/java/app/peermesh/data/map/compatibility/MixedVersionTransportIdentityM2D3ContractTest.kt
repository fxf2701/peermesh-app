package app.peermesh.data.map.compatibility

import app.peermesh.core.map.MapDomainPayloadContract
import app.peermesh.data.ingest.EventSources
import app.peermesh.data.map.processing.MapEventProcessingResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * Uses representative existing M2D1/M2D2 paths to prove compatibility fixtures are not rewritten.
 *
 */
class MixedVersionTransportIdentityM2D3ContractTest {
    @Test
    fun representativeLegacyCanonicalTransportPreservesEventIdentity() {
        val original = M2D3JvmHarness.fixture("legacy_marker_add_v0.json")
        var received = original
        M2D3JvmHarness.receiver { received = it.core; MapEventProcessingResult.Accepted(it.core.id) }
            .onEnvelopeReceivedWithMeta(M2D3JvmHarness.batch(original), EventSources.MESH)
        assertEquals(original, received)
    }

    @Test
    fun representativeDedicatedActionTransportPreservesEventAndActionIdentity() {
        val original = M2D3JvmHarness.fixture("unknown_dedicated_action_for_old_peer.json")
        var received = original
        M2D3JvmHarness.receiver { received = it.core; MapEventProcessingResult.Accepted(it.core.id) }
            .onEnvelopeReceivedWithMeta(M2D3JvmHarness.batch(original), EventSources.MESH)
        val actionId = ((received.data as JsonObject)[MapDomainPayloadContract.ACTION_ID_FIELD] as JsonPrimitive).content
        assertEquals(original, received)
        assertEquals(received.id, actionId)
    }

}
