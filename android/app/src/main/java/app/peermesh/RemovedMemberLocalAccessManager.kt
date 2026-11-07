package app.peermesh

import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.reducer.JsonAccess
import app.peermesh.security.crypto.SecureKeyStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object RemovedMemberLocalAccessManager {
    fun handleEventInserted(context: android.content.Context, gid: String, eventId: String) {
        Thread {
            val db = AppDatabaseProvider.get(context.applicationContext)
            val event = db.eventsDao().findById(gid, eventId) ?: return@Thread
            if (event.type != app.peermesh.core.types.EventTypes.SYS_MEMBER_REMOVED) return@Thread
            val payload = runCatching {
                Json.parseToJsonElement(event.payload_json) as? JsonObject
            }.getOrNull() ?: return@Thread
            val targetPeerId = JsonAccess.stringValue(payload, "target_peer_id")
                ?: event.ref_target
                ?: return@Thread
            val localActor = DeviceIdProvider.getActiveActor(context.applicationContext)
            if (targetPeerId != localActor) return@Thread
            SecureKeyStore(context.applicationContext).clearJoinMaterial(gid)
            ActivityLog.add(
                label = "Removed member local join material cleared",
                source = "moderation",
                message = "gid=$gid actor=$localActor",
                eventId = eventId,
                gid = gid,
            )
        }.start()
    }
}
