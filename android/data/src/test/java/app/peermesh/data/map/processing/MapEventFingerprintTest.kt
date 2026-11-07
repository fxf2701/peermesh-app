package app.peermesh.data.map.processing

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.map.*
import app.peermesh.core.model.EventCore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class MapEventFingerprintTest {
    @Test fun digestIsDeterministicAndRoundTripStable() {
        val event = event()
        val digest = Sha256MapEventFingerprint.sha256(event)
        assertEquals(64, digest.length)
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(digest, Sha256MapEventFingerprint.sha256(event))
        val roundTrip = CanonicalJson.json.decodeFromString<EventCore>(CanonicalJson.json.encodeToString(event))
        assertEquals(digest, Sha256MapEventFingerprint.sha256(roundTrip))
    }

    @Test fun everyWireSemanticFamilyChangesDigest() {
        val event = event()
        val original = Sha256MapEventFingerprint.sha256(event)
        val changedPayload = event.copy(data = JsonObject((event.data as JsonObject) + ("action_note" to JsonPrimitive("changed"))))
        listOf(changedPayload, event.copy(id = "other"), event.copy(type = "MAP.ITEM.NOTE"),
            event.copy(actor = event.actor.copy(did = "other")), event.copy(time = event.time.copy(t = 12)),
            event.copy(ref = event.ref.copy(target = "other"))).forEach {
            assertNotEquals(original, Sha256MapEventFingerprint.sha256(it))
        }
    }

    private fun event() = MapItemEventCodec.encode(MapItemType.MARKER,
        MapConfirmation(MapActionMetadata("action", "m", "peer", 10, 2), MapInformationSource.FIRSTHAND, "note"))
}
