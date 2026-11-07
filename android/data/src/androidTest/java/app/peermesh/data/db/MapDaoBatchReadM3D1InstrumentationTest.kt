package app.peermesh.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.data.db.entity.MapAreaEntity
import app.peermesh.data.db.entity.MapMarkerEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapDaoBatchReadM3D1InstrumentationTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun markerBatchLookupIsExactGroupScopedAndDoesNotApplyActiveOrTimeFilters() {
        val dao = database.mapDao()
        dao.upsertMarker(marker("g", "active", "active", 9_999))
        dao.upsertMarker(marker("g", "deleted", "deleted", 1))
        dao.upsertMarker(marker("g", "unrequested", "active", null))
        dao.upsertMarker(marker("other", "active", "active", null))

        val rows = dao.findMarkersByIds("g", listOf("active", "deleted", "missing"))

        assertEquals(setOf("active", "deleted"), rows.map { it.marker_id }.toSet())
        assertTrue(dao.findMarkersByIds("g", emptyList()).isEmpty())
    }

    @Test fun areaBatchLookupIsExactGroupScopedAndDoesNotApplyActiveOrTimeFilters() {
        val dao = database.mapDao()
        dao.upsertArea(area("g", "active", "active", 9_999))
        dao.upsertArea(area("g", "deleted", "deleted", 1))
        dao.upsertArea(area("g", "unrequested", "active", null))
        dao.upsertArea(area("other", "active", "active", null))

        val rows = dao.findAreasByIds("g", listOf("active", "deleted", "missing"))

        assertEquals(setOf("active", "deleted"), rows.map { it.area_id }.toSet())
        assertTrue(dao.findAreasByIds("g", emptyList()).isEmpty())
    }

    private fun marker(gid: String, id: String, status: String, expiresAt: Long?) = MapMarkerEntity(
        gid, id, "layer", 10.0, 106.0, 20.0, "label", "note", "risk", 1, status,
        1, 2, "did:o", null, null, null, null, expiresAt, "event-$gid-$id", 2, 1, "did:o",
    )

    private fun area(gid: String, id: String, status: String, expiresAt: Long?) = MapAreaEntity(
        gid, id, "layer", POLYGON, "label", "note", "risk", 1, status,
        1, 2, "did:o", null, null, null, null, expiresAt, "event-$gid-$id", 2, 1, "did:o",
    )

    private companion object {
        const val POLYGON =
            """{"type":"Polygon","coordinates":[[[106.0,10.0],[107.0,10.0],[107.0,11.0],[106.0,10.0]]]}"""
    }
}
