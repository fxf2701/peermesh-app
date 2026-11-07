package app.peermesh.data.membership

import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabase

object GroupMembershipPolicy {
    fun requiresActiveMembership(type: String): Boolean {
        return when (type) {
            EventTypes.SYS_MEMBER_REMOVED,
            EventTypes.SYS_GROUP_REKEY,
            EventTypes.SYS_GROUP_KEY_SHARE,
            EventTypes.SYS_GROUP,
            EventTypes.SYS_GROUP_UPD,
            -> false
            else -> EventTypes.isChat(type) ||
                EventTypes.isMap(type) ||
                EventTypes.isLiveTrail(type) ||
                type == EventTypes.SYS_ACK ||
                type == EventTypes.SYS_USER ||
                type == EventTypes.SYS_USER_UPD ||
                type == EventTypes.SYS_PRESENCE ||
                type == EventTypes.SYS_ALERT
        }
    }

    fun isPeerRemoved(db: AppDatabase, gid: String, peerId: String): Boolean {
        if (gid.isBlank() || peerId.isBlank()) return false
        return db.removedMemberDao().count(gid, peerId) > 0
    }

    fun shouldRejectEvent(db: AppDatabase, gid: String, core: EventCore): Boolean {
        if (!requiresActiveMembership(core.type)) return false
        return isPeerRemoved(db, gid, core.actor.did)
    }

    fun shouldRejectForRemovedLocalMember(
        db: AppDatabase,
        gid: String,
        core: EventCore,
        localPeerId: String?,
    ): Boolean {
        if (!requiresActiveMembership(core.type)) return false
        val safeLocalPeerId = localPeerId?.takeIf { it.isNotBlank() } ?: return false
        return isPeerRemoved(db, gid, safeLocalPeerId)
    }
}
