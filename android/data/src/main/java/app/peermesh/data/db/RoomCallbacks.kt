package app.peermesh.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

object RoomCallbacks {
    val pragmas = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            applyPragma(db, "PRAGMA journal_mode=WAL")
            applyPragma(db, "PRAGMA foreign_keys=ON")
            applyPragma(db, "PRAGMA synchronous=NORMAL")
        }
    }

    private fun applyPragma(db: SupportSQLiteDatabase, sql: String) {
        val cursor = db.query(sql)
        cursor.close()
    }
}
