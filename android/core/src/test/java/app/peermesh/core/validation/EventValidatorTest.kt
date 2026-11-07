package app.peermesh.core.validation

import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.core.types.EventTypes
import app.peermesh.core.map.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventValidatorTest {
    @Test
    fun validate_acceptsChatMessage() {
        val data = buildJsonObject { put("text", JsonPrimitive("hi")) }
        val core = baseCore(type = EventTypes.CHAT_MSG, data = data)
        assertTrue(EventValidator.validate(core))
    }

    @Test
    fun validate_rejectsLongChatText() {
        val data = buildJsonObject { put("text", JsonPrimitive("x".repeat(201))) }
        val core = baseCore(type = EventTypes.CHAT_MSG, data = data)
        assertFalse(EventValidator.validate(core))
    }

    @Test
    fun validate_acceptsChatReplyWithRefParent() {
        val data = buildJsonObject {
            put("text", JsonPrimitive("reply"))
        }
        val core = baseCore(
            type = EventTypes.CHAT_REPLY,
            data = data,
            ref = EventRef(root = null, parent = "parent-id", target = null),
        )
        assertTrue(EventValidator.validate(core))
    }

    @Test
    fun validate_rejectsChatReplyMissingReplyTo() {
        val data = buildJsonObject {
            put("text", JsonPrimitive("reply"))
        }
        val core = baseCore(type = EventTypes.CHAT_REPLY, data = data)
        assertFalse(EventValidator.validate(core))
    }

    @Test
    fun validate_rejectsChatReplyPointingToSelf() {
        val data = buildJsonObject {
            put("text", JsonPrimitive("reply"))
        }
        val core = baseCore(
            type = EventTypes.CHAT_REPLY,
            data = data,
            ref = EventRef(root = null, parent = "event-id", target = null),
        )
        assertFalse(EventValidator.validate(core))
    }

    @Test
    fun validate_acceptsChatEditWithTarget() {
        val data = buildJsonObject {
            put("text", JsonPrimitive("edited"))
            put("tct", JsonPrimitive(1700000000L))
        }
        val core = baseCore(
            type = EventTypes.CHAT_EDIT,
            data = data,
            ref = EventRef(root = null, parent = null, target = "message-id"),
        )
        assertTrue(EventValidator.validate(core))
    }

    @Test
    fun validate_rejectsChatEditMissingTarget() {
        val data = buildJsonObject {
            put("text", JsonPrimitive("edited"))
        }
        val core = baseCore(type = EventTypes.CHAT_EDIT, data = data)
        assertFalse(EventValidator.validate(core))
    }

    @Test
    fun validate_acceptsChatDeleteWithTarget() {
        val data = buildJsonObject {
            put("target_id", JsonPrimitive("message-id"))
        }
        val core = baseCore(type = EventTypes.CHAT_DEL, data = data)
        assertTrue(EventValidator.validate(core))
    }

    @Test
    fun validate_rejectsChatDeleteMissingTarget() {
        val core = baseCore(type = EventTypes.CHAT_DEL, data = buildJsonObject {})
        assertFalse(EventValidator.validate(core))
    }

    @Test
    fun validate_rejectsMarkerMissingCoords() {
        val data = buildJsonObject { put("note", JsonPrimitive("test")) }
        val core = baseCore(type = EventTypes.MAP_MARKER_ADD, data = data)
        assertFalse(EventValidator.validate(core))
    }

    @Test
    fun validate_rejectsDedicatedIdentityAndTargetMismatches() {
        val valid = confirmation()
        assertFalse(EventValidator.validate(valid.copy(id = "other")))
        assertFalse(EventValidator.validate(valid.copy(time = valid.time.copy(t = valid.time.t + 1))))
        assertFalse(EventValidator.validate(valid.copy(ref = EventRef(target = " "))))
        assertFalse(EventValidator.validate(valid.with("item_type", "point")))
        assertFalse(EventValidator.validate(valid.copy(time = valid.time.copy(lt = -1))))
    }

    @Test
    fun validate_rejectsEventActionMismatchAndUnknownStableValues() {
        val valid = confirmation()
        assertFalse(EventValidator.validate(valid.with("map_action", "add_update")))
        assertFalse(EventValidator.validate(valid.copy(type = EventTypes.MAP_ITEM_SITUATION_UPDATE)))
        assertFalse(EventValidator.validate(valid.with("map_action", "future_action")))
        assertFalse(EventValidator.validate(valid.with("information_source", "observed")))
        assertFalse(EventValidator.validate(valid.with("map_domain_v", 2L)))

        val status = MapItemEventCodec.encode(
            MapItemType.AREA,
            MapSituationStatusUpdate(metadata(), MapSituationLifecycleStatus.NO_LONGER_ACTIVE, null, null),
        )
        assertFalse(EventValidator.validate(status.with("situation_lifecycle", "active")))
        assertFalse(EventValidator.validate(status.with("situation_lifecycle", "future")))

        val update = MapItemEventCodec.encode(
            MapItemType.MARKER,
            MapSituationUpdate(metadata(), "Changed", null, MapSituationUpdateType.SITUATION_CHANGED),
        )
        assertFalse(EventValidator.validate(update.with("situation_update_type", "future")))
    }

    @Test
    fun validate_enforcesDedicatedPayloadShape() {
        val confirm = confirmation()
        assertTrue(EventValidator.validate(confirm.with("information_source", "uncertain").with("action_note", "Support")))
        assertFalse(EventValidator.validate(confirm.with("lat", 1L)))
        assertFalse(EventValidator.validate(confirm.with("geometry", "{}")))
        assertFalse(EventValidator.validate(confirm.with("target_id", "different-item")))

        val update = MapItemEventCodec.encode(
            MapItemType.MARKER,
            MapSituationUpdate(metadata(), "Changed", null, MapSituationUpdateType.SITUATION_CHANGED),
        )
        assertFalse(EventValidator.validate(update.without("action_note")))
        assertFalse(EventValidator.validate(update.with("action_note", "   ")))
        assertFalse(EventValidator.validate(update.with("situation_lifecycle", "no_longer_active")))

        val status = MapItemEventCodec.encode(
            MapItemType.AREA,
            MapSituationStatusUpdate(metadata(), MapSituationLifecycleStatus.NO_LONGER_ACTIVE, "Done", MapInformationSource.FIRSTHAND),
        )
        assertTrue(EventValidator.validate(status))

        val ack = MapItemEventCodec.encode(MapItemType.MARKER, MapCoordinationAcknowledgement(metadata()))
        listOf("information_source", "action_note", "confidence").forEach { field ->
            assertFalse(EventValidator.validate(ack.with(field, "bad")))
        }

        val note = MapItemEventCodec.encode(MapItemType.AREA, MapCoordinationNote(metadata(), "Meet here"))
        assertFalse(EventValidator.validate(note.with("action_note", " ")))
        assertFalse(EventValidator.validate(note.with("information_source", "firsthand")))
    }

    @Test
    fun validate_enforcesDomainV1AddWithoutChangingLegacyAdd() {
        val legacy = buildJsonObject {
            put("lat", 1.0); put("lon", 2.0); put("kind", "road_closed"); put("confidence", 2)
        }
        assertTrue(EventValidator.validate(baseCore(EventTypes.MAP_MARKER_ADD, legacy)))
        assertFalse(EventValidator.validate(baseCore(EventTypes.MAP_MARKER_ADD, JsonObject(legacy + mapOf(
            "map_domain_v" to JsonPrimitive(1), "item_category" to JsonPrimitive("situation")
        )))))
        val situation = JsonObject(legacy + mapOf(
            "map_domain_v" to JsonPrimitive(1),
            "item_category" to JsonPrimitive("situation"),
            "information_source" to JsonPrimitive("firsthand"),
        ))
        assertTrue(EventValidator.validate(baseCore(EventTypes.MAP_MARKER_ADD, situation)))
        assertFalse(EventValidator.validate(baseCore(EventTypes.MAP_MARKER_ADD, JsonObject(situation + ("item_category" to JsonPrimitive("coordination"))))))

        val coordination = JsonObject(legacy + mapOf(
            "kind" to JsonPrimitive("camp"),
            "map_domain_v" to JsonPrimitive(1),
            "item_category" to JsonPrimitive("coordination"),
        ))
        assertTrue(EventValidator.validate(baseCore(EventTypes.MAP_MARKER_ADD, coordination)))
        assertFalse(EventValidator.validate(baseCore(EventTypes.MAP_MARKER_ADD, JsonObject(coordination + ("information_source" to JsonPrimitive("firsthand"))))))
    }

    private fun metadata() = MapActionMetadata("event-id", "item-id", "device", 1_700_000_000, 1)
    private fun confirmation() = MapItemEventCodec.encode(MapItemType.MARKER, MapConfirmation(metadata(), null, null))
    private fun EventCore.with(key: String, value: String): EventCore = copy(data = JsonObject((data as JsonObject) + (key to JsonPrimitive(value))))
    private fun EventCore.with(key: String, value: Long): EventCore = copy(data = JsonObject((data as JsonObject) + (key to JsonPrimitive(value))))
    private fun EventCore.without(key: String): EventCore = copy(data = JsonObject((data as JsonObject) - key))

    private fun baseCore(
        type: String,
        data: kotlinx.serialization.json.JsonElement,
        ref: EventRef = EventRef(root = null, parent = null, target = null),
    ): EventCore {
        return EventCore(
            ev = 1,
            id = "event-id",
            type = type,
            actor = Actor(did = "device"),
            time = EventTime(t = 1700000000L, lt = 1L),
            ref = ref,
            meta = null,
            data = data,
        )
    }
}
