package app.peermesh

import android.content.Context
import app.peermesh.transport.ble.BleConfig

object PeerMeshPrefs {
    const val NAME = "peer_mesh_prefs"
    const val KEY_BLE_FORWARD_OPAQUE = "ble_forward_opaque"
    const val DEFAULT_BLE_FORWARD_OPAQUE = true
    const val KEY_BLE_MESH_USER_ENABLED = "ble_mesh_user_enabled"
    const val DEFAULT_BLE_MESH_USER_ENABLED = true
    private const val KEY_AUTOMATIC_NEARBY_MIGRATED = "automatic_nearby_migrated"

    fun applyBleForwardingConfig(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        BleConfig.forwardOpaqueMode = prefs.getBoolean(
            KEY_BLE_FORWARD_OPAQUE,
            DEFAULT_BLE_FORWARD_OPAQUE,
        )
    }

    fun isBleMeshUserEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BLE_MESH_USER_ENABLED, DEFAULT_BLE_MESH_USER_ENABLED)
    }

    fun setBleMeshUserEnabled(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_BLE_MESH_USER_ENABLED, enabled).apply()
    }

    fun migrateLegacyNearbyControlToAutomatic(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTOMATIC_NEARBY_MIGRATED, false)) return
        prefs.edit()
            .putBoolean(KEY_BLE_MESH_USER_ENABLED, true)
            .putBoolean(KEY_AUTOMATIC_NEARBY_MIGRATED, true)
            .apply()
    }
}
