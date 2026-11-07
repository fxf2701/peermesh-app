package app.peermesh

import android.content.Context
import android.content.SharedPreferences
import app.peermesh.data.db.AppDatabaseProvider

object LiveTrailCleanupManager {
    private const val PREFS_NAME = "live_trail_cleanup"
    private const val KEY_LAST_CLEANUP = "last_cleanup_s"

    private const val PENDING_EXPIRY_SEC = 24L * 60L * 60L
    private const val ACTIVE_STALE_SEC = 60L * 24L * 60L * 60L
    private const val TRAIL_RETENTION_SEC = 7L * 24L * 60L * 60L
    private const val CLEANUP_INTERVAL_SEC = 60L * 60L
    @Volatile private var cleanupRunning = false

    fun runIfDue(context: Context) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val prefs = prefs(context)
        val last = prefs.getLong(KEY_LAST_CLEANUP, 0L)
        if (nowSeconds - last < CLEANUP_INTERVAL_SEC) return
        prefs.edit().putLong(KEY_LAST_CLEANUP, nowSeconds).apply()
        runAsync(context, nowSeconds)
    }

    fun runNow(context: Context, nowSeconds: Long = System.currentTimeMillis() / 1000L) {
        runAsync(context, nowSeconds)
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun runAsync(context: Context, nowSeconds: Long) {
        if (cleanupRunning) return
        cleanupRunning = true
        Thread {
            try {
                runNowBlocking(context, nowSeconds)
            } finally {
                cleanupRunning = false
            }
        }.start()
    }

    fun runNowBlocking(context: Context, nowSeconds: Long = System.currentTimeMillis() / 1000L) {
        val db = AppDatabaseProvider.get(context)
        val pendingCutoff = nowSeconds - PENDING_EXPIRY_SEC
        val pendingTrails = db.liveTrailDao().findExpiredPendingTrails(pendingCutoff)
        if (pendingTrails.isNotEmpty()) {
            val pendingTrailIds = pendingTrails.map { it.trail_id }
            db.liveTrailDao().deletePointsByTrailIds(pendingTrailIds)
            db.liveTrailDao().deleteTrailsByIds(pendingTrailIds)
            val types = listOf(
                app.peermesh.core.types.EventTypes.LIVE_TRAIL_START,
                app.peermesh.core.types.EventTypes.LIVE_TRAIL_POINT_BATCH,
                app.peermesh.core.types.EventTypes.LIVE_TRAIL_END,
            )
            pendingTrails.forEach { trail ->
                db.eventsDao().deleteByTypesAndTarget(trail.gid, trail.trail_id, types)
            }
        }

        val endedCutoff = nowSeconds - TRAIL_RETENTION_SEC
        val endedTrailIds = db.liveTrailDao().findEndedTrailIdsBefore(endedCutoff)
        if (endedTrailIds.isNotEmpty()) {
            db.liveTrailDao().deletePointsByTrailIds(endedTrailIds)
            db.liveTrailDao().deleteTrailsByIds(endedTrailIds)
        }

        val activeCutoff = nowSeconds - ACTIVE_STALE_SEC
        val staleActiveIds = db.liveTrailDao().findStaleActiveTrailIds(activeCutoff)
        if (staleActiveIds.isNotEmpty()) {
            db.liveTrailDao().deletePointsByTrailIds(staleActiveIds)
            db.liveTrailDao().deleteTrailsByIds(staleActiveIds)
        }

        db.liveTrailDao().deletePointsBefore(endedCutoff)
    }
}
