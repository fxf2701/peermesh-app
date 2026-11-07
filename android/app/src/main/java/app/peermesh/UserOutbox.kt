package app.peermesh

import android.content.Context
import app.peermesh.core.model.EventCore
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.membership.GroupMembershipPolicy

object UserOutbox {
    fun enqueue(
        context: Context,
        gid: String,
        core: EventCore,
        source: String,
        successLabel: String,
        successMessage: String,
        failureLabel: String = "Mesh send queued",
        targetDeviceIds: Set<String> = emptySet(),
    ): Boolean {
        if (!PeerMeshEventPolicy.canSendOutbound(core.type)) {
            ActivityLog.add(
                label = "USER_OUTBOX_BLOCKED",
                source = source,
                message = "type=${core.type} reason=${PeerMeshEventPolicy.deniedReason(core.type)}",
                eventId = core.id,
                gid = gid,
            )
            return false
        }
        val db = AppDatabaseProvider.get(context.applicationContext)
        if (GroupMembershipPolicy.shouldRejectEvent(db, gid, core)) {
            ActivityLog.add(
                label = "Removed member outbound blocked",
                source = source,
                message = "type=${core.type} reason=removed_member actor=${core.actor.did}",
                eventId = core.id,
                gid = gid,
            )
            return false
        }
        val accepted = OutboundBatchDispatcher.enqueue(
            context = context,
            gid = gid,
            core = core,
            source = source,
            targetDeviceIds = targetDeviceIds,
        )
        if (accepted) {
            ActivityLog.add(
                label = "USER_OUTBOX_ENQUEUE",
                source = source,
                message = "type=${core.type} persisted=true targets=${targetDeviceIds.size}",
                eventId = core.id,
                gid = gid,
            )
            ActivityLog.add(
                label = "Event transport outcome",
                source = source,
                message = "status=queued type=${core.type} targets=${targetDeviceIds.size}",
                eventId = core.id,
                gid = gid,
            )
            ActivityLog.add(
                label = successLabel,
                source = source,
                message = buildString {
                    append("text=")
                    append(successMessage)
                    append(" actor=")
                    append(core.actor.did)
                    append(" created_s=")
                    append(core.time.t)
                    append(" send_ms=")
                    append(System.currentTimeMillis())
                },
                eventId = core.id,
                gid = gid,
            )
        } else {
            ActivityLog.add(
                label = failureLabel,
                source = source,
                message = "persist_failed",
                eventId = core.id,
                gid = gid,
            )
        }
        return accepted
    }
}
