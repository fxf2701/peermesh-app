package app.peermesh

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.widget.CompoundButton

/** Shared UI behavior; never toggles a service before confirmation. */
object NetworkServiceToggleUi {
    fun bind(activity: Activity, button: CompoundButton, mesh: Boolean, refresh: () -> Unit = {}) {
        val enabled = if (mesh) NetworkServiceControl.meshEnabled(activity) else NetworkServiceControl.cloudEnabled(activity)
        button.setOnCheckedChangeListener(null)
        button.isChecked = enabled
        button.isEnabled = mesh || PeerMeshCapabilities.canUseRelaySync
        button.setOnCheckedChangeListener { _, checked ->
            if (checked == enabled) return@setOnCheckedChangeListener
            if (!checked) {
                button.isChecked = true
                AlertDialog.Builder(activity)
                    .setTitle(if (mesh) R.string.network_control_stop_mesh_title else R.string.network_control_stop_cloud_title)
                    .setMessage(if (mesh) R.string.network_control_stop_mesh_message else R.string.network_control_stop_cloud_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.network_control_turn_off) { _, _ ->
                        change(activity, mesh, false)
                        bind(activity, button, mesh, refresh)
                        refresh()
                    }.show()
            } else {
                change(activity, mesh, true)
                bind(activity, button, mesh, refresh)
                refresh()
            }
        }
    }

    private fun change(activity: Activity, mesh: Boolean, enabled: Boolean) {
        if (mesh) NetworkServiceControl.setMeshEnabled(activity, enabled)
        else NetworkServiceControl.setCloudEnabled(activity, enabled)
    }
}
