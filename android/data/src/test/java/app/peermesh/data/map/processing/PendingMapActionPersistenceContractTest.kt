package app.peermesh.data.map.processing

import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.*
import app.peermesh.data.db.PersistenceParseResult
import app.peermesh.data.db.MapItemPersistenceRecordValidator
import org.junit.Assert.*
import org.junit.Test

/** Executable contract anchors for Phase M2C3A. Remove Ignore while implementing each invariant. */
internal class PendingMapActionPersistenceContractTest {
    @Test
    fun `entity factory requires explicit pending initial state`() {
        val interaction = MapItemInteractionEvent.Confirmation(MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("a", "missing", "peer", 15, 7), MapInformationSource.UNCERTAIN, "note"))
        val event = EventFactory.mapConfirmation(interaction.itemType, interaction.action)
        val input = MapItemActionPersistenceInput("g", event, interaction)
        val applied = (DefaultMapItemActionEntityFactory.create(input, "f".repeat(64), MapItemActionInitialState.APPLIED) as PersistenceParseResult.Success).value
        val pending = (DefaultMapItemActionEntityFactory.create(input, "f".repeat(64), MapItemActionInitialState.PENDING_TARGET) as PersistenceParseResult.Success).value
        assertEquals("applied", applied.materialization_state)
        assertEquals("pending_target", pending.materialization_state)
        assertNull(applied.rejection_reason)
        assertNull(pending.rejection_reason)
        assertTrue(applied.sameImmutableActionAs(pending))
        assertEquals(applied.copy(materialization_state = pending.materialization_state), pending)
        assertFalse(MapItemActionInitialState.entries.any { it.name == "REJECTED" })
    }

    @Test
    fun `exact pending duplicate is idempotent across materialization state`() {
        val row = row()
        val applied = row.copy(materialization_state = "applied")
        val rejected = row.copy(materialization_state = "rejected", rejection_reason = "target_resolved")
        assertTrue(MapItemPersistenceRecordValidator.validate(applied) is PersistenceParseResult.Success)
        assertTrue(MapItemPersistenceRecordValidator.validate(rejected) is PersistenceParseResult.Success)
        assertTrue(row.sameImmutableActionAs(applied))
        assertTrue(row.sameImmutableActionAs(rejected))
    }

    @Test
    fun `conflicting pending duplicate cannot overwrite immutable history`() {
        val row = row()
        assertFalse(row.sameImmutableActionAs(row.copy(action_note = "changed")))
        assertFalse(row.sameImmutableActionAs(row.copy(event_fingerprint = "b".repeat(64))))
    }

    private fun row(): app.peermesh.data.db.entity.MapItemActionEntity {
        val interaction = MapItemInteractionEvent.CoordinationNote(MapItemType.AREA,
            MapCoordinationNote(MapActionMetadata("n", "missing", "peer", 15, 7), "content"))
        val event = EventFactory.mapCoordinationNote(interaction.itemType, interaction.action)
        return (DefaultMapItemActionEntityFactory.create(MapItemActionPersistenceInput("g", event, interaction),
            "a".repeat(64), MapItemActionInitialState.PENDING_TARGET) as PersistenceParseResult.Success).value
    }
}
