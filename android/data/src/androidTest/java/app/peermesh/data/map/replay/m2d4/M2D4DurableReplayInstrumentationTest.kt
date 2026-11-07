package app.peermesh.data.map.replay.m2d4

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.map.MapActionMetadata
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.map.MapCoordinationAcknowledgement
import app.peermesh.core.map.MapCoordinationNote
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.map.MapSituationLifecycleStatus
import app.peermesh.core.map.MapSituationStatusUpdate
import app.peermesh.core.map.MapSituationUpdate
import app.peermesh.core.map.MapSituationUpdateType
import app.peermesh.core.model.EventCore
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.map.processing.MapEventProcessingRequest
import app.peermesh.data.map.processing.MapEventProcessingResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M2D4DurableReplayInstrumentationTest {
    private val groups = setOf("g", "g-good", "g-bad")
    private val actors = setOf("owner", "peer", "peer2", "peer3")

    @Test fun canonicalMarkerAndAreaReplayIsDeterministicAcrossDurableRowPermutations() {
        val marker = markerAdd("m-add", "m", 100)
        val markerOld = markerUpdate("m-old", "m", 110, 1.1)
        val markerNew = markerUpdate("m-new", "m", 120, 1.2)
        val markerDelete = EventFactory.mapMarkerDelete("m-del", "owner", 130, 0, "m")
        val area = areaAdd("a-add", "a", 100)
        val areaOld = areaUpdate("a-old", "a", 110, 1.1)
        val areaNew = areaUpdate("a-new", "a", 120, 1.2)
        val areaDelete = EventFactory.mapAreaDelete("a-del", "owner", 130, 0, "a")

        val first = replay(listOf(marker, markerOld, markerNew, area, areaOld, areaNew, markerDelete, areaDelete))
        val second = replay(listOf(area, areaNew, areaOld, marker, markerNew, markerOld, areaDelete, markerDelete))

        assertEquals(first.snapshot("g"), second.snapshot("g"))
        val targets = first.snapshot("g").canonicalTargets.associateBy { it.itemType to it.itemId }
        assertEquals("deleted", targets.getValue("marker" to "m").canonicalContent["status"])
        assertEquals("m-del", targets.getValue("marker" to "m").canonicalContent["last_event_id"])
        assertEquals("deleted", targets.getValue("area" to "a").canonicalContent["status"])
        assertEquals("a-del", targets.getValue("area" to "a").canonicalContent["last_event_id"])
    }

    @Test fun dedicatedActionsAndProjectionRebuildAcrossPermittedOppositeOrders() {
        val situationAdd = markerAdd("s-add", "s", 100)
        val coordinationAdd = coordinationMarkerAdd("c-add", "c", 100)
        val confirm1 = confirm("confirm-1", "s", "peer", 110, 1)
        val confirmAgain = confirm("confirm-2", "s", "peer", 111, 1)
        val confirm2 = confirm("confirm-3", "s", "peer2", 112, 1)
        val update = situationUpdate("update", "s", 120, MapSituationUpdateType.STILL_ACTIVE)
        val ack1 = acknowledge("ack-1", "c", "peer", 110)
        val ackAgain = acknowledge("ack-2", "c", "peer", 111)
        val ack2 = acknowledge("ack-3", "c", "peer2", 112)
        val note = coordinationNote("note", "c", 120)

        val first = replay(listOf(situationAdd, coordinationAdd, confirm1, confirmAgain, confirm2, update, ack1, ackAgain, ack2, note))
        val second = replay(listOf(coordinationAdd, situationAdd, note, ack2, ackAgain, ack1, update, confirm2, confirmAgain, confirm1))
        assertEquals(first.snapshot("g"), second.snapshot("g"))

        val states = first.snapshot("g").domainStates.associateBy { it.itemId }
        assertEquals("2", states.getValue("s").semanticContent["confirmation_actor_count"])
        assertEquals("confirm-3", states.getValue("s").semanticContent["latest_confirmation_event_id"])
        assertEquals("update", states.getValue("s").semanticContent["latest_update_event_id"])
        assertEquals("2", states.getValue("c").semanticContent["acknowledgement_actor_count"])
        assertEquals(8, first.snapshot("g").actions.size)
        assertTrue(first.snapshot("g").actions.all { it.eventId == it.actionId })
    }

    @Test fun actionBeforeTargetAndTargetBeforeActionConvergeWithEarliestResolutionCutoff() {
        val add = markerAdd("add", "life", 100)
        val before = confirm("before", "life", "peer", 110, 0)
        val resolution = status("resolution", "life", "peer2", 120, 0)
        val repeatedResolution = status("resolution-2", "life", "peer3", 121, 0)
        val after = situationUpdate("after", "life", 130, MapSituationUpdateType.STILL_ACTIVE)

        val pendingFirst = replay(listOf(repeatedResolution, resolution, before, add))
        val targetFirst = replay(listOf(add, before, resolution, repeatedResolution))
        assertEquals(pendingFirst.snapshot("g"), targetFirst.snapshot("g"))

        val actions = pendingFirst.snapshot("g").actions.associateBy { it.eventId }
        assertEquals("applied", actions.getValue("before").semanticContent["materialization_state"])
        assertEquals("applied", actions.getValue("resolution-2").semanticContent["materialization_state"])
        val state = pendingFirst.snapshot("g").domainStates.single()
        assertEquals("no_longer_active", state.semanticContent["situation_lifecycle"])
        assertEquals("resolution-2", state.semanticContent["latest_lifecycle_event_id"])
        assertEquals("resolution-2", state.semanticContent["latest_material_event_id"])
        assertEquals("0", state.semanticContent["confirmation_actor_count"])

        val rejectedPending = replay(listOf(after, resolution, add)).snapshot("g").actions.associateBy { it.eventId }
        assertEquals("rejected", rejectedPending.getValue("after").semanticContent["materialization_state"])
        assertEquals("target_resolved", rejectedPending.getValue("after").semanticContent["rejection_reason"])
    }

    @Test fun materialChangeFreshnessRebuildsDeterministically() {
        val add = markerAdd("add-fresh", "fresh", 100)
        val oldConfirm = confirm("confirm-old", "fresh", "peer", 110, 1)
        val changed = situationUpdate("changed", "fresh", 120, MapSituationUpdateType.SITUATION_CHANGED)
        val freshConfirm = confirm("confirm-fresh", "fresh", "peer2", 130, 7)
        val first = replay(listOf(add, oldConfirm, changed, freshConfirm))
        val second = replay(listOf(add, changed, oldConfirm, freshConfirm))
        assertEquals(first.snapshot("g"), second.snapshot("g"))
        val state = first.snapshot("g").domainStates.single()
        assertEquals("changed", state.semanticContent["latest_material_event_id"])
        assertEquals("confirm-fresh", state.semanticContent["latest_confirmation_event_id"])
        assertEquals("1", state.semanticContent["confirmation_actor_count"])
        assertEquals(3, first.snapshot("g").actions.size)
    }

    @Test fun sameCompleteOrderingClockUsesEventIdAsFinalTieBreak() {
        val add = markerAdd("add-tie", "tie", 100)
        val tieA = confirm("confirm-a", "tie", "peer", 130, 7)
        val tieZ = confirm("confirm-z", "tie", "peer", 130, 7)
        val first = replay(listOf(add, tieA, tieZ))
        val second = replay(listOf(add, tieZ, tieA))
        assertEquals(first.snapshot("g"), second.snapshot("g"))
        val state = first.snapshot("g").domainStates.single()
        assertEquals("confirm-z", state.semanticContent["latest_confirmation_event_id"])
        assertEquals("1", state.semanticContent["confirmation_actor_count"])
        assertEquals(2, first.snapshot("g").actions.size)
    }

    @Test fun replayUsesImmutableEventTimeNotInvocationOrInsertionWallClock() {
        val add = markerAdd("temporal-add", "temporal", 100, ttl = 100)
        val accepted = confirm("temporal-confirm", "temporal", "peer", 150, 0)
        val source = listOf(add, accepted)
        val ancientInsertion = replay(source, insertedAt = { 1L })
        val farFutureInsertion = replay(source, insertedAt = { Long.MAX_VALUE - 1 })
        assertEquals(ancientInsertion.snapshot("g"), farFutureInsertion.snapshot("g"))
        assertTrue(farFutureInsertion.results.all { it is MapEventProcessingResult.Accepted })

        val boundary = replay(listOf(add, confirm("at-expiry", "temporal", "peer", 200, 0)))
        assertTrue(boundary.results.last() is MapEventProcessingResult.Rejected)
        assertEquals(0, boundary.snapshot("g").actions.size)
    }

    @Test fun exactAndConflictingDuplicateReplayIsIdempotentAndNonDestructive() {
        val add = markerAdd("dupe-add", "dupe", 100)
        val first = confirm("dupe-action", "dupe", "peer", 110, 1)
        val conflicting = first.copy(data = buildJsonObject {
            (first.data as kotlinx.serialization.json.JsonObject).forEach { (key, value) -> put(key, value) }
            put("action_note", "conflicting")
        })
        val result = replay(listOf(add, first, first, conflicting))
        assertTrue(result.results[2] is MapEventProcessingResult.ExactDuplicate)
        assertTrue(result.results[3] is MapEventProcessingResult.ConflictingDuplicate)
        val action = result.snapshot("g").actions.single()
        assertEquals("confirmed", action.semanticContent["action_note"])
        assertEquals("1", result.snapshot("g").domainStates.single().semanticContent["confirmation_actor_count"])
    }

    @Test fun markerAndAreaWithSameRawIdRemainIsolated() {
        val result = replay(listOf(
            markerAdd("marker-add", "same", 100),
            confirm("marker-confirm", "same", "peer", 110, 0),
            areaAdd("area-add", "same", 100),
            confirmArea("area-confirm", "same", "peer2", 110),
        ))
        assertTrue(result.results[2] is MapEventProcessingResult.Rejected)
        assertTrue(result.results[3] is MapEventProcessingResult.Rejected)
        assertEquals(setOf("marker" to "same"),
            result.snapshot("g").canonicalTargets.map { it.itemType to it.itemId }.toSet())
        assertEquals(setOf("marker-confirm"), result.snapshot("g").actions.map { it.eventId }.toSet())
    }

    @Test fun invalidTargetAndGroupDoNotCorruptValidSiblings() {
        val wrongType = EventFactory.mapConfirmation(
            MapItemType.AREA, MapConfirmation(MapActionMetadata("wrong", "valid", "peer", 120, 0), null, null),
        )
        val validGroup = listOf(
            fixture(markerAdd("valid-add", "valid", 100), gid = "g-good", source = "relay"),
            fixture(wrongType, gid = "g-good", source = "relay"),
        )
        val invalidGroup = fixture(
            markerAdd("bad-add", "bad", 100).copy(actor = app.peermesh.core.model.Actor("outsider")),
            gid = "g-bad", source = "relay",
        )
        val result = harness().replay(validGroup + invalidGroup)
        assertTrue(result.results[0] is MapEventProcessingResult.Accepted)
        assertTrue(result.results[1] is MapEventProcessingResult.Rejected)
        assertTrue(result.results[2] is MapEventProcessingResult.Rejected)
        assertEquals(1, result.snapshot("g-good").canonicalTargets.size)
        assertTrue(result.snapshot("g-bad").canonicalTargets.isEmpty())
    }

    @Test fun domainInsertFailureRollsBackDurableEventAndCanonicalMutation() {
        val environment = M2D4RoomReplayEnvironment(groups, actors)
        environment.use {
            environment.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER m2d4_fail_domain BEFORE INSERT ON map_item_domain_state " +
                    "BEGIN SELECT RAISE(ABORT, 'm2d4 domain failure'); END",
            )
            assertTrue(environment.processor.process(request(markerAdd("fail-add", "fail", 100))) is
                MapEventProcessingResult.PersistenceFailure)
            assertNull(environment.db.eventsDao().findById("g", "fail-add"))
            assertNull(environment.db.mapDao().findMarker("g", "fail"))
            assertNull(environment.db.mapItemDomainStateDao().find("g", "marker", "fail"))
            val snapshot = environment.snapshotReader.read("g")
            assertTrue(snapshot.canonicalTargets.isEmpty())
            assertTrue(snapshot.domainStates.isEmpty())
            assertTrue(snapshot.actions.isEmpty())
        }
    }

    @Test fun failureBeforeProcessorInvocationDoesNotEnterProductionPath() {
        lateinit var environment: M2D4RoomReplayEnvironment
        val harness = M2D4DeterministicReplayHarness(
            environmentFactory = {
                M2D4RoomReplayEnvironment(groups, actors).also { environment = it }
            },
            failureHook = M2D4FailureHook { throw ExpectedPreProcessorFailure },
        )
        try {
            harness.replay(listOf(fixture(markerAdd("never-processed", "never", 100))))
            fail("expected pre-processor failure")
        } catch (expected: ExpectedPreProcessorFailure) {
            assertFalse(environment.db.isOpen)
        }
    }

    @Test fun actionOrProjectionFailureRollsBackDurableAndActionWrites() {
        val add = markerAdd("projection-add", "projection", 100)
        val action = confirm("projection-action", "projection", "peer", 110, 0)
        val environment = M2D4RoomReplayEnvironment(groups, actors)
        environment.use {
            assertTrue(environment.processor.process(request(add)) is MapEventProcessingResult.Accepted)
            environment.db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER m2d4_fail_projection BEFORE UPDATE OF confirmation_actor_count " +
                    "ON map_item_domain_state BEGIN SELECT RAISE(ABORT, 'm2d4 projection failure'); END",
            )
            assertTrue(environment.processor.process(request(action)) is MapEventProcessingResult.PersistenceFailure)
            val snapshot = environment.snapshotReader.read("g")
            assertTrue(snapshot.actions.isEmpty())
            assertEquals("0", snapshot.domainStates.single().semanticContent["confirmation_actor_count"])
            assertNull(environment.db.eventsDao().findById("g", "projection-action"))
        }
    }

    @Test fun durableFixtureAdapterPreservesExactSerializedAuthority() {
        val core = confirm("adapter", "target", "peer", 123, 9)
        val entity = EventEntity(
            gid = "g", event_id = core.id, type = core.type, source = "relay", created_t = core.time.t,
            time_lt = core.time.lt ?: 0L, actor_did = core.actor.did, ref_root = core.ref.root,
            ref_parent = core.ref.parent, ref_target = core.ref.target, ttl_s = core.meta?.ttl_s,
            payload_json = CanonicalJson.json.encodeToString(EventCore.serializer(), core), inserted_at = 999,
        )
        val fixture = DurableMapReplayFixture.from(entity)
        assertEquals(core, fixture.core)
        assertEquals("relay", fixture.originalSource)
        assertEquals(999, fixture.originalInsertedAtSeconds)
    }

    @Test fun stillMissingTargetRemainsPendingAndLegacyVotesAreNotDedicatedActions() {
        val missing = confirm("missing", "absent", "peer", 110, 0)
        val pending = replay(listOf(missing))
        assertEquals("pending_target", pending.snapshot("g").actions.single().semanticContent["materialization_state"])
        assertTrue(pending.snapshot("g").domainStates.isEmpty())

        val add = markerAdd("legacy-add", "legacy", 100)
        val vote = EventFactory.mapMarkerUpdate(
            "legacy-vote", "peer", 110, 0, "legacy", "layer", 99.0, 99.0, null,
            null, "legacy contribution", "risk", 2,
        )
        val legacy = replay(listOf(add, vote))
        assertTrue(legacy.snapshot("g").actions.isEmpty())
        assertEquals("legacy-add", legacy.snapshot("g").canonicalTargets.single().canonicalContent["last_event_id"])
    }

    @Test fun schemaUsesCurrentVersion() {
        M2D4RoomReplayEnvironment(groups, actors).use { assertEquals(54, it.db.openHelper.writableDatabase.version) }
    }

    private fun replay(cores: List<EventCore>, insertedAt: (EventCore) -> Long = { it.time.t }): M2D4ReplayEvidence =
        harness().replay(cores.map { fixture(it, insertedAtSeconds = insertedAt(it)) })

    private fun harness() = M2D4DeterministicReplayHarness(
        environmentFactory = { M2D4RoomReplayEnvironment(groups, actors) },
    )
    private fun fixture(
        core: EventCore,
        gid: String = "g",
        insertedAtSeconds: Long = core.time.t,
        source: String = "m2d4-durable",
    ) = DurableMapReplayFixture(gid, core, source, insertedAtSeconds)
    private fun request(core: EventCore) = MapEventProcessingRequest("g", core, "m2d4-durable", core.time.t)

    private fun markerAdd(eventId: String, itemId: String, t: Long, ttl: Long? = null) = EventFactory.mapMarkerAdd(
        eventId, "owner", t, 0, itemId, "layer", 1.0, 2.0, null, "marker-$itemId", null,
        "risk", ttlSeconds = ttl,
        domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND),
    )
    private fun markerUpdate(eventId: String, itemId: String, t: Long, lat: Double) = EventFactory.mapMarkerUpdate(
        eventId, "owner", t, 0, itemId, "layer", lat, 2.0, null, "marker-$eventId", null, "risk",
    )
    private fun areaAdd(eventId: String, itemId: String, t: Long) = EventFactory.mapAreaAdd(
        eventId, "owner", t, 0, itemId, "layer", geometry(1.0), "area-$itemId", null, "risk",
        domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND),
    )
    private fun coordinationMarkerAdd(eventId: String, itemId: String, t: Long) = EventFactory.mapMarkerAdd(
        eventId, "owner", t, 0, itemId, "layer", 1.0, 2.0, null, "marker-$itemId", null, "camp",
        domainExtension = MapCanonicalAddExtension(MapItemCategory.COORDINATION, null),
    )
    private fun areaUpdate(eventId: String, itemId: String, t: Long, coordinate: Double) = EventFactory.mapAreaUpdate(
        eventId, "owner", t, 0, itemId, "layer", geometry(coordinate), "area-$eventId", null, "risk",
    )
    private fun confirm(id: String, target: String, actor: String, t: Long, lt: Long) = EventFactory.mapConfirmation(
        MapItemType.MARKER,
        MapConfirmation(MapActionMetadata(id, target, actor, t, lt), MapInformationSource.FIRSTHAND, "confirmed"),
    )
    private fun confirmArea(id: String, target: String, actor: String, t: Long) = EventFactory.mapConfirmation(
        MapItemType.AREA,
        MapConfirmation(MapActionMetadata(id, target, actor, t, 0), MapInformationSource.FIRSTHAND, "confirmed"),
    )
    private fun situationUpdate(id: String, target: String, t: Long, type: MapSituationUpdateType) =
        EventFactory.mapSituationUpdate(
            MapItemType.MARKER,
            MapSituationUpdate(MapActionMetadata(id, target, "peer", t, 0), "update-$id", MapInformationSource.FIRSTHAND, type),
        )
    private fun status(id: String, target: String, actor: String, t: Long, lt: Long) = EventFactory.mapSituationStatus(
        MapItemType.MARKER,
        MapSituationStatusUpdate(
            MapActionMetadata(id, target, actor, t, lt), MapSituationLifecycleStatus.NO_LONGER_ACTIVE,
            "resolved", MapInformationSource.FIRSTHAND,
        ),
    )
    private fun acknowledge(id: String, target: String, actor: String, t: Long) = EventFactory.mapAcknowledgement(
        MapItemType.MARKER, MapCoordinationAcknowledgement(MapActionMetadata(id, target, actor, t, 0)),
    )
    private fun coordinationNote(id: String, target: String, t: Long) = EventFactory.mapCoordinationNote(
        MapItemType.MARKER, MapCoordinationNote(MapActionMetadata(id, target, "peer", t, 0), "note-$id"),
    )
    private fun geometry(value: Double) = buildJsonArray { add(buildJsonArray { add(JsonPrimitive(value)); add(JsonPrimitive(value + 1)) }) }

    private object ExpectedPreProcessorFailure : RuntimeException()
}
