package app.peermesh.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

object AppDatabaseProvider {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase {
        return instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }
    }

    private fun build(context: Context): AppDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            DATABASE_NAME
        )
            .addCallback(RoomCallbacks.pragmas)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
    }

    private const val DATABASE_NAME = "peer_mesh.db"
}
