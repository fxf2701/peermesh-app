package app.peermesh.data.ingest

import app.peermesh.core.model.Actor
import app.peermesh.core.model.EncPayload
import app.peermesh.core.model.EventCore
import app.peermesh.core.model.EventEnvelope
import app.peermesh.core.model.EventRef
import app.peermesh.core.model.EventTime
import app.peermesh.data.db.AppDatabase
import app.peermesh.data.db.dao.ChatDao
import app.peermesh.data.db.dao.DeletedEventDao
import app.peermesh.data.db.dao.EventDedupeDao
import app.peermesh.data.db.dao.EventsDao
import app.peermesh.data.db.dao.GroupDao
import app.peermesh.data.db.dao.MapDao
import app.peermesh.data.db.dao.MapItemVoteDao
import app.peermesh.data.db.dao.LiveTrailDao
import app.peermesh.data.db.dao.RemovedMemberDao
import app.peermesh.data.db.dao.UserProfileDao
import app.peermesh.data.db.entity.DeletedEventEntity
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class EventIngestorTest {
    @Test
    fun ingest_dropsExpiredEvent() {
        val db = Mockito.mock(AppDatabase::class.java)
        Mockito.`when`(db.eventsDao()).thenReturn(Mockito.mock(EventsDao::class.java))
        val dedupeDao = Mockito.mock(EventDedupeDao::class.java)
        Mockito.`when`(dedupeDao.countActive(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(0)
        Mockito.`when`(db.eventDedupeDao()).thenReturn(dedupeDao)
        Mockito.`when`(db.chatDao()).thenReturn(Mockito.mock(ChatDao::class.java))
        val deletedEventDao = Mockito.mock(DeletedEventDao::class.java)
        Mockito.`when`(deletedEventDao.findActive(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(null)
        Mockito.`when`(db.deletedEventDao()).thenReturn(deletedEventDao)
        Mockito.`when`(db.mapDao()).thenReturn(Mockito.mock(MapDao::class.java))
        Mockito.`when`(db.mapItemVoteDao()).thenReturn(Mockito.mock(MapItemVoteDao::class.java))
        Mockito.`when`(db.liveTrailDao()).thenReturn(Mockito.mock(LiveTrailDao::class.java))
        Mockito.`when`(db.groupDao()).thenReturn(Mockito.mock(GroupDao::class.java))
        Mockito.`when`(db.userProfileDao()).thenReturn(Mockito.mock(UserProfileDao::class.java))
        Mockito.`when`(db.removedMemberDao()).thenReturn(mockRemovedMemberDao())

        val ingestor = EventIngestor(db)
        val core = EventCore(
            ev = 1,
            id = "event-id",
            type = "CHAT.MSG",
            actor = Actor(did = "device"),
            time = EventTime(t = 1L, lt = 0L),
            ref = EventRef(root = null, parent = null, target = null),
            meta = null,
            data = JsonNull,
        )
        val envelope = EventEnvelope(
            v = 1,
            gid = "gid",
            mid = "event-id",
            ts = 1L,
            ttl = 6,
            enc = EncPayload(ct = "ct"),
            sig = null,
        )

        val result = ingestor.ingest(envelope, core)
        assertFalse(result)
    }

    @Test
    fun ingest_dropsChatMessageWhenDeletedTombstoneExists() {
        val db = Mockito.mock(AppDatabase::class.java)
        Mockito.`when`(db.eventsDao()).thenReturn(Mockito.mock(EventsDao::class.java))
        val dedupeDao = Mockito.mock(EventDedupeDao::class.java)
        Mockito.`when`(dedupeDao.countActive(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(0)
        Mockito.`when`(db.eventDedupeDao()).thenReturn(dedupeDao)
        Mockito.`when`(db.chatDao()).thenReturn(Mockito.mock(ChatDao::class.java))
        val deletedEventDao = object : DeletedEventDao {
            override fun upsert(entity: DeletedEventEntity) = error("unused")

            override fun find(gid: String, targetEventId: String): DeletedEventEntity? = error("unused")

            override fun findActive(gid: String, targetEventId: String, nowSeconds: Long): DeletedEventEntity? {
                return if (gid == "gid" && targetEventId == "event-id") {
                    DeletedEventEntity(
                        gid = "gid",
                        target_event_id = "event-id",
                        delete_event_id = "delete-id",
                        actor_did = "device",
                        deleted_at = 10L,
                        expires_at = Long.MAX_VALUE,
                        target_created_t = 1L,
                        delete_created_t = 10L,
                        last_event_t = 10L,
                        last_event_lt = 1L,
                        last_event_actor = "device",
                    )
                } else {
                    null
                }
            }

            override fun deleteExpired(nowSeconds: Long): Int = 0

            override fun deleteByGid(gid: String): Int = error("unused")
        }
        Mockito.`when`(db.deletedEventDao()).thenReturn(deletedEventDao)
        Mockito.`when`(db.mapDao()).thenReturn(Mockito.mock(MapDao::class.java))
        Mockito.`when`(db.mapItemVoteDao()).thenReturn(Mockito.mock(MapItemVoteDao::class.java))
        Mockito.`when`(db.liveTrailDao()).thenReturn(Mockito.mock(LiveTrailDao::class.java))
        Mockito.`when`(db.groupDao()).thenReturn(Mockito.mock(GroupDao::class.java))
        Mockito.`when`(db.userProfileDao()).thenReturn(Mockito.mock(UserProfileDao::class.java))
        Mockito.`when`(db.removedMemberDao()).thenReturn(mockRemovedMemberDao())

        val ingestor = EventIngestor(db)
        val core = EventCore(
            ev = 1,
            id = "event-id",
            type = "CHAT.MSG",
            actor = Actor(did = "device"),
            time = EventTime(t = System.currentTimeMillis() / 1000L, lt = 0L),
            ref = EventRef(root = null, parent = null, target = null),
            meta = null,
            data = JsonNull,
        )
        val envelope = EventEnvelope(
            v = 1,
            gid = "gid",
            mid = "event-id",
            ts = 1L,
            ttl = 86400,
            enc = EncPayload(ct = "ct"),
            sig = null,
        )

        val result = ingestor.ingest(envelope, core)
        assertFalse(result)
    }

    @Test
    fun ingest_dropsChatEditWhenDeletedTombstoneExistsForTarget() {
        val db = Mockito.mock(AppDatabase::class.java)
        Mockito.`when`(db.eventsDao()).thenReturn(Mockito.mock(EventsDao::class.java))
        val dedupeDao = Mockito.mock(EventDedupeDao::class.java)
        Mockito.`when`(dedupeDao.countActive(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(0)
        Mockito.`when`(db.eventDedupeDao()).thenReturn(dedupeDao)
        Mockito.`when`(db.chatDao()).thenReturn(Mockito.mock(ChatDao::class.java))
        val deletedEventDao = object : DeletedEventDao {
            override fun upsert(entity: DeletedEventEntity) = error("unused")

            override fun find(gid: String, targetEventId: String): DeletedEventEntity? = error("unused")

            override fun findActive(gid: String, targetEventId: String, nowSeconds: Long): DeletedEventEntity? {
                return if (gid == "gid" && targetEventId == "target-message") {
                    DeletedEventEntity(
                        gid = "gid",
                        target_event_id = "target-message",
                        delete_event_id = "delete-id",
                        actor_did = "device",
                        deleted_at = 10L,
                        expires_at = Long.MAX_VALUE,
                        target_created_t = 1L,
                        delete_created_t = 10L,
                        last_event_t = 10L,
                        last_event_lt = 1L,
                        last_event_actor = "device",
                    )
                } else {
                    null
                }
            }

            override fun deleteExpired(nowSeconds: Long): Int = 0

            override fun deleteByGid(gid: String): Int = error("unused")
        }
        Mockito.`when`(db.deletedEventDao()).thenReturn(deletedEventDao)
        Mockito.`when`(db.mapDao()).thenReturn(Mockito.mock(MapDao::class.java))
        Mockito.`when`(db.mapItemVoteDao()).thenReturn(Mockito.mock(MapItemVoteDao::class.java))
        Mockito.`when`(db.liveTrailDao()).thenReturn(Mockito.mock(LiveTrailDao::class.java))
        Mockito.`when`(db.groupDao()).thenReturn(Mockito.mock(GroupDao::class.java))
        Mockito.`when`(db.userProfileDao()).thenReturn(Mockito.mock(UserProfileDao::class.java))
        Mockito.`when`(db.removedMemberDao()).thenReturn(mockRemovedMemberDao())

        val ingestor = EventIngestor(db)
        val core = EventCore(
            ev = 1,
            id = "edit-event",
            type = "CHAT.EDIT",
            actor = Actor(did = "device"),
            time = EventTime(t = System.currentTimeMillis() / 1000L, lt = 0L),
            ref = EventRef(root = null, parent = null, target = "target-message"),
            meta = null,
            data = JsonNull,
        )
        val envelope = EventEnvelope(
            v = 1,
            gid = "gid",
            mid = "edit-event",
            ts = 1L,
            ttl = 86400,
            enc = EncPayload(ct = "ct"),
            sig = null,
        )

        val result = ingestor.ingest(envelope, core)
        assertFalse(result)
    }

    @Test
    fun ingest_prunesExpiredDeletedTombstones() {
        val db = Mockito.mock(AppDatabase::class.java)
        Mockito.`when`(db.eventsDao()).thenReturn(Mockito.mock(EventsDao::class.java))
        val dedupeDao = Mockito.mock(EventDedupeDao::class.java)
        Mockito.`when`(dedupeDao.countActive(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong())).thenReturn(0)
        Mockito.`when`(db.eventDedupeDao()).thenReturn(dedupeDao)
        Mockito.`when`(db.chatDao()).thenReturn(Mockito.mock(ChatDao::class.java))
        val deletedDao = TrackingDeletedEventDao()
        Mockito.`when`(db.deletedEventDao()).thenReturn(deletedDao)
        Mockito.`when`(db.mapDao()).thenReturn(Mockito.mock(MapDao::class.java))
        Mockito.`when`(db.mapItemVoteDao()).thenReturn(Mockito.mock(MapItemVoteDao::class.java))
        Mockito.`when`(db.liveTrailDao()).thenReturn(Mockito.mock(LiveTrailDao::class.java))
        Mockito.`when`(db.groupDao()).thenReturn(Mockito.mock(GroupDao::class.java))
        Mockito.`when`(db.userProfileDao()).thenReturn(Mockito.mock(UserProfileDao::class.java))
        Mockito.`when`(db.removedMemberDao()).thenReturn(mockRemovedMemberDao())

        val ingestor = EventIngestor(db)
        val core = EventCore(
            ev = 1,
            id = "event-id",
            type = "CHAT.MSG",
            actor = Actor(did = "device"),
            time = EventTime(t = 1L, lt = 0L),
            ref = EventRef(root = null, parent = null, target = null),
            meta = null,
            data = JsonNull,
        )
        val envelope = EventEnvelope(
            v = 1,
            gid = "gid",
            mid = "event-id",
            ts = 1L,
            ttl = 6,
            enc = EncPayload(ct = "ct"),
            sig = null,
        )

        ingestor.ingest(envelope, core)

        assertTrue(deletedDao.deleteExpiredCalls > 0)
    }

    private class TrackingDeletedEventDao : DeletedEventDao {
        var deleteExpiredCalls: Int = 0

        override fun upsert(entity: DeletedEventEntity) = error("unused")

        override fun find(gid: String, targetEventId: String): DeletedEventEntity? = null

        override fun findActive(gid: String, targetEventId: String, nowSeconds: Long): DeletedEventEntity? = null

        override fun deleteExpired(nowSeconds: Long): Int {
            deleteExpiredCalls += 1
            return 0
        }

        override fun deleteByGid(gid: String): Int = error("unused")
    }

    private fun mockRemovedMemberDao(): RemovedMemberDao {
        return object : RemovedMemberDao {
            override fun upsert(entity: app.peermesh.data.db.entity.RemovedMemberEntity) = error("unused")

            override fun find(groupId: String, peerId: String) = null

            override fun listByGroup(groupId: String) = emptyList<app.peermesh.data.db.entity.RemovedMemberEntity>()

            override fun count(groupId: String, peerId: String): Int = 0

            override fun deleteByGroup(groupId: String): Int = error("unused")
        }
    }
}
