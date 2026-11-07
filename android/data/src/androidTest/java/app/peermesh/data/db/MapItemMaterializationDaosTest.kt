package app.peermesh.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.data.db.entity.MapItemActionEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapItemMaterializationDaosTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun holderUsesDaosFromTheSameDatabaseInstance() {
        val dependencies = db.mapItemMaterializationDaos()
        assertNotNull(dependencies.eventsDao)
        assertNotNull(dependencies.actionDao)
        assertNotNull(dependencies.domainStateDao)

        val row = MapItemActionEntity(
            gid = "g", action_id = "event", event_id = "event", event_type = "MAP.ITEM.CONFIRM",
            map_domain_v = 1, item_type = "marker", item_id = "item", action_type = "confirm",
            actor_did = "did:actor", action_t = 10, action_lt = 0, information_source = null,
            action_note = null, update_disposition = null, target_lifecycle = null,
            event_fingerprint = "sha", materialization_state = "pending_target", rejection_reason = null,
        )
        assertTrue(dependencies.actionDao.insertIgnore(row) is PersistenceWriteResult.Success)
        assertEquals(row, db.mapItemActionDao().findByEventId("g", "event"))
    }
}
