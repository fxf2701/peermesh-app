package app.peermesh.data.map.processing

import app.peermesh.core.model.Actor
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapEventOrderingPolicyTest {
    @Test
    fun orderKey_usesOriginalCompleteWireTuple() {
        val core = EventCore(
            ev = 1,
            id = "event-1",
            type = "MAP.MARKER.ADD",
            actor = Actor(did = "did:peer:actor"),
            time = EventTime(t = 123L, lt = 7L),
            ref = EventRef(target = "marker-1"),
            data = buildJsonObject { },
        )

        val key = MapEventOrderingPolicy.orderKey(core)

        assertEquals(123L, key.timestampSeconds)
        assertEquals(7L, key.lamport)
        assertEquals("did:peer:actor", key.actorDid)
        assertEquals("event-1", key.eventId)
    }

    @Test fun policyAcceptsAddWithoutExistingState() {
        val event = core("missing", 10, 0, "did:peer")
        assertTrue(DefaultCanonicalMapEventPolicy().evaluate("g", event, null, null) is CanonicalMapEventPolicyDecision.AcceptCanonicalMutation)
    }

    @Test fun policyRejectsUpdateWithoutPersistedState() {
        val event = core("missing-upd", 10, 0, "did:peer").copy(type = "MAP.MARKER.UPD")
        assertTrue(DefaultCanonicalMapEventPolicy().evaluate("g", event, null, null) is CanonicalMapEventPolicyDecision.Reject)
    }

    @Test fun completeTupleUsesEveryTieBreakInOrder() {
        val base = core("e1", 100, 1, "did:a")
        assertTrue(MapEventOrderingPolicy.orderKey(core("e0", 99, 99, "did:z")) < MapEventOrderingPolicy.orderKey(base))
        assertTrue(MapEventOrderingPolicy.orderKey(core("e0", 100, 0, "did:z")) < MapEventOrderingPolicy.orderKey(base))
        assertTrue(MapEventOrderingPolicy.orderKey(core("e0", 100, 1, "did:0")) < MapEventOrderingPolicy.orderKey(base))
        assertTrue(MapEventOrderingPolicy.orderKey(core("e0", 100, 1, "did:a")) < MapEventOrderingPolicy.orderKey(base))
    }

    private fun core(id: String, timestamp: Long, lamport: Long, actor: String) = EventCore(
        ev = 1, id = id, type = "MAP.MARKER.ADD", actor = Actor(actor),
        time = EventTime(timestamp, lamport), ref = EventRef(target = "m"), data = buildJsonObject {},
    )
}
