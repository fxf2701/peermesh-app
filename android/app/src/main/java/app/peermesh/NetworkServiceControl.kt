package app.peermesh

import android.content.Context
import app.peermesh.ble.BleForegroundService
import app.peermesh.relay.RelaySyncLifecycleCoordinator
import app.peermesh.relay.RelaySyncSettings

/** User intent is persisted before reconciling transport lifecycle. */
object NetworkServiceControl {
    fun meshEnabled(context: Context) = PeerMeshPrefs.isBleMeshUserEnabled(context)
    fun cloudEnabled(context: Context) = RelaySyncSettings.isEnabled(context)

    fun setMeshEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        PeerMeshPrefs.setBleMeshUserEnabled(app, enabled)
        if (enabled) {
            BleForegroundService.startMesh(app, caller = "network_controls", reason = "user_enabled")
        } else {
            BleForegroundService.stopMesh(app, caller = "network_controls", reason = "user_disabled")
        }
    }

    fun setCloudEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        RelaySyncSettings.setEnabled(app, enabled)
        if (enabled) RelaySyncLifecycleCoordinator.onUserRequestedImmediateSync(app)
        else RelaySyncLifecycleCoordinator.onUserDisabledRelay(app)
    }
}
