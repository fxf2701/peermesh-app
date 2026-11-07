package app.peermesh.data.map.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.entity.RemovedMemberEntity
import app.peermesh.data.db.entity.UserProfileEntity
import app.peermesh.data.map.processing.MapEventRejectionReason
import app.peermesh.core.json.CanonicalJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive

@RunWith(AndroidJUnit4::class)
class RoomLocalMapEventWriterInstrumentationTest {
    private lateinit var db: AppDatabase
    private lateinit var writer: LocalMapEventWriter

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        db.userProfileDao().upsert(UserProfileEntity("g", "owner", "Owner", null, 1, "p", 1, 0, "owner"))
        db.userProfileDao().upsert(UserProfileEntity("g", "peer", "Peer", null, 1, "p2", 1, 0, "peer"))
        writer = LocalMapEventWriterFactory.lazy(db)
    }

    @After fun tearDown() = db.close()

    @Test fun markerAdd_commitsExactEventAndCanonicalRow() {
        val core = markerAdd("marker-add", "owner", 10, "m")
        assertEquals(LocalMapWriteOutcome.AcceptedNew("marker-add"), write(core, "owner"))
        val durable = db.eventsDao().findById("g", "marker-add")!!
        assertEquals("owner", durable.actor_did)
        assertEquals(10, durable.created_t)
        assertEquals(0, durable.time_lt)
        assertEquals("owner", db.mapDao().findMarker("g", "m")!!.actor_did)
        assertNotNull(db.mapItemDomainStateDao().find("g", "marker", "m"))
    }

    @Test fun areaAdd_commitsThroughProductionWriter() {
        val core = areaAdd("area-add", "owner", 10, "a")
        assertEquals(LocalMapWriteOutcome.AcceptedNew("area-add"), write(core, "owner"))
        assertEquals("owner", db.mapDao().findArea("g", "a")!!.actor_did)
        assertEquals(0, db.eventsDao().findById("g", "area-add")!!.time_lt)
        assertEquals(geometryV1(), CanonicalJson.json.parseToJsonElement(db.mapDao().findArea("g", "a")!!.geometry_json))
    }

    @Test fun markerOwnerUpdateAndDeleteApply() {
        write(markerAdd("m-add", "owner", 10, "m"), "owner")
        assertTrue(write(EventFactory.mapMarkerUpdate("m-upd", "owner", 11, 1, "m", "l", 3.0, 4.0, null, null, null, "risk"), "owner") is LocalMapWriteOutcome.AcceptedNew)
        assertEquals(3.0, db.mapDao().findMarker("g", "m")!!.lat, 0.0)
        assertTrue(write(EventFactory.mapMarkerDelete("m-del", "owner", 12, 2, "m"), "owner") is LocalMapWriteOutcome.AcceptedNew)
        assertEquals("deleted", db.mapDao().findMarker("g", "m")!!.status)
    }

    @Test fun markerNonOwnerUpdateAndDeleteAreRejectedWithoutMutation() {
        write(markerAdd("m-add", "owner", 10, "m"), "owner")
        val before = db.mapDao().findMarker("g", "m")!!
        val state = db.mapItemDomainStateDao().find("g", "marker", "m")!!
        val update = withoutLegacyContribution(EventFactory.mapMarkerUpdate("m-peer", "peer", 11, 1, "m", "l", 5.0, 6.0, null, "changed", null, "risk"))
        assertEquals(LocalMapWriteOutcome.SemanticRejected(update.id, MapEventRejectionReason.OWNER_REQUIRED), write(update, "peer"))
        val delete = EventFactory.mapMarkerDelete("m-peer-del", "peer", 12, 2, "m")
        assertEquals(LocalMapWriteOutcome.SemanticRejected(delete.id, MapEventRejectionReason.OWNER_REQUIRED), write(delete, "peer"))
        assertNull(db.eventsDao().findById("g", update.id))
        assertNull(db.eventsDao().findById("g", delete.id))
        assertEquals(before, db.mapDao().findMarker("g", "m"))
        assertEquals(state, db.mapItemDomainStateDao().find("g", "marker", "m"))
    }

    @Test fun areaOwnerUpdateAndDeleteApply() {
        write(areaAdd("a-add", "owner", 10, "a"), "owner")
        val update = EventFactory.mapAreaUpdate("a-upd", "owner", 11, 1, "a", "l", geometryV2(), "changed", null, "risk")
        assertEquals(LocalMapWriteOutcome.AcceptedNew(update.id), write(update, "owner"))
        assertEquals("changed", db.mapDao().findArea("g", "a")!!.label)
        assertEquals(geometryV2(), CanonicalJson.json.parseToJsonElement(db.mapDao().findArea("g", "a")!!.geometry_json))
        val delete = EventFactory.mapAreaDelete("a-del", "owner", 12, 2, "a")
        assertEquals(LocalMapWriteOutcome.AcceptedNew(delete.id), write(delete, "owner"))
        assertEquals("deleted", db.mapDao().findArea("g", "a")!!.status)
    }

    @Test fun areaNonOwnerUpdateAndDeleteAreRejectedWithoutMutation() {
        write(areaAdd("a-add", "owner", 10, "a"), "owner")
        val before = db.mapDao().findArea("g", "a")!!
        val state = db.mapItemDomainStateDao().find("g", "area", "a")!!
        val update = withoutLegacyContribution(EventFactory.mapAreaUpdate("a-peer", "peer", 11, 1, "a", "l", geometryV2(), "changed", null, "risk"))
        assertEquals(LocalMapWriteOutcome.SemanticRejected(update.id, MapEventRejectionReason.OWNER_REQUIRED), write(update, "peer"))
        val delete = EventFactory.mapAreaDelete("a-peer-del", "peer", 12, 2, "a")
        assertEquals(LocalMapWriteOutcome.SemanticRejected(delete.id, MapEventRejectionReason.OWNER_REQUIRED), write(delete, "peer"))
        assertNull(db.eventsDao().findById("g", update.id))
        assertNull(db.eventsDao().findById("g", delete.id))
        assertEquals(before, db.mapDao().findArea("g", "a"))
        assertEquals(state, db.mapItemDomainStateDao().find("g", "area", "a"))
    }

    @Test fun removedLocalMember_rejectsWithoutDurableOrCanonicalState() {
        db.removedMemberDao().upsert(RemovedMemberEntity("g", "owner", "admin", "removed", 1, 1, 0, "admin"))
        val result = write(markerAdd("removed", "owner", 10, "m"), "owner")
        assertEquals(LocalMapWriteOutcome.SemanticRejected("removed", MapEventRejectionReason.LOCAL_MEMBER_REMOVED), result)
        assertNull(db.eventsDao().findById("g", "removed"))
        assertNull(db.mapDao().findMarker("g", "m"))
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "m"))
        assertNull(db.mapItemActionDao().findByEventId("g", "removed"))
    }

    @Test fun removedLocalActorRejectsDistinctValidEventActor() {
        db.removedMemberDao().upsert(RemovedMemberEntity("g", "peer", "admin", "removed", 1, 1, 0, "admin"))
        val event = markerAdd("local-mismatch", "owner", 10, "m")
        assertEquals("owner", event.actor.did)
        assertEquals(LocalMapWriteOutcome.SemanticRejected(event.id, MapEventRejectionReason.LOCAL_MEMBER_REMOVED), write(event, "peer"))
        assertEquals("owner", event.actor.did)
        assertNull(db.eventsDao().findById("g", event.id))
        assertNull(db.mapDao().findMarker("g", "m"))
        assertNull(db.mapItemDomainStateDao().find("g", "marker", "m"))
        assertNull(db.mapItemActionDao().findByEventId("g", event.id))
    }

    @Test fun duplicates_areClassifiedWithoutMutatingOriginal() {
        val first = markerAdd("same", "owner", 10, "m")
        assertTrue(write(first, "owner") is LocalMapWriteOutcome.AcceptedNew)
        assertTrue(write(first, "owner") is LocalMapWriteOutcome.ExactDuplicate)
        val changed = first.copy(data = kotlinx.serialization.json.buildJsonObject {
            (first.data as kotlinx.serialization.json.JsonObject).forEach { (key, value) -> put(key, value) }
            put("label", "changed")
        })
        assertTrue(write(changed, "owner") is LocalMapWriteOutcome.ConflictingDuplicate)
        assertEquals("same", db.mapDao().findMarker("g", "m")!!.last_event_id)
        assertFalse(db.eventsDao().findById("g", "same")!!.payload_json.contains("changed"))
    }

    @Test fun exactDuplicateAfterLocalRemoval_preservesProcessorClassification() {
        val event = markerAdd("writer-removed-duplicate", "owner", 10, "writer-marker")
        assertEquals(LocalMapWriteOutcome.AcceptedNew(event.id), write(event, "owner"))
        val marker = db.mapDao().findMarker("g", "writer-marker")!!
        val state = db.mapItemDomainStateDao().find("g", "marker", "writer-marker")!!
        db.removedMemberDao().upsert(RemovedMemberEntity("g", "owner", "admin", "removed", 11, 11, 0, "admin"))
        assertEquals(LocalMapWriteOutcome.ExactDuplicate(event.id), write(event, "owner"))
        assertEquals(marker, db.mapDao().findMarker("g", "writer-marker"))
        assertEquals(state, db.mapItemDomainStateDao().find("g", "marker", "writer-marker"))
    }

    @Test fun schemaUsesCurrentVersion() = assertEquals(54, db.openHelper.readableDatabase.version)

    private fun write(core: app.peermesh.core.model.EventCore, localActor: String) = writer.write(LocalMapWriteRequest("g", core, localActor, core.time.t))
    private fun markerAdd(id: String, actor: String, time: Long, marker: String) = EventFactory.mapMarkerAdd(id, actor, time, 0, marker, "l", 1.0, 2.0, null, null, null, "risk", domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
    private fun areaAdd(id: String, actor: String, time: Long, area: String) = EventFactory.mapAreaAdd(id, actor, time, 0, area, "l", geometryV1(), null, null, "risk", domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND))
    private fun geometryV1() = polygon(106.6600, 10.7620)
    private fun geometryV2() = polygon(106.6700, 10.7720)
    private fun polygon(lon: Double, lat: Double) = buildJsonObject {
        put("type", "Polygon")
        put("coordinates", buildJsonArray {
            add(buildJsonArray {
                add(point(lon, lat)); add(point(lon + .001, lat)); add(point(lon + .001, lat + .001)); add(point(lon, lat))
            })
        })
    }
    private fun point(lon: Double, lat: Double) = buildJsonArray { add(JsonPrimitive(lon)); add(JsonPrimitive(lat)) }
    private fun withoutLegacyContribution(core: app.peermesh.core.model.EventCore) = core.copy(data = buildJsonObject {
        (core.data as kotlinx.serialization.json.JsonObject).forEach { (key, value) -> if (key != "confidence" && key != "note") put(key, value) }
    })
}
