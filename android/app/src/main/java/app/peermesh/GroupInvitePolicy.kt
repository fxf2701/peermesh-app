package app.peermesh

import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabase

object GroupInvitePolicy {
    fun requiresRotation(db: AppDatabase, gid: String): Boolean {
        if (gid.isBlank()) return false
        return db.eventsDao().countByType(gid, EventTypes.SYS_GROUP_REKEY) > 0
    }

    fun inviteMaterialAvailable(
        isRemoved: Boolean,
        hasSalt: Boolean,
        hasPassphrase: Boolean,
        requiresRotation: Boolean,
    ): Boolean {
        if (isRemoved) return false
        if (!hasSalt) return false
        if (!hasPassphrase) return false
        if (requiresRotation) return false
        return true
    }
}
