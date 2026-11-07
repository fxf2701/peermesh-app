package app.peermesh.map.application.participation

import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.map.application.detail.MapItemIdentity
import app.peermesh.map.application.timeline.MapItemActivityActor
import app.peermesh.map.application.timeline.MapItemActivityEntry
import app.peermesh.map.application.timeline.MapItemActivityType
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMapCoordinationNotePreviewProjectorM3C3Test {
    private val projector = DefaultMapCoordinationNotePreviewProjector()

    @Test fun selectsFirstValidCoordinationNoteInSuppliedOrderWithoutSorting() {
        val first = note("older-timestamp", timestamp = 10)
        val second = note("newer-timestamp", timestamp = 99)
        assertEquals("older-timestamp", available(listOf(first, second)).entryId)
    }

    @Test fun skipsAcknowledgementsAndSituationActivityBeforeNote() {
        val entries = listOf(
            entry("ack", MapItemActivityType.COORDINATION_ACKNOWLEDGED),
            entry("confirm", MapItemActivityType.SITUATION_CONFIRMED,
                informationSource = MapInformationSource.FIRSTHAND),
            entry("update", MapItemActivityType.SITUATION_UPDATED, note = "situation",
                updateDisposition = MapSituationUpdateType.SITUATION_CHANGED),
            note("selected"),
        )
        assertEquals("selected", available(entries).entryId)
    }

    @Test fun preservesEveryPreviewFieldExactly() {
        val actor = MapItemActivityActor("did:example:actor", "Responder")
        val source = note("event-7", actor = actor, timestamp = 42, note = "Need water")
        val preview = available(listOf(source))
        assertEquals(source.entryId, preview.entryId)
        assertEquals(source.itemIdentity, preview.itemIdentity)
        assertSame(actor, preview.actor)
        assertEquals(source.actionTimestampSeconds, preview.actionTimestampSeconds)
        assertEquals(source.note, preview.note)
    }

    @Test fun unrelatedGroupMarkerAndAreaEntriesAreIgnoredWhileSearching() {
        val entries = listOf(
            note("other-group", identity = MARKER.copy(groupId = "other")),
            note("area", identity = AREA),
            note("wanted"),
        )
        assertEquals("wanted", available(entries).entryId)
        assertEquals(
            MapCoordinationNotePreviewProjectionResult.NoPreviewInSuppliedHistory,
            projector.project(MARKER, entries.take(2)),
        )
    }

    @Test fun emptyAndPartialHistoryMeanOnlyNoPreviewInSuppliedHistory() {
        val expected = MapCoordinationNotePreviewProjectionResult.NoPreviewInSuppliedHistory
        assertEquals(expected, projector.project(MARKER, emptyList()))
        assertEquals(expected, projector.project(MARKER,
            listOf(entry("ack", MapItemActivityType.COORDINATION_ACKNOWLEDGED))))
        assertTrue(expected.toString().contains("SuppliedHistory"))
        assertFalse(expected.toString().contains("Never"))
    }

    @Test fun blankRequestedIdentityIsInvalid() {
        assertEquals(MapCoordinationNotePreviewProjectionResult.InvalidIdentity,
            projector.project(MARKER.copy(itemId = " "), listOf(note("note"))))
    }

    @Test fun everyMalformedExactTargetCoordinationNoteIsInvalidHistory() {
        listOf(
            note(id = ""),
            note("blank-stable-actor", actor = MapItemActivityActor(" ", "Actor")),
            note("blank-display-actor", actor = MapItemActivityActor("did:actor", " ")),
            note("zero-timestamp", timestamp = 0),
            note("negative-timestamp", timestamp = -1),
            note("blank", note = "  "),
            note("source", informationSource = MapInformationSource.UNCERTAIN),
            note("disposition", updateDisposition = MapSituationUpdateType.SITUATION_CHANGED),
            note("lifecycle", targetLifecycle = MapSituationLifecycleStatus.ACTIVE),
        ).forEach { malformed ->
            assertEquals(MapCoordinationNotePreviewProjectionResult.InvalidSuppliedHistory,
                projector.project(MARKER, listOf(malformed)))
        }
    }

    @Test fun malformedUnrelatedNoteDoesNotPoisonRequestedPreview() {
        val malformedOther = note("bad", identity = AREA, note = " ")
        assertEquals("good", available(listOf(malformedOther, note("good"))).entryId)
    }

    @Test fun previewContractDoesNotExposeCurrentParticipationOrGlobalLatestGuarantee() {
        val names = MapCoordinationNotePreview::class.java.declaredFields.map { it.name }
        listOf("acknowledgementCount", "confirmationCount", "current", "latest", "global")
            .forEach { token -> assertFalse(names.any { it.contains(token, true) }) }
    }

    @Test fun projectorHasOnlyAllowedBoundaryDependencies() {
        assertTrue(DefaultMapCoordinationNotePreviewProjector::class.java.declaredConstructors.single().parameterTypes.isEmpty())
        assertTrue(MapCoordinationNotePreviewPresentationFactory.create() is DefaultMapCoordinationNotePreviewProjector)
        assertPublicBoundaryIsClean(
            DefaultMapCoordinationNotePreviewProjector::class.java,
            ProjectMapCoordinationNotePreview::class.java,
            MapCoordinationNotePreviewProjectionResult::class.java,
            MapCoordinationNotePreviewProjectionResult.Available::class.java,
            MapCoordinationNotePreview::class.java,
        )
        assertImplementationHasNoForbiddenDependency(DefaultMapCoordinationNotePreviewProjector::class.java)
    }

    private fun assertPublicBoundaryIsClean(vararg contracts: Class<*>) {
        val exposed = contracts.flatMap { contract ->
            contract.interfaces.toList() +
                contract.declaredConstructors.flatMap { it.parameterTypes.toList() } +
                contract.declaredFields.flatMap { listOf(it.type) } +
                contract.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.flatMap {
                    listOf(it.returnType) + it.parameterTypes
                }
        }.map(Class<*>::getName)
        forbiddenTypeTokens.forEach { token ->
            assertFalse("Forbidden public dependency $token in $exposed", exposed.any { it.contains(token) })
        }
    }

    private fun assertImplementationHasNoForbiddenDependency(type: Class<*>) {
        val path = "/${type.name.replace('.', '/')}.class"
        val bytecode = checkNotNull(type.getResourceAsStream(path), { "Missing class bytes for ${type.name}" })
            .use { String(it.readBytes(), Charsets.ISO_8859_1) }
        forbiddenImplementationTokens.forEach { token ->
            assertFalse("Forbidden implementation dependency $token", bytecode.contains(token))
        }
    }

    private fun available(entries: List<MapItemActivityEntry>) =
        (projector.project(MARKER, entries) as MapCoordinationNotePreviewProjectionResult.Available).preview

    private fun note(
        id: String,
        identity: MapItemIdentity = MARKER,
        actor: MapItemActivityActor = ACTOR,
        timestamp: Long = 20,
        note: String? = "note-$id",
        informationSource: MapInformationSource? = null,
        updateDisposition: MapSituationUpdateType? = null,
        targetLifecycle: MapSituationLifecycleStatus? = null,
    ) = entry(id, MapItemActivityType.COORDINATION_NOTE_ADDED, identity, actor, timestamp, note,
        informationSource, updateDisposition, targetLifecycle)

    private fun entry(
        id: String,
        type: MapItemActivityType,
        identity: MapItemIdentity = MARKER,
        actor: MapItemActivityActor = ACTOR,
        timestamp: Long = 20,
        note: String? = null,
        informationSource: MapInformationSource? = null,
        updateDisposition: MapSituationUpdateType? = null,
        targetLifecycle: MapSituationLifecycleStatus? = null,
    ) = MapItemActivityEntry(id, identity, type, actor, timestamp, informationSource, note,
        updateDisposition, targetLifecycle)

    companion object {
        private val forbiddenTypeTokens = listOf(
            ".data.db.dao.", ".data.db.entity.", "Repository", "androidx.room", "android.app",
            "androidx.lifecycle", "android.app.Activity", "androidx.fragment", "androidx.compose", ".submission.",
            "MapItemTimelineController", "MapItemTimelineState", "LoadMapItemActivity", "Cursor",
            ".ble.", ".relay.", ".transport.",
        )
        private val forbiddenImplementationTokens = listOf(
            "app/peermesh/data/db/dao/", "app/peermesh/data/db/entity/", "Repository",
            "androidx/room/", "android/app/", "androidx/lifecycle/", "androidx/fragment/",
            "androidx/compose/", "map/application/submission/", "MapItemTimelineController",
            "MapItemTimelineState", "LoadMapItemActivity", "MapItemActivityCursor", "/ble/", "/relay/",
            "/transport/",
        )
        private val MARKER = MapItemIdentity("group", MapItemType.MARKER, "same")
        private val AREA = MapItemIdentity("group", MapItemType.AREA, "same")
        private val ACTOR = MapItemActivityActor("did:actor", "Actor")
    }
}
