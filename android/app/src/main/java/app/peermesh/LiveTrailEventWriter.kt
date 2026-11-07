package app.peermesh

import android.content.Context
import android.net.ConnectivityManager
import android.bluetooth.BluetoothManager
import androidx.core.content.ContextCompat
import app.peermesh.core.json.CanonicalJson
import app.peermesh.core.model.EventCore
import app.peermesh.core.types.EventTypes
import app.peermesh.data.db.AppDatabaseProvider
import app.peermesh.data.db.entity.EventEntity
import app.peermesh.data.reducer.LiveTrailReducer
import app.peermesh.data.membership.GroupMembershipPolicy
import app.peermesh.security.crypto.SecureKeyStore
import app.peermesh.ble.BleMeshRuntime
import app.peermesh.ble.BlePermissionHelper
import app.peermesh.data.debug.ActivityLog

object LiveTrailEventWriter {
    fun storeAndBroadcast(
        context: Context,
        gid: String,
        core: EventCore,
        targetDeviceIds: Set<String> = emptySet(),
    ): Boolean {
        if (!PeerMeshEventPolicy.canGenerateLocal(core.type)) {
            ActivityLog.add(
                label = "Live trail event blocked",
                source = "trail",
                message = "type=${core.type} reason=${PeerMeshEventPolicy.deniedReason(core.type)}",
                eventId = core.id,
                gid = gid,
            )
            return false
        }
        val db = AppDatabaseProvider.get(context)
        if (GroupMembershipPolicy.isPeerRemoved(db, gid, DeviceIdProvider.get(context))) {
            ActivityLog.add(
                label = "Live trail event blocked",
                source = "trail",
                message = "type=${core.type} reason=removed_member",
                eventId = core.id,
                gid = gid,
            )
            return false
        }
        val nowSeconds = System.currentTimeMillis() / 1000L
        val coreWithKid = KeyIdHelper.attachKeyId(context, gid, core)
        val trailId = coreWithKid.ref.target
        app.peermesh.data.debug.ActivityLog.add(
            label = "Live trail event",
            source = "trail",
            message = "type=${coreWithKid.type} trailId=$trailId targets=${targetDeviceIds.size}",
            eventId = coreWithKid.id,
            gid = gid,
        )
        val payloadJson = CanonicalJson.json.encodeToString(EventCore.serializer(), coreWithKid)
        val event = EventEntity(
            gid = gid,
            event_id = coreWithKid.id,
            type = coreWithKid.type,
            source = "local",
            created_t = coreWithKid.time.t,
            time_lt = coreWithKid.time.lt ?: 0L,
            actor_did = coreWithKid.actor.did,
            ref_root = coreWithKid.ref.root,
            ref_parent = coreWithKid.ref.parent,
            ref_target = coreWithKid.ref.target,
            ttl_s = coreWithKid.meta?.ttl_s,
            payload_json = payloadJson,
            inserted_at = nowSeconds,
        )
        var inserted = false
        db.runInTransaction {
            val result = db.eventsDao().insert(event)
            if (result != -1L) {
                inserted = true
                LiveTrailReducer(db.liveTrailDao()).apply(coreWithKid, gid)
                if (trailId != null) {
                    db.liveTrailDao().updateAccepted(gid, trailId, 1, null)
                }
            }
        }
        if (!inserted) return false

        UserOutbox.enqueue(
            context = context,
            gid = gid,
            core = coreWithKid,
            source = "mesh",
            successLabel = "Sent live trail event",
            successMessage = coreWithKid.type,
            targetDeviceIds = targetDeviceIds,
        )
        return true
    }

    private fun hasBlePermissions(context: Context): Boolean {
        return BlePermissionHelper.requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isBleReady(context: Context): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
    }

}
