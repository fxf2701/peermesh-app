package app.peermesh

import android.content.Context
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.peermesh.ble.BleForegroundService
import app.peermesh.ble.BleMeshWorkManager
import app.peermesh.ble.BlePermissionHelper

object ActiveGroupManager {
    private const val PREFS_NAME = PeerMeshPrefs.NAME
    private const val KEY_ACTIVE_GID = "active_gid"

    private fun keyForActor(actorDid: String?): String {
        return if (actorDid.isNullOrBlank()) {
            KEY_ACTIVE_GID
        } else {
            "${KEY_ACTIVE_GID}_$actorDid"
        }
    }

    fun getActiveGroup(context: Context): String? {
        val actorDid = DeviceIdProvider.get(context)
        return getActiveGroup(context, actorDid)
    }

    fun getActiveGroup(context: Context, actorDid: String?): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(keyForActor(actorDid), null)
    }

    fun setActiveGroup(
        context: Context,
        gid: String,
        onLocationUnavailable: (() -> Unit)? = null,
        onBluetoothUnavailable: (() -> Unit)? = null,
    ) {
        val actorDid = DeviceIdProvider.get(context)
        setActiveGroup(context, actorDid, gid, onLocationUnavailable, onBluetoothUnavailable)
    }

    fun setActiveGroup(
        context: Context,
        actorDid: String?,
        gid: String,
        onLocationUnavailable: (() -> Unit)? = null,
        onBluetoothUnavailable: (() -> Unit)? = null,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(keyForActor(actorDid), gid)
            .apply()
        val hasPerms = BlePermissionHelper.requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (!hasPerms) {
            Toast.makeText(
                context,
                context.getString(R.string.mesh_start_need_permission),
                Toast.LENGTH_SHORT
            ).show()
            return
        } else {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
                    onLocationUnavailable?.invoke() ?: Toast.makeText(
                        context,
                        context.getString(R.string.mesh_start_need_location),
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }
            }
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = manager.adapter
            val bleReady = adapter != null && adapter.isEnabled && adapter.bluetoothLeAdvertiser != null
            if (!bleReady) {
                onBluetoothUnavailable?.invoke() ?: Toast.makeText(
                    context,
                    context.getString(R.string.mesh_start_need_bluetooth),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }
        if (PeerMeshPrefs.isBleMeshUserEnabled(context)) {
            BleForegroundService.startMesh(
                context,
                caller = "ActiveGroupManager.setActiveGroup",
                reason = "active_group_selected",
            )
            BleMeshWorkManager.enqueue(context)
        }
        SysMetadataSyncWorkManager.enqueue(context)
    }

    fun clearActiveGroup(context: Context) {
        val actorDid = DeviceIdProvider.get(context)
        clearActiveGroup(context, actorDid)
    }

    fun clearActiveGroup(context: Context, actorDid: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(keyForActor(actorDid))
            .apply()
        if (PeerMeshPrefs.isBleMeshUserEnabled(context)) {
            if (BleForegroundService.canStartMesh(context)) {
                BleForegroundService.startMesh(
                    context,
                    caller = "ActiveGroupManager.clearActiveGroup",
                    reason = "carrier_only_after_active_group_cleared",
                )
                BleMeshWorkManager.enqueue(context)
            } else {
                // Active group state must not own the BLE carrier lifecycle.
                // If the environment cannot start right now, leave the user-enabled
                // carrier policy intact so the normal foreground / permission / BT / location
                // hooks can start transport as soon as requirements become ready.
                app.peermesh.data.debug.ActivityLog.add(
                    label = "BLE carrier lifecycle preserved",
                    source = "mesh",
                    message = "caller=ActiveGroupManager.clearActiveGroup reason=active_group_cleared_cannot_start_now action=preserve_user_enabled_carrier",
                )
            }
        } else {
            app.peermesh.data.debug.ActivityLog.add(
                label = "BLE carrier lifecycle preserved",
                source = "mesh",
                message = "caller=ActiveGroupManager.clearActiveGroup reason=user_manual_off action=leave_stopped",
            )
        }
        SysMetadataSyncWorkManager.cancel(context)
    }
}
