package app.peermesh.data.reducer

import app.peermesh.core.model.EventCore
import app.peermesh.core.time.EventClock
import app.peermesh.core.time.EventClockComparator
import app.peermesh.core.types.EventTypes
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.db.dao.GroupDao
import app.peermesh.data.db.dao.RemovedMemberDao
import app.peermesh.data.db.dao.UserProfileDao
import app.peermesh.data.db.entity.RemovedMemberEntity
import app.peermesh.data.db.entity.UserProfileEntity

class SysReducer(
    private val groupDao: GroupDao,
    private val userProfileDao: UserProfileDao,
    private val removedMemberDao: RemovedMemberDao,
) {
    fun apply(event: EventCore, gid: String) {
        when (event.type) {
            EventTypes.SYS_GROUP, EventTypes.SYS_GROUP_UPD -> applyGroupUpdate(event, gid)
            EventTypes.SYS_USER, EventTypes.SYS_USER_UPD -> applyUserUpdate(event, gid)
            EventTypes.SYS_MEMBER_REMOVED -> applyMemberRemoved(event, gid)
            EventTypes.SYS_GROUP_REKEY -> applyGroupRekey(event, gid)
        }
    }

    private fun applyGroupUpdate(event: EventCore, gid: String) {
        val name = JsonAccess.stringValue(event.data, "name") ?: return
        val existing = groupDao.findGroup(gid) ?: return
        val incomingOwnerDid = JsonAccess.stringValue(event.data, "owner_did")
        val incomingContextProfile = JsonAccess.stringValue(event.data, "context_profile")
        val shouldRefreshName = event.time.t >= existing.updated_at
        val shouldBackfillOwner = existing.owner_did.isNullOrBlank() && !incomingOwnerDid.isNullOrBlank()
        val shouldBackfillContext = existing.context_profile.isBlank() && !incomingContextProfile.isNullOrBlank()
        if (!shouldRefreshName && !shouldBackfillOwner && !shouldBackfillContext) return
        groupDao.upsert(
            existing.copy(
                name = if (shouldRefreshName) name else existing.name,
                owner_did = if (shouldBackfillOwner) incomingOwnerDid else existing.owner_did,
                context_profile = if (shouldRefreshName) {
                    incomingContextProfile ?: existing.context_profile
                } else if (shouldBackfillContext) {
                    incomingContextProfile ?: existing.context_profile
                } else {
                    existing.context_profile
                },
                updated_at = maxOf(existing.updated_at, event.time.t),
            )
        )
    }

    private fun applyUserUpdate(event: EventCore, gid: String) {
        val displayName = JsonAccess.stringValue(event.data, "display_name")
            ?: JsonAccess.stringValue(event.data, "name")
            ?: return
        val avatarId = JsonAccess.stringValue(event.data, "avatar_id")
        val existing = userProfileDao.find(gid, event.actor.did)
        val incoming = EventClock(event.time.t, event.time.lt ?: 0L, event.actor.did)
        val existingClock = existing?.let {
            EventClock(it.last_event_t, it.last_event_lt, it.last_event_actor)
        }
        if (existingClock != null && !EventClockComparator.isNewer(incoming, existingClock)) return

        val profile = UserProfileEntity(
            gid = gid,
            actor_did = event.actor.did,
            display_name = displayName,
            avatar_id = avatarId ?: existing?.avatar_id,
            updated_at = event.time.t,
            last_event_id = event.id,
            last_event_t = event.time.t,
            last_event_lt = event.time.lt ?: 0L,
            last_event_actor = event.actor.did,
        )
        userProfileDao.upsert(profile)
    }

    private fun applyMemberRemoved(event: EventCore, gid: String) {
        val group = groupDao.findGroup(gid) ?: return
        val ownerDid = group.owner_did ?: return
        if (event.actor.did != ownerDid) return
        val targetPeerId = JsonAccess.stringValue(event.data, "target_peer_id")
            ?: event.ref.target
            ?: return
        if (targetPeerId.isBlank() || targetPeerId == ownerDid) return
        val existing = removedMemberDao.find(gid, targetPeerId)
        val incoming = EventClock(event.time.t, event.time.lt ?: 0L, event.actor.did)
        val existingClock = existing?.let {
            EventClock(it.lastEventT, it.lastEventLt, it.lastEventActor)
        }
        if (existingClock != null && !EventClockComparator.isNewer(incoming, existingClock)) return
        removedMemberDao.upsert(
            RemovedMemberEntity(
                groupId = gid,
                peerId = targetPeerId,
                removedByPeerId = event.actor.did,
                removalEventId = event.id,
                removedAtSeconds = event.time.t,
                lastEventT = event.time.t,
                lastEventLt = event.time.lt ?: 0L,
                lastEventActor = event.actor.did,
            )
        )
        ActivityLog.add(
            label = "Member removal applied",
            source = "reducer",
            message = "gid=$gid owner=$ownerDid target=$targetPeerId",
            eventId = event.id,
            gid = gid,
        )
    }

    private fun applyGroupRekey(event: EventCore, gid: String) {
        val group = groupDao.findGroup(gid) ?: return
        val ownerDid = group.owner_did
        if (!ownerDid.isNullOrBlank() && event.actor.did != ownerDid) return
        val payload = event.data as? kotlinx.serialization.json.JsonObject ?: return
        val previousKid = JsonAccess.stringValue(payload, "previous_kid")
        val newKid = JsonAccess.stringValue(payload, "new_kid")?.trim().orEmpty()
        if (newKid.isBlank()) return
        ActivityLog.add(
            label = "Group rekey event applied",
            source = "reducer",
            message = "gid=$gid actor=${event.actor.did} previous_kid=${previousKid ?: "-"} new_kid=$newKid activation=deferred",
            eventId = event.id,
            gid = gid,
        )
    }
}
