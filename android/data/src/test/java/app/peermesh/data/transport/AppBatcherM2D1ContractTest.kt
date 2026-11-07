package app.peermesh.data.transport

import app.peermesh.core.encoding.Base64Url
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.AppBatchEnvelope
import app.peermesh.core.model.EncPayload
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.transport.EnvelopeBroadcaster
import app.peermesh.core.types.EventTypes
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppBatcherM2D1ContractTest {
    @Before fun setUp() = AppBatcher.resetForTest()
    @After fun tearDown() = AppBatcher.resetForTest()

    @Test
    fun allCanonicalAndDedicatedMapTypesUseTheExistingMapLane() {
        mapTypes.forEach { type ->
            assertEquals(type, AppBatcher.BatchType.MAP, AppBatcher.batchTypeFor(type))
        }
        assertEquals(3_000L, AppBatcher.MAP_BATCH_WINDOW_MS)
        assertEquals(AppBatcher.MAP_BATCH_WINDOW_MS, AppBatcher.batchWindowMs(AppBatcher.BatchType.MAP))
        assertEquals(4 * 1024, AppBatcher.MAX_BATCH_BYTES)
        assertEquals(2_400, AppBatcher.HARD_MAX_PAYLOAD_BYTES)
        assertEquals(15 * 60_000L, AppBatcher.DEFERRED_BATCH_TTL_MS)
    }

    @Test
    fun canonicalAndDedicatedActionsCanShareOneAppBatch() {
        val broadcaster = RecordingBroadcaster(true)
        enqueue(broadcaster, EventTypes.MAP_MARKER_ADD, "canonical")
        enqueue(broadcaster, EventTypes.MAP_ITEM_CONFIRM, "action")

        AppBatcher.flushForTest(EventTypes.MAP_MARKER_ADD, broadcaster)

        assertTrue(broadcaster.payloads.single().size <= AppBatcher.HARD_MAX_PAYLOAD_BYTES)
        assertEquals(listOf("canonical", "action"), emittedEventIds(broadcaster.payloads.single()))
    }

    @Test
    fun multipleDedicatedActionsCanShareOneAppBatch() {
        val broadcaster = RecordingBroadcaster(true)
        enqueue(broadcaster, EventTypes.MAP_ITEM_CONFIRM, "confirm")
        enqueue(broadcaster, EventTypes.MAP_ITEM_NOTE, "note")

        AppBatcher.flushForTest(EventTypes.MAP_ITEM_CONFIRM, broadcaster)

        assertEquals(listOf("confirm", "note"), emittedEventIds(broadcaster.payloads.single()))
    }

    @Test
    fun deferredReleaseBroadcastsTheExactOriginalPayloadBytes() {
        val broadcaster = RecordingBroadcaster(false, true)
        enqueue(broadcaster, EventTypes.MAP_ITEM_ACKNOWLEDGE, "action")
        AppBatcher.flushForTest(EventTypes.MAP_ITEM_ACKNOWLEDGE, broadcaster)

        AppBatcher.releaseDeferred(broadcaster, "contract_test")

        assertEquals(2, broadcaster.payloads.size)
        assertArrayEquals(broadcaster.payloads[0], broadcaster.payloads[1])
    }

    @Test
    fun failedDeferredReleaseRetainsTheSamePayloadForRetry() {
        val broadcaster = RecordingBroadcaster(false, false, true)
        enqueue(broadcaster, EventTypes.MAP_ITEM_SITUATION_UPDATE, "action")
        AppBatcher.flushForTest(EventTypes.MAP_ITEM_SITUATION_UPDATE, broadcaster)

        AppBatcher.releaseDeferred(broadcaster, "first_retry")
        AppBatcher.releaseDeferred(broadcaster, "second_retry")

        assertEquals(3, broadcaster.payloads.size)
        assertArrayEquals(broadcaster.payloads[0], broadcaster.payloads[1])
        assertArrayEquals(broadcaster.payloads[0], broadcaster.payloads[2])
    }

    @Test
    fun duplicatePendingSenderEventDoesNotCreateAnotherSerializedItem() {
        val broadcaster = RecordingBroadcaster(true)
        enqueue(broadcaster, EventTypes.MAP_ITEM_NOTE, "same-event")
        enqueue(broadcaster, EventTypes.MAP_ITEM_NOTE, "same-event")

        AppBatcher.flushForTest(EventTypes.MAP_ITEM_NOTE, broadcaster)

        assertEquals(listOf("same-event"), emittedEventIds(broadcaster.payloads.single()))
    }

    @Test
    fun nonMapBatchClassificationRemainsUnchanged() {
        val expected = mapOf(
            EventTypes.CHAT_MSG to AppBatcher.BatchType.MESSAGE,
            EventTypes.SYS_ACK to AppBatcher.BatchType.ACK,
            EventTypes.SYS_GROUP_REKEY to AppBatcher.BatchType.MEMBERSHIP_CONTROL,
            EventTypes.SYS_GROUP_KEY_SHARE to AppBatcher.BatchType.MEMBERSHIP_CONTROL,
            EventTypes.LIVE_TRAIL_START to AppBatcher.BatchType.TRAIL,
            "FUTURE.EVENT" to AppBatcher.BatchType.OTHER,
        )
        expected.forEach { (type, lane) -> assertEquals(type, lane, AppBatcher.batchTypeFor(type)) }
        assertEquals(1_000L, AppBatcher.batchWindowMs(AppBatcher.BatchType.MESSAGE))
        assertEquals(10_000L, AppBatcher.batchWindowMs(AppBatcher.BatchType.ACK))
        assertEquals(0L, AppBatcher.batchWindowMs(AppBatcher.BatchType.MEMBERSHIP_CONTROL))
        assertEquals(8_000L, AppBatcher.batchWindowMs(AppBatcher.BatchType.TRAIL))
        assertEquals(5_000L, AppBatcher.batchWindowMs(AppBatcher.BatchType.OTHER))
    }

    private fun enqueue(broadcaster: EnvelopeBroadcaster, type: String, eventId: String) {
        assertTrue(AppBatcher.enqueue(broadcaster, "gid", type, envelopeBytes(eventId)))
    }

    private fun envelopeBytes(eventId: String): ByteArray = CanonicalJson.json.encodeToString(
        EventEnvelope.serializer(),
        EventEnvelope(1, "gid", eventId, 1_700_000_000L, 60, enc = EncPayload("ciphertext")),
    ).toByteArray()

    private fun emittedEventIds(payload: ByteArray): List<String> {
        val batch = CanonicalJson.json.decodeFromString<AppBatchEnvelope>(payload.toString(Charsets.UTF_8))
        return batch.items.map { encoded ->
            CanonicalJson.json.decodeFromString<EventEnvelope>(Base64Url.decode(encoded).toString(Charsets.UTF_8)).mid
        }
    }

    private class RecordingBroadcaster(vararg decisions: Boolean) : EnvelopeBroadcaster {
        private val decisions = ArrayDeque(decisions.toList())
        val payloads = mutableListOf<ByteArray>()
        override fun broadcastEnvelope(blob: ByteArray): Boolean {
            payloads += blob.copyOf()
            return if (decisions.isEmpty()) true else decisions.removeFirst()
        }
    }

    private companion object {
        val mapTypes = listOf(
            EventTypes.MAP_MARKER_ADD,
            EventTypes.MAP_MARKER_UPD,
            EventTypes.MAP_MARKER_DEL,
            EventTypes.MAP_AREA_ADD,
            EventTypes.MAP_AREA_UPD,
            EventTypes.MAP_AREA_DEL,
            EventTypes.MAP_ITEM_CONFIRM,
            EventTypes.MAP_ITEM_SITUATION_UPDATE,
            EventTypes.MAP_ITEM_SITUATION_STATUS,
            EventTypes.MAP_ITEM_ACKNOWLEDGE,
            EventTypes.MAP_ITEM_NOTE,
        )
    }
}
