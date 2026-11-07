package app.peermesh.core.map

import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.core.validation.EventValidator
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MapItemEventContractTest {
    private fun metadata(id: String = "event-1", item: String = "item-1") = MapActionMetadata(
        actionId = id,
        itemId = item,
        actorDid = "did:peer:actor",
        timestampSeconds = 1_700_000_000L,
        eventLamport = 12L,
    )

    @Test
    fun `dedicated event types are map types with stable action mapping`() {
        val expected = mapOf(
            EventTypes.MAP_ITEM_CONFIRM to MapItemAction.CONFIRM,
            EventTypes.MAP_ITEM_SITUATION_UPDATE to MapItemAction.ADD_UPDATE,
            EventTypes.MAP_ITEM_SITUATION_STATUS to MapItemAction.UPDATE_STATUS,
            EventTypes.MAP_ITEM_ACKNOWLEDGE to MapItemAction.ACKNOWLEDGE,
            EventTypes.MAP_ITEM_NOTE to MapItemAction.ADD_NOTE,
        )
        expected.forEach { (type, action) ->
            assertTrue(EventTypes.isMap(type))
            assertEquals(action, MapItemEventContract.actionForEventType(type))
            assertEquals(type, MapItemEventContract.eventTypeFor(action))
        }
        assertEquals("MAP.MARKER.ADD", EventTypes.MAP_MARKER_ADD)
        assertEquals("MAP.AREA.DEL", EventTypes.MAP_AREA_DEL)
    }

    @Test
    fun `all typed actions round trip for marker and area targets`() {
        val cases = listOf(
            MapItemType.MARKER to MapConfirmation(metadata(), MapInformationSource.FIRSTHAND, "Seen now"),
            MapItemType.AREA to MapConfirmation(metadata("event-2"), null, null),
            MapItemType.MARKER to MapSituationUpdate(metadata("event-3"), "Water moved", MapInformationSource.UNCERTAIN, MapSituationUpdateType.SITUATION_CHANGED),
            MapItemType.AREA to MapSituationStatusUpdate(metadata("event-4"), MapSituationLifecycleStatus.NO_LONGER_ACTIVE, "Cleared", MapInformationSource.REPORTED_BY_OTHERS),
            MapItemType.MARKER to MapCoordinationAcknowledgement(metadata("event-5")),
            MapItemType.AREA to MapCoordinationNote(metadata("event-6"), "Meet at north edge"),
        )
        cases.forEach { (itemType, action) ->
            val event = when (action) {
                is MapConfirmation -> MapItemEventCodec.encode(itemType, action)
                is MapSituationUpdate -> MapItemEventCodec.encode(itemType, action)
                is MapSituationStatusUpdate -> MapItemEventCodec.encode(itemType, action)
                is MapCoordinationAcknowledgement -> MapItemEventCodec.encode(itemType, action)
                is MapCoordinationNote -> MapItemEventCodec.encode(itemType, action)
                else -> error("unexpected")
            }
            assertTrue(EventValidator.validate(event))
            assertEquals(event.id, (event.data as JsonObject)[MapDomainPayloadContract.ACTION_ID_FIELD].text())
            assertEquals(event.time.t.toString(), (event.data as JsonObject)[MapDomainPayloadContract.ACTION_TIMESTAMP_FIELD].toString())
            val serialized = CanonicalJson.json.encodeToString(event)
            val decodedCore = CanonicalJson.json.decodeFromString<EventCore>(serialized)
            val decoded = MapItemEventCodec.decode(decodedCore)
            assertEquals(itemType, decoded?.itemType)
            assertEquals(action, decoded.actionRecord())
        }
    }

    @Test
    fun `factory uses stable wire values rather than enum names`() {
        val event = MapItemEventCodec.encode(
            MapItemType.MARKER,
            MapSituationUpdate(metadata(), "Still blocked", MapInformationSource.REPORTED_BY_OTHERS, MapSituationUpdateType.STILL_ACTIVE),
        )
        val json = CanonicalJson.json.encodeToString(event)
        assertTrue(json.contains("reported_by_others"))
        assertTrue(json.contains("still_active"))
        assertFalse(json.contains("REPORTED_BY_OTHERS"))
        assertFalse(json.contains("STILL_ACTIVE"))
    }

    @Test
    fun `unknown optional version-one field is tolerated`() {
        val event = MapItemEventCodec.encode(MapItemType.AREA, MapConfirmation(metadata(), null, null))
        val data = JsonObject((event.data as JsonObject) + ("future_hint" to JsonPrimitive("ignored")))
        assertTrue(EventValidator.validate(event.copy(data = data)))
    }

    @Test
    fun `area confirmation contains no geometry or canonical mutation fields`() {
        val event = MapItemEventCodec.encode(MapItemType.AREA, MapConfirmation(metadata(), null, null))
        val data = event.data as JsonObject
        assertFalse(data.containsKey("geometry"))
        assertTrue(data.keys.none { it in MapItemEventCodec.forbiddenCanonicalFields })
    }

    @Test
    fun `canonical events are never decoded as dedicated actions`() {
        val action = MapItemEventCodec.encode(MapItemType.MARKER, MapConfirmation(metadata(), null, null))
        assertNull(MapItemEventCodec.decode(action.copy(type = EventTypes.MAP_MARKER_UPD)))
    }

    @Test
    fun `domain version overflow is rejected by decoder and validator`() {
        val event = MapItemEventCodec.encode(MapItemType.MARKER, MapConfirmation(metadata(), null, null))
            .with(MapDomainPayloadContract.EXTENSION_VERSION_FIELD, 4_294_967_297L)
        assertNull(MapItemEventCodec.decode(event))
        assertFalse(EventValidator.validate(event))
        listOf<JsonPrimitive>(
            JsonPrimitive("1"),
            JsonPrimitive(1.5),
            JsonPrimitive(Long.MAX_VALUE),
            JsonPrimitive(0L),
            JsonPrimitive(2L),
        ).forEach { version ->
            val malformed = event.copy(data = JsonObject((event.data as JsonObject) +
                (MapDomainPayloadContract.EXTENSION_VERSION_FIELD to version)))
            assertNull(MapItemEventCodec.decode(malformed))
            assertFalse(EventValidator.validate(malformed))
        }
    }

    @Test
    fun `decoder rejects unsupported event core version`() {
        val event = MapItemEventCodec.encode(MapItemType.MARKER, MapConfirmation(metadata(), null, null)).copy(ev = 2)
        assertNull(MapItemEventCodec.decode(event))
        assertFalse(EventValidator.validate(event))
    }

    @Test
    fun `decoder preserves exact nonblank canonical target identity`() {
        val target = "  item-with-space  "
        val event = MapItemEventCodec.encode(
            MapItemType.MARKER,
            MapConfirmation(metadata(item = target), null, null),
        )
        val decoded = MapItemEventCodec.decode(event) as MapItemInteractionEvent.Confirmation
        assertEquals(target, decoded.action.metadata.itemId)
        assertEquals(target, event.ref.target)
        assertNull(
            MapItemEventCodec.decode(event.copy(ref = app.peermesh.core.model.EventRef(target = "   "))),
        )
    }

    @Test
    fun `typed construction fails fast on invalid action records`() {
        val invalidMetadata = metadata(id = " ")
        assertThrows(IllegalArgumentException::class.java) {
            MapItemEventCodec.encode(MapItemType.MARKER, MapConfirmation(invalidMetadata, null, null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapItemEventCodec.encode(MapItemType.MARKER, MapSituationUpdate(metadata(), " ", null, MapSituationUpdateType.STILL_ACTIVE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapItemEventCodec.encode(MapItemType.AREA, MapCoordinationNote(metadata(), "   "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapItemEventCodec.encode(MapItemType.MARKER, MapSituationStatusUpdate(metadata(), MapSituationLifecycleStatus.ACTIVE, null, null))
        }
    }

    private fun EventCore.with(key: String, value: Long): EventCore =
        copy(data = JsonObject((data as JsonObject) + (key to JsonPrimitive(value))))

    private fun kotlinx.serialization.json.JsonElement?.text(): String? =
        (this as? JsonPrimitive)?.content

    private fun MapItemInteractionEvent?.actionRecord(): Any? = when (this) {
        is MapItemInteractionEvent.Confirmation -> action
        is MapItemInteractionEvent.SituationUpdate -> action
        is MapItemInteractionEvent.SituationStatus -> action
        is MapItemInteractionEvent.Acknowledgement -> action
        is MapItemInteractionEvent.CoordinationNote -> action
        null -> null
    }
}
