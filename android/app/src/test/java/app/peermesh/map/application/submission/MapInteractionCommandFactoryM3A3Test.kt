package app.peermesh.map.application.submission

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemEventCodec
import app.peermesh.core.map.MapItemInteractionEvent
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.core.types.EventTypes
import app.peermesh.core.validation.EventValidator
import app.peermesh.map.application.detail.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapInteractionCommandFactoryM3A3Test {
    private val factory = DefaultMapInteractionEventFactoryAdapter()
    private val context = MapEventConstructionContext("trusted-actor", "event-1", 100L, 101L, 102L)

    @Test
    fun `all commands map to one exact dedicated event contract`() {
        val cases = listOf(
            ConfirmSituationCommand(target(MapItemType.MARKER), MapInformationSource.FIRSTHAND, "confirmed") to
                EventTypes.MAP_ITEM_CONFIRM,
            AddSituationUpdateCommand(
                target(MapItemType.MARKER), "changed", MapSituationUpdateType.SITUATION_CHANGED,
                MapInformationSource.REPORTED_BY_OTHERS,
            ) to EventTypes.MAP_ITEM_SITUATION_UPDATE,
            ResolveSituationCommand(target(MapItemType.AREA), "ended", MapInformationSource.UNCERTAIN) to
                EventTypes.MAP_ITEM_SITUATION_STATUS,
            AcknowledgeCoordinationCommand(target(MapItemType.AREA)) to EventTypes.MAP_ITEM_ACKNOWLEDGE,
            AddCoordinationNoteCommand(target(MapItemType.MARKER), "bring water") to EventTypes.MAP_ITEM_NOTE,
        )

        cases.forEach { (command, expectedType) ->
            val core = factory.create(command, context)
            assertEquals(expectedType, core.type)
            assertTrue(EventValidator.validate(core))
            assertEquals(context.eventId, core.id)
            assertEquals(context.actorDid, core.actor.did)
            assertEquals(context.createdSeconds, core.time.t)
            assertEquals(context.lamport, core.time.lt)
            assertEquals(command.target.itemId, core.ref.target)
        }
    }

    @Test
    fun `decoded contracts preserve action fields and force resolution lifecycle`() {
        val update = MapItemEventCodec.decode(factory.create(
            AddSituationUpdateCommand(
                target(MapItemType.MARKER), "raw content", MapSituationUpdateType.STILL_ACTIVE,
                MapInformationSource.FIRSTHAND,
            ),
            context,
        )) as MapItemInteractionEvent.SituationUpdate
        assertEquals("raw content", update.action.content)
        assertEquals(MapSituationUpdateType.STILL_ACTIVE, update.action.updateType)
        assertEquals(MapInformationSource.FIRSTHAND, update.action.source)

        val resolved = MapItemEventCodec.decode(factory.create(
            ResolveSituationCommand(target(MapItemType.AREA), note = "done"),
            context,
        )) as MapItemInteractionEvent.SituationStatus
        assertEquals(MapSituationLifecycleStatus.NO_LONGER_ACTIVE, resolved.action.status)
        assertEquals("done", resolved.action.note)
    }

    @Test
    fun `same raw id remains marker and area type distinct`() {
        val marker = MapItemEventCodec.decode(factory.create(
            AcknowledgeCoordinationCommand(target(MapItemType.MARKER)), context,
        )) as MapItemInteractionEvent.Acknowledgement
        val area = MapItemEventCodec.decode(factory.create(
            AcknowledgeCoordinationCommand(target(MapItemType.AREA)), context,
        )) as MapItemInteractionEvent.Acknowledgement

        assertEquals(MapItemType.MARKER, marker.itemType)
        assertEquals(MapItemType.AREA, area.itemType)
        assertEquals(marker.action.metadata.itemId, area.action.metadata.itemId)
    }

    @Test
    fun `acknowledgement and coordination note expose no forbidden command fields`() {
        assertEquals(setOf("target"), AcknowledgeCoordinationCommand::class.java.declaredFields
            .filterNot { it.isSynthetic }.map { it.name }.toSet())
        assertEquals(setOf("target", "content"), AddCoordinationNoteCommand::class.java.declaredFields
            .filterNot { it.isSynthetic }.map { it.name }.toSet())
        assertTrue(ResolveSituationCommand::class.java.declaredFields.none { it.name.contains("status", true) })
        listOf(
            ConfirmSituationCommand::class.java,
            AddSituationUpdateCommand::class.java,
            ResolveSituationCommand::class.java,
            AcknowledgeCoordinationCommand::class.java,
            AddCoordinationNoteCommand::class.java,
        ).forEach { commandClass ->
            assertTrue(commandClass.simpleName, commandClass.declaredFields.filterNot { it.isSynthetic }.none {
                it.name.contains("actor", true) || it.name.contains("lamport", true) ||
                    it.name.contains("eventId", true) || it.name.contains("timestamp", true)
            })
        }
    }

    private fun target(type: MapItemType) = MapItemIdentity("group-1", type, "same-id")
}
