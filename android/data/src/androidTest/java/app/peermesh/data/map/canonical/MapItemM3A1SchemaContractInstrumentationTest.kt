package app.peermesh.data.map.canonical

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.peermesh.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapItemM3A1SchemaContractInstrumentationTest {
    @Test fun roomSchemaVersionRemains53() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).build()
        try {
            assertEquals(54, database.openHelper.readableDatabase.version)
        } finally {
            database.close()
        }
    }
}
