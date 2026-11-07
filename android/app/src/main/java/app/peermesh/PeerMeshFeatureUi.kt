package app.peermesh

import android.app.Activity
import android.content.Intent
import android.view.Menu
import com.google.android.material.navigation.NavigationView

object PeerMeshFeatureUi {
    fun applyDrawerCapabilities(navView: NavigationView) {
        navView.menu.findItem(R.id.menu_test)?.isVisible = BuildConfig.ENABLE_DEBUG_TOOLS
        navView.menu.findItem(R.id.menu_trails)?.title = proTitle(
            navView.context.getString(R.string.menu_trails),
            PeerMeshCapabilities.canUseTrail,
        )
    }

    fun applyGroupChatMenuCapabilities(menu: Menu) {
        menu.findItem(R.id.menu_group_map)?.title = proTitle(
            menu.findItem(R.id.menu_group_map)?.title?.toString().orEmpty(),
            PeerMeshCapabilities.canUseMap,
        )
    }

    fun openTrailOrPaywall(activity: Activity) {
        if (PeerMeshProGate.require(activity, PeerMeshProGate.FEATURE_TRAIL)) {
            activity.startActivity(Intent(activity, TrailActivity::class.java))
        }
    }

    fun openMapOrPaywall(activity: Activity, gid: String, groupName: String?) {
        if (PeerMeshProGate.require(activity, PeerMeshProGate.FEATURE_MAP)) {
            activity.startActivity(
                Intent(activity, GroupMapActivity::class.java).apply {
                    putExtra(GroupMapActivity.EXTRA_GID, gid)
                    putExtra(GroupMapActivity.EXTRA_GROUP_NAME, groupName)
                }
            )
        }
    }

    private fun proTitle(title: String, unlocked: Boolean): String {
        if (unlocked || title.endsWith(PRO_SUFFIX)) return title
        return "$title$PRO_SUFFIX"
    }

    private const val PRO_SUFFIX = " Pro"
}
