package app.peermesh

import android.content.Context
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.security.crypto.SecureKeyStore

object GroupLocalDataManager {
    fun deleteGroupFromDevice(context: Context, gid: String) {
        val db = AppDatabaseProvider.get(context.applicationContext)
        db.runInTransaction {
            db.outboundBatchDao().deletePeerDeliveriesByGid(gid)
            db.outboundBatchDao().deleteBatchEventsByGid(gid)
            db.outboundBatchDao().deleteBatchesByGid(gid)
            db.dtnPendingPullDao().deleteByGid(gid)
            db.dtnAdvertisedDao().deleteByGid(gid)
            db.dtnSyncCursorDao().deleteByGid(gid)
            db.dtnPeerStateDao().deleteByGid(gid)
            db.relayCursorDao().deleteByGid(gid)
            db.mapItemVoteDao().deleteByGid(gid)
            db.mapMarkerFieldClockDao().deleteByGid(gid)
            db.mapDao().deleteMarkersByGid(gid)
            db.mapDao().deleteAreasByGid(gid)
            db.mapDao().deleteLayersByGid(gid)
            db.liveTrailDao().deletePointsByGid(gid)
            db.liveTrailDao().deleteTrailsByGid(gid)
            db.chatDao().deleteByGid(gid)
            db.eventDedupeDao().deleteByGid(gid)
            db.eventsDao().deleteByGid(gid)
            db.removedMemberDao().deleteByGroup(gid)
            db.userProfileDao().deleteByGid(gid)
            db.groupDao().delete(gid)
        }
        SecureKeyStore(context.applicationContext).clear(gid)
    }
}
