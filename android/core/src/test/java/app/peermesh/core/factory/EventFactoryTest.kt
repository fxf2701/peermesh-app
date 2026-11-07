package app.peermesh.core.factory

import app.peermesh.core.types.EventTypes
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapActionMetadata
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.validation.EventValidator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EventFactoryTest {
    @Test
    fun chatMessage_createsReplyTypeWhenReplyToSet() {
        val core = EventFactory.chatMessage(
            eventId = "id",
            actorDid = "did",
            createdSeconds = 1L,
            lamport = 1L,
            text = "hi",
            replyTo = "parent",
        )
        assertEquals(EventTypes.CHAT_REPLY, core.type)
        val data = core.data as JsonObject
        assertFalse(data.containsKey("reply_to"))
        assertEquals("parent", core.ref.parent)
    }

    @Test
    fun chatEdit_createsTargetInRefAndTargetCreatedTimeInPayload() {
        val core = EventFactory.chatEdit(
            eventId = "edit-id",
            actorDid = "did",
            createdSeconds = 2L,
            lamport = 2L,
            targetMessageId = "target-id",
            targetCreatedSeconds = 1L,
            text = "edited",
        )
        assertEquals(EventTypes.CHAT_EDIT, core.type)
        val data = core.data as JsonObject
        assertEquals(1L, (data["tct"] as JsonPrimitive).content.toLong())
        assertEquals("target-id", core.ref.target)
        assertFalse(data.containsKey("target_id"))
    }

    @Test
    fun chatDelete_createsDeleteTypeWithTarget() {
        val core = EventFactory.chatDelete(
            eventId = "delete-id",
            actorDid = "did",
            createdSeconds = 1L,
            lamport = 1L,
            targetMessageId = "target-id",
        )
        assertEquals(EventTypes.CHAT_DEL, core.type)
        val data = core.data as JsonObject
        assertEquals("target-id", (data["target_id"] as JsonPrimitive).content)
        assertEquals("target-id", core.ref.target)
    }

    @Test
    fun mapMarkerAdd_includesCoords() {
        val core = EventFactory.mapMarkerAdd(
            eventId = "id",
            actorDid = "did",
            createdSeconds = 1L,
            lamport = 1L,
            markerId = "marker",
            layerId = "layer",
            lat = 10.0,
            lon = 20.0,
            radiusM = null,
            note = null,
            kind = null,
        )
        val data = core.data as JsonObject
        assertTrue(data.containsKey("lat"))
        assertTrue(data.containsKey("lon"))
        assertTrue(EventValidator.validate(core))
        assertFalse(data.containsKey("map_domain_v"))
        assertEquals("id", core.ref.root)
    }

    @Test
    fun mapAreaAdd_legacyCallRemainsValidWithoutSource() {
        val core = EventFactory.mapAreaAdd("id", "did", 1, 1, "area", "layer", buildJsonObject {}, note = null, kind = null)
        assertTrue(EventValidator.validate(core))
        assertFalse((core.data as JsonObject).containsKey("information_source"))
        assertEquals("id", core.ref.root)
    }

    @Test
    fun canonicalMutationsCarryExplicitRootWhenKnown() {
        val markerUpdate = EventFactory.mapMarkerUpdate(
            "upd", "did", 2, 2, "marker", "layer", 10.0, 20.0,
            null, note = null, kind = null, rootEventId = "marker-add",
        )
        val areaDelete = EventFactory.mapAreaDelete(
            "del", "did", 3, 3, "area", rootEventId = "area-add",
        )
        assertEquals("marker-add", markerUpdate.ref.root)
        assertEquals("area-add", areaDelete.ref.root)
    }

    @Test
    fun domainV1SituationAddCarriesCategoryAndSource() {
        val core = EventFactory.mapMarkerAdd(
            "id", "did", 1, 1, "marker", "layer", 10.0, 20.0, null,
            note = null, kind = "road_closed",
            domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND),
        )
        assertTrue(EventValidator.validate(core))
        val data = core.data as JsonObject
        assertEquals("situation", data["item_category"].text())
        assertEquals("firsthand", data["information_source"].text())
    }

    @Test
    fun typedConfirmationFactoryProducesValidEvent() {
        val action = MapConfirmation(MapActionMetadata("event", "marker", "did", 10, 2), null, null)
        val core = EventFactory.mapConfirmation(MapItemType.MARKER, action)
        assertTrue(EventValidator.validate(core))
        assertEquals("event", core.id)
        assertEquals("marker", core.ref.target)
    }

    @Test
    fun domainAwareAddFailsFastForInvalidExtension() {
        assertThrows(IllegalArgumentException::class.java) {
            EventFactory.mapMarkerAdd(
                "id", "did", 1, 1, "marker", "layer", 10.0, 20.0, null,
                note = null, kind = "road_closed",
                domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, null),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EventFactory.mapMarkerAdd(
                "id", "did", 1, 1, "marker", "layer", 10.0, 20.0, null,
                note = null, kind = "camp",
                domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EventFactory.mapAreaAdd(
                "id", "did", 1, 1, "area", "layer", buildJsonObject {},
                note = null, kind = "route_note",
                domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EventFactory.mapMarkerAdd(
                "id", "did", 1, 1, "marker", "layer", 10.0, 20.0, null,
                note = null, kind = "camp",
                domainExtension = MapCanonicalAddExtension(MapItemCategory.COORDINATION, MapInformationSource.FIRSTHAND),
            )
        }
    }

    private fun kotlinx.serialization.json.JsonElement?.text(): String? = (this as? JsonPrimitive)?.content
}
