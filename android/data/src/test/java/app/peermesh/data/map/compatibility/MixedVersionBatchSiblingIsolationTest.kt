package app.peermesh.data.map.compatibility

import app.peermesh.core.map.MapDomainPayloadContract
import app.peermesh.core.model.*
import app.peermesh.core.types.EventTypes
import app.peermesh.data.ingest.EventSources
import app.peermesh.data.map.processing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * Proves compatibility-specific rejected, malformed, or unknown siblings cannot suppress valid
 * legacy canonical or non-map siblings in the existing APP-batch path.
 *
 * Uses the production M2D1 APP-batch path for current-peer sibling isolation and an explicitly
 * contract-modeled per-item premise for unavailable old-peer batch behavior.
 */
class MixedVersionBatchSiblingIsolationTest {
    @Test
    fun malformedDedicatedActionDoesNotSuppressValidLegacyCanonicalSibling() {
        val base = action()
        val malformed = base.copy(data = JsonObject((base.data as JsonObject) +
            (MapDomainPayloadContract.ACTION_ID_FIELD to JsonPrimitive("wrong-id"))))
        assertOnlyLegacyProcessed(malformed)
    }

    @Test
    fun unknownDedicatedActionDoesNotSuppressValidLegacyCanonicalSibling() {
        val unknown = action().copy(type = "MAP.ITEM.FUTURE")
        assertOnlyLegacyProcessed(unknown)
    }

    @Test
    fun modeledOldPeerPerItemPremiseLeavesValidNonMapSiblingAccepted() {
        val unknown = action().copy(type = "MAP.ITEM.FUTURE")
        val chat = EventCore(1, "chat-sibling", EventTypes.CHAT_MSG, Actor("did:peer"),
            EventTime(System.currentTimeMillis() / 1000, 1), EventRef(), data = buildJsonObject { put("text", "safe sibling") })
        val accepted = M2D3JvmHarness.modeledPreM2BatchAcceptedItems(unknown, chat)
        assertEquals(listOf(chat), accepted)
    }

    @Test
    fun currentPeerReceiverRejectsUnknownActionWithoutCorruptingCanonicalSibling() {
        val legacy = legacy()
        var received: EventCore? = null
        M2D3JvmHarness.receiver { received = it.core; MapEventProcessingResult.Accepted(it.core.id) }
            .onEnvelopeReceivedWithMeta(M2D3JvmHarness.batch(action().copy(type = "MAP.ITEM.FUTURE"), legacy), EventSources.MESH)
        assertEquals(legacy, received)
    }

    @Test
    fun unresolvedTargetRejectionDoesNotAffectValidSiblingWithinOneBatch() {
        val rejected = action()
        val legacy = legacy()
        val seen = mutableListOf<String>()
        val result = M2D3JvmHarness.receiver {
            seen += it.core.id
            if (it.core.id == rejected.id) MapEventProcessingResult.Rejected(it.core.id, MapEventRejectionReason.TARGET_CATEGORY_UNRESOLVED)
            else MapEventProcessingResult.Accepted(it.core.id)
        }.onEnvelopeReceivedWithMeta(M2D3JvmHarness.batch(rejected, legacy), EventSources.MESH)
        assertTrue(result.accepted)
        assertEquals(listOf(rejected.id, legacy.id), seen)
    }

    private fun assertOnlyLegacyProcessed(invalid: EventCore) {
        val legacy = legacy()
        val seen = mutableListOf<String>()
        val result = M2D3JvmHarness.receiver { seen += it.core.id; MapEventProcessingResult.Accepted(it.core.id) }
            .onEnvelopeReceivedWithMeta(M2D3JvmHarness.batch(invalid, legacy), EventSources.MESH)
        assertTrue(result.accepted)
        assertEquals(listOf(legacy.id), seen)
    }

    private fun action() = M2D3JvmHarness.fixture("unknown_dedicated_action_for_old_peer.json")
    private fun legacy() = M2D3JvmHarness.fixture("legacy_marker_add_v0.json")
}
