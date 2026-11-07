package app.peermesh

import android.content.Context
import app.peermesh.ble.BlePeerCloudStateRegistry
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.debug.ActivityLog
import app.peermesh.data.ingest.EventSources
import app.peermesh.relay.RelaySyncSettings
import kotlinx.serialization.decodeFromString

object CloudToBleBridge {
    private const val SOURCE_RELAY_BRIDGE = "relay_bridge"

    fun onEventInserted(context: Context, gid: String, eventId: String) {
        Thread {
            if (!RelaySyncSettings.isCloudToBleBridgeEnabled(context)) {
                return@Thread
            }
            if (!BlePeerCloudStateRegistry.shouldBridgeGroup(gid)) {
                return@Thread
            }
            val db = AppDatabaseProvider.get(context)
            val entity = db.eventsDao().findById(gid, eventId) ?: return@Thread
            val core = bridgeableCore(entity) ?: return@Thread
            val accepted = UserOutbox.enqueue(
                context = context,
                gid = gid,
                core = core,
                source = SOURCE_RELAY_BRIDGE,
                successLabel = "Cloud->BLE bridge queued",
                successMessage = bridgeSummary(core),
                failureLabel = "Cloud->BLE bridge failed",
            )
            if (!accepted) {
                ActivityLog.add(
                    label = "Cloud->BLE bridge failed",
                    source = SOURCE_RELAY_BRIDGE,
                    message = "type=${core.type} reason=enqueue_rejected",
                    eventId = core.id,
                    gid = gid,
                )
            }
        }.start()
    }

    internal fun bridgeableCore(entity: EventEntity): EventCore? {
        if (!shouldBridge(entity.source, entity.type)) return null
        return runCatching {
            CanonicalJson.json.decodeFromString<EventCore>(entity.payload_json)
        }.getOrNull()
    }

    internal fun shouldBridge(source: String, type: String): Boolean {
        if (source != EventSources.RELAY) return false
        return EventTypes.isChat(type) ||
            EventTypes.isMap(type) ||
            EventTypes.isLiveTrail(type)
    }

    private fun bridgeSummary(core: EventCore): String {
        return when {
            EventTypes.isChat(core.type) -> "relay_chat_bridge"
            EventTypes.isMap(core.type) -> "relay_map_bridge"
            EventTypes.isLiveTrail(core.type) -> "relay_trail_bridge"
            else -> "relay_bridge"
        }
    }
}
