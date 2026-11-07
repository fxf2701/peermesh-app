package app.peermesh.data.map.processing

import app.peermesh.core.map.*
import app.peermesh.data.db.*
import org.junit.Assert.*
import org.junit.Test

class MapItemActionEntityFactoryTest {
    @Test fun everyActionMapsStableValuesAndForbiddenFieldsStayNull() {
        val actions = listOf(
            MapItemInteractionEvent.Confirmation(MapItemType.MARKER, MapConfirmation(meta("c"), MapInformationSource.UNCERTAIN, "confirm note")),
            MapItemInteractionEvent.SituationUpdate(MapItemType.MARKER, MapSituationUpdate(meta("u"), "update", MapInformationSource.FIRSTHAND, MapSituationUpdateType.SITUATION_CHANGED)),
            MapItemInteractionEvent.SituationStatus(MapItemType.MARKER, MapSituationStatusUpdate(meta("s"), MapSituationLifecycleStatus.NO_LONGER_ACTIVE, "ended", MapInformationSource.REPORTED_BY_OTHERS)),
            MapItemInteractionEvent.Acknowledgement(MapItemType.MARKER, MapCoordinationAcknowledgement(meta("a"))),
            MapItemInteractionEvent.CoordinationNote(MapItemType.MARKER, MapCoordinationNote(meta("n"), "coord note")),
        )
        val rows = actions.map { create(it) }
        rows.forEach { row ->
            assertEquals(row.event_id, row.action_id); assertEquals(1, row.map_domain_v)
            assertEquals(15, row.action_t); assertEquals(7, row.action_lt)
            assertEquals("applied", row.materialization_state); assertNull(row.rejection_reason)
            assertTrue(MapItemPersistenceRecordValidator.validate(row) is PersistenceParseResult.Success)
        }
        assertEquals("uncertain", rows[0].information_source); assertEquals("confirm note", rows[0].action_note); assertNull(rows[0].update_disposition); assertNull(rows[0].target_lifecycle)
        assertEquals("situation_changed", rows[1].update_disposition); assertEquals("update", rows[1].action_note); assertNull(rows[1].target_lifecycle)
        assertEquals("no_longer_active", rows[2].target_lifecycle); assertNull(rows[2].update_disposition)
        assertNull(rows[3].information_source); assertNull(rows[3].action_note); assertNull(rows[3].update_disposition); assertNull(rows[3].target_lifecycle)
        assertNull(rows[4].information_source); assertEquals("coord note", rows[4].action_note); assertNull(rows[4].update_disposition); assertNull(rows[4].target_lifecycle)
    }

    @Test fun explicitInitialStateChangesOnlyMaterializationMetadata() {
        val interaction = MapItemInteractionEvent.Confirmation(
            MapItemType.MARKER,
            MapConfirmation(meta("state"), MapInformationSource.UNCERTAIN, "note"),
        )
        val applied = create(interaction, MapItemActionInitialState.APPLIED)
        val pending = create(interaction, MapItemActionInitialState.PENDING_TARGET)

        assertEquals("applied", applied.materialization_state)
        assertEquals("pending_target", pending.materialization_state)
        assertNull(applied.rejection_reason)
        assertNull(pending.rejection_reason)
        assertEquals(applied.copy(materialization_state = pending.materialization_state), pending)
        assertFalse(MapItemActionInitialState.entries.any { it.name == "REJECTED" })
    }

    private fun create(
        interaction: MapItemInteractionEvent,
        initialState: MapItemActionInitialState = MapItemActionInitialState.APPLIED,
    ): app.peermesh.data.db.entity.MapItemActionEntity {
        val event = when (interaction) {
            is MapItemInteractionEvent.Confirmation -> MapItemEventCodec.encode(interaction.itemType, interaction.action)
            is MapItemInteractionEvent.SituationUpdate -> MapItemEventCodec.encode(interaction.itemType, interaction.action)
            is MapItemInteractionEvent.SituationStatus -> MapItemEventCodec.encode(interaction.itemType, interaction.action)
            is MapItemInteractionEvent.Acknowledgement -> MapItemEventCodec.encode(interaction.itemType, interaction.action)
            is MapItemInteractionEvent.CoordinationNote -> MapItemEventCodec.encode(interaction.itemType, interaction.action)
        }
        return (DefaultMapItemActionEntityFactory.create(MapItemActionPersistenceInput("g", event, interaction),
            "f".repeat(64),
            initialState,
        ) as PersistenceParseResult.Success).value
    }
    private fun meta(id: String) = MapActionMetadata(id, "m", "peer", 15, 7)
}
