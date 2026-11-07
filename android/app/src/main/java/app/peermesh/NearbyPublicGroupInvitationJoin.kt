package app.peermesh

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.peermesh.nearby.NearbyRoomRepository

/** Routes an explicit public-invitation Join action to the existing deep-link Join UI. */
internal object NearbyPublicGroupInvitationJoin {
    fun open(activity: Activity, roomId: String, messageId: String) {
        Thread {
            val link = runCatching {
                NearbyRoomRepository(activity.applicationContext)
                    .resolvePublicInvitationJoinLink(roomId, messageId)
            }.getOrNull()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (link == null) {
                    Toast.makeText(
                        activity,
                        R.string.nearby_public_invitation_join_unavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@runOnUiThread
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link), activity, MainActivity::class.java)
                activity.startActivity(intent)
            }
        }.start()
    }
}
