package app.peermesh.data.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.core.factory.EventFactory
import app.peermesh.core.map.MapActionMetadata
import app.peermesh.core.map.MapCanonicalAddExtension
import app.peermesh.core.map.MapConfirmation
import app.peermesh.core.map.MapInformationSource
import app.peermesh.core.map.MapItemCategory
import app.peermesh.core.map.MapItemType
import app.peermesh.core.model.EncPayload
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventEnvelope
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.entity.RemovedMemberEntity
import app.peermesh.data.db.entity.UserProfileEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EventIngestorM2C4AIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var ingestor: EventIngestor

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        listOf("owner", "peer", "removed").forEach { did ->
            db.userProfileDao().upsert(UserProfileEntity("g", did, did, null, 1, "profile-$did", 1, 0, did))
        }
        ingestor = EventIngestor(db)
    }

    @After fun tearDown() = db.close()

    @Test fun remoteActionBeforeTarget_isPersistedThenReplayedByProductionProcessorGraph() {
        val action = EventFactory.mapConfirmation(
            MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("pending-action", "marker", "peer", 11, 2), null, null),
        )
        assertTrue(ingest(action))
        assertEquals("pending_target", db.mapItemActionDao().findByActionId("g", "pending-action")!!.materialization_state)

        assertTrue(ingest(markerAdd()))
        assertEquals("applied", db.mapItemActionDao().findByActionId("g", "pending-action")!!.materialization_state)
        assertEquals(1, db.mapItemDomainStateDao().find("g", "marker", "marker")!!.confirmation_actor_count)
    }

    @Test fun remoteNonOwnerAndRemovedMemberMutations_areRejectedWithoutDurableEvents() {
        assertTrue(ingest(markerAdd()))
        val nonOwner = withoutLegacyContribution(EventFactory.mapMarkerUpdate(
            "non-owner", "peer", 12, 0, "marker", "layer", 2.0, 3.0,
            null, "changed", null, "risk",
        ))
        assertFalse(ingest(nonOwner))
        assertNull(db.eventsDao().findById("g", "non-owner"))

        db.removedMemberDao().upsert(RemovedMemberEntity("g", "removed", "owner", "removal", 13, 13, 0, "owner"))
        val removedAdd = EventFactory.mapMarkerAdd(
            "removed-add", "removed", 14, 0, "other", "layer", 1.0, 2.0,
            null, null, null, "risk",
            domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND),
        )
        assertFalse(ingest(removedAdd))
        assertNull(db.eventsDao().findById("g", "removed-add"))
        assertNull(db.mapDao().findMarker("g", "other"))
    }

    @Test fun remoteNonOwnerMarkerUpdate_isRejectedThroughProductionProcessor() {
        assertTrue(ingest(markerAdd()))
        val before = db.mapDao().findMarker("g", "marker")!!
        val update = withoutLegacyContribution(EventFactory.mapMarkerUpdate(
            "peer-marker-update", "peer", 12, 0, "marker", "layer", 2.0, 3.0,
            null, "changed", null, "risk",
        ))
        assertFalse(ingest(update))
        assertNull(db.eventsDao().findById("g", update.id))
        assertEquals(before, db.mapDao().findMarker("g", "marker"))
    }

    @Test fun remoteNonOwnerAreaUpdate_isRejectedThroughProductionProcessor() {
        assertTrue(ingest(areaAdd()))
        val before = db.mapDao().findArea("g", "area")!!
        val update = withoutLegacyContribution(EventFactory.mapAreaUpdate(
            "peer-area-update", "peer", 12, 0, "area", "layer", geometry(), "changed", null, "risk",
        ))
        assertFalse(ingest(update))
        assertNull(db.eventsDao().findById("g", update.id))
        assertEquals(before, db.mapDao().findArea("g", "area"))
    }

    @Test fun remoteNonOwnerMarkerDelete_isRejectedThroughProductionProcessor() {
        assertTrue(ingest(markerAdd()))
        val before = db.mapDao().findMarker("g", "marker")!!
        val delete = EventFactory.mapMarkerDelete("peer-marker-delete", "peer", 12, 0, "marker")
        assertFalse(ingest(delete))
        assertNull(db.eventsDao().findById("g", delete.id))
        assertEquals(before, db.mapDao().findMarker("g", "marker"))
    }

    @Test fun remoteNonOwnerAreaDelete_isRejectedThroughProductionProcessor() {
        assertTrue(ingest(areaAdd()))
        val before = db.mapDao().findArea("g", "area")!!
        val delete = EventFactory.mapAreaDelete("peer-area-delete", "peer", 12, 0, "area")
        assertFalse(ingest(delete))
        assertNull(db.eventsDao().findById("g", delete.id))
        assertEquals(before, db.mapDao().findArea("g", "area"))
    }

    @Test fun remoteRemovedMemberDedicatedAction_isRejectedThroughProductionProcessor() {
        assertTrue(ingest(markerAdd()))
        db.removedMemberDao().upsert(RemovedMemberEntity("g", "removed", "owner", "removal", 13, 13, 0, "owner"))
        val action = EventFactory.mapConfirmation(
            MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("removed-action", "marker", "removed", 14, 0), null, null),
        )
        assertFalse(ingest(action))
        assertNull(db.eventsDao().findById("g", action.id))
        assertNull(db.mapItemActionDao().findByActionId("g", "removed-action"))
    }

    @Test fun remoteConflictingDedicatedDuplicate_preservesDurableEventActionAndProjection_withoutNotificationOrDedupeMark() {
        assertTrue(ingest(markerAdd()))
        val original = EventFactory.mapConfirmation(
            MapItemType.MARKER,
            MapConfirmation(MapActionMetadata("conflict-action", "marker", "peer", 11, 1), null, "original"),
        )
        assertTrue(ingest(original))
        val durableEvent = db.eventsDao().findById("g", original.id)!!
        val action = db.mapItemActionDao().findByActionId("g", "conflict-action")!!
        val history = db.mapItemActionDao().findAppliedHistory("g", "marker", "marker", null, null, null, null, 10)
        val projection = db.mapItemDomainStateDao().find("g", "marker", "marker")!!
        db.eventDedupeDao().deleteByGid("g")

        val notified = CountDownLatch(1)
        val listener = object : IngestNotifier.Listener {
            override fun onEventInserted(gid: String, eventId: String) = notified.countDown()
        }
        IngestNotifier.add(listener)
        try {
            val conflicting = EventFactory.mapConfirmation(
                MapItemType.MARKER,
                MapConfirmation(MapActionMetadata("conflict-action", "marker", "peer", 11, 1), null, "changed"),
            )
            assertEquals(original.id, conflicting.id)
            assertFalse(ingest(conflicting))
            assertFalse(notified.await(200, TimeUnit.MILLISECONDS))
        } finally {
            IngestNotifier.remove(listener)
        }

        assertEquals(durableEvent, db.eventsDao().findById("g", original.id))
        assertEquals(action, db.mapItemActionDao().findByActionId("g", "conflict-action"))
        assertEquals(history, db.mapItemActionDao().findAppliedHistory("g", "marker", "marker", null, null, null, null, 10))
        assertEquals(projection, db.mapItemDomainStateDao().find("g", "marker", "marker"))
        assertEquals(0, dedupeCount(original.id))
    }

    @Test fun schemaVersion_remains52() {
        assertEquals(54, db.openHelper.readableDatabase.version)
    }

    private fun markerAdd() = EventFactory.mapMarkerAdd(
        "target-add", "owner", 10, 0, "marker", "layer", 1.0, 2.0,
        null, null, null, "risk",
        domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND),
    )

    private fun areaAdd() = EventFactory.mapAreaAdd(
        "area-add", "owner", 10, 0, "area", "layer", geometry(), null, null, "risk",
        domainExtension = MapCanonicalAddExtension(MapItemCategory.SITUATION, MapInformationSource.FIRSTHAND),
    )

    private fun geometry() = buildJsonArray { add(JsonPrimitive(1)) }

    private fun withoutLegacyContribution(core: EventCore): EventCore = core.copy(
        data = buildJsonObject {
            (core.data as JsonObject).forEach { (key, value) ->
                if (key != "confidence" && key != "note") put(key, value)
            }
        },
    )

    private fun dedupeCount(eventId: String): Int = db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM event_dedupe WHERE gid = 'g' AND event_id = ?", arrayOf(eventId))
        .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun ingest(core: EventCore): Boolean = ingestor.ingest(
        EventEnvelope(v = 1, gid = "g", mid = core.id, ts = core.time.t, ttl = 60, enc = EncPayload("ciphertext")),
        core,
        EventSources.MESH,
    )
}
