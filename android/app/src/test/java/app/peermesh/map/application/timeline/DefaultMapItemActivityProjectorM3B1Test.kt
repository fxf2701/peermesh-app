package app.peermesh.map.application.timeline

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemAction
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.data.db.MapActionOrderKey
import app.peermesh.data.map.MapItemActionRead
import app.peermesh.data.map.MapItemIdentity as DataMapItemIdentity
import app.peermesh.map.application.detail.MapItemIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultMapItemActivityProjectorM3B1Test {
    private val projector = DefaultMapItemActivityProjector()

    @Test fun mapsAllFiveDedicatedActionFamiliesAndAllowedFields() {
        val cases = listOf(
            action(
                action = MapItemAction.CONFIRM,
                source = MapInformationSource.REPORTED_BY_OTHERS,
                note = "possibly flooded",
            ) to Expected(
                MapItemActivityType.SITUATION_CONFIRMED,
                MapInformationSource.REPORTED_BY_OTHERS,
                "possibly flooded",
                null,
                null,
            ),
            action(
                action = MapItemAction.ADD_UPDATE,
                source = MapInformationSource.FIRSTHAND,
                note = "  water rising  ",
                disposition = MapSituationUpdateType.SITUATION_CHANGED,
            ) to Expected(
                MapItemActivityType.SITUATION_UPDATED,
                MapInformationSource.FIRSTHAND,
                "  water rising  ",
                MapSituationUpdateType.SITUATION_CHANGED,
                null,
            ),
            action(
                action = MapItemAction.UPDATE_STATUS,
                source = MapInformationSource.UNCERTAIN,
                note = "reported clear",
                lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
            ) to Expected(
                MapItemActivityType.SITUATION_MARKED_NO_LONGER_ACTIVE,
                MapInformationSource.UNCERTAIN,
                "reported clear",
                null,
                MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
            ),
            action(action = MapItemAction.ACKNOWLEDGE) to Expected(
                MapItemActivityType.COORDINATION_ACKNOWLEDGED,
                null,
                null,
                null,
                null,
            ),
            action(action = MapItemAction.ADD_NOTE, note = "need blankets") to Expected(
                MapItemActivityType.COORDINATION_NOTE_ADDED,
                null,
                "need blankets",
                null,
                null,
            ),
        )

        cases.forEach { (read, expected) ->
            val entry = checkNotNull(projector.project(MARKER, read))
            assertEquals(expected.type, entry.type)
            assertEquals(expected.source, entry.informationSource)
            assertEquals(expected.note, entry.note)
            assertEquals(expected.disposition, entry.updateDisposition)
            assertEquals(expected.lifecycle, entry.targetLifecycle)
        }
    }

    @Test fun preservesExactRequestedMarkerAndAreaIdentity() {
        assertEquals(MARKER, projector.project(MARKER, action())!!.itemIdentity)
        val area = MapItemIdentity("g", MapItemType.AREA, "area-1")
        assertEquals(
            area,
            projector.project(area, action(identity = DataMapItemIdentity(MapItemType.AREA, "area-1")))!!.itemIdentity,
        )
    }

    @Test fun rejectsIdentityAndRequiredMetadataViolations() {
        val invalid = listOf(
            action(gid = "other"),
            action(identity = DataMapItemIdentity(MapItemType.AREA, "item-1")),
            action(identity = DataMapItemIdentity(MapItemType.MARKER, "other")),
            action(order = order(eventId = " ")),
            action(order = order(actor = "\t")),
            action(order = order(timestamp = 0)),
            action(order = order(timestamp = -1)),
        )
        invalid.forEach { assertNull(projector.project(MARKER, it)) }
    }

    @Test fun rejectsEveryInvalidConfirmAndSituationUpdateShape() {
        listOf(
            action(disposition = MapSituationUpdateType.STILL_ACTIVE),
            action(lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE),
            action(action = MapItemAction.ADD_UPDATE, note = null, disposition = MapSituationUpdateType.STILL_ACTIVE),
            action(action = MapItemAction.ADD_UPDATE, note = "  ", disposition = MapSituationUpdateType.STILL_ACTIVE),
            action(action = MapItemAction.ADD_UPDATE, note = "update"),
            action(
                action = MapItemAction.ADD_UPDATE,
                note = "update",
                disposition = MapSituationUpdateType.STILL_ACTIVE,
                lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
            ),
        ).forEach { assertNull(projector.project(MARKER, it)) }
    }

    @Test fun rejectsEveryInvalidStatusShape() {
        listOf(
            action(action = MapItemAction.UPDATE_STATUS),
            action(action = MapItemAction.UPDATE_STATUS, lifecycle = MapSituationLifecycleStatus.ACTIVE),
            action(
                action = MapItemAction.UPDATE_STATUS,
                disposition = MapSituationUpdateType.SITUATION_CHANGED,
                lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
            ),
        ).forEach { assertNull(projector.project(MARKER, it)) }
    }

    @Test fun rejectsEveryForbiddenAcknowledgeField() {
        listOf(
            action(action = MapItemAction.ACKNOWLEDGE, source = MapInformationSource.FIRSTHAND),
            action(action = MapItemAction.ACKNOWLEDGE, note = "note"),
            action(action = MapItemAction.ACKNOWLEDGE, disposition = MapSituationUpdateType.STILL_ACTIVE),
            action(action = MapItemAction.ACKNOWLEDGE, lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE),
        ).forEach { assertNull(projector.project(MARKER, it)) }
    }

    @Test fun rejectsBlankAndEveryForbiddenCoordinationNoteField() {
        listOf(
            action(action = MapItemAction.ADD_NOTE, note = ""),
            action(action = MapItemAction.ADD_NOTE, note = " \t"),
            action(action = MapItemAction.ADD_NOTE, note = "note", source = MapInformationSource.UNCERTAIN),
            action(action = MapItemAction.ADD_NOTE, note = "note", disposition = MapSituationUpdateType.STILL_ACTIVE),
            action(action = MapItemAction.ADD_NOTE, note = "note", lifecycle = MapSituationLifecycleStatus.ACTIVE),
        ).forEach { assertNull(projector.project(MARKER, it)) }
    }

    @Test fun copiesActorTimestampAndEventIdentityWithoutUsingLamport() {
        val entry = projector.project(
            MARKER,
            action(order = MapActionOrderKey(123, 999_999, "did:peer", "event-9")),
        )!!
        assertEquals("event-9", entry.entryId)
        assertEquals(MapItemActivityActor("did:peer", "did:peer"), entry.actor)
        assertEquals(123L, entry.actionTimestampSeconds)
        assertEquals(
            setOf("entryId", "itemIdentity", "type", "actor", "actionTimestampSeconds", "informationSource", "note", "updateDisposition", "targetLifecycle"),
            MapItemActivityEntry::class.java.declaredFields.map { it.name }.toSet(),
        )
    }

    private fun action(
        gid: String = "g",
        identity: DataMapItemIdentity = DataMapItemIdentity(MapItemType.MARKER, "item-1"),
        action: MapItemAction = MapItemAction.CONFIRM,
        order: MapActionOrderKey = order(),
        source: MapInformationSource? = null,
        note: String? = null,
        disposition: MapSituationUpdateType? = null,
        lifecycle: MapSituationLifecycleStatus? = null,
    ) = MapItemActionRead(gid, identity, action, order, source, note, disposition, lifecycle)

    private data class Expected(
        val type: MapItemActivityType,
        val source: MapInformationSource?,
        val note: String?,
        val disposition: MapSituationUpdateType?,
        val lifecycle: MapSituationLifecycleStatus?,
    )

    companion object {
        private val MARKER = MapItemIdentity("g", MapItemType.MARKER, "item-1")
        private fun order(
            timestamp: Long = 100,
            lamport: Long = 7,
            actor: String = "did:actor",
            eventId: String = "event-1",
        ) = MapActionOrderKey(timestamp, lamport, actor, eventId)
    }
}
