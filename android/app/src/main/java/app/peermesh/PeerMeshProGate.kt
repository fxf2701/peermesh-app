package app.peermesh

import android.app.Activity
import android.content.Context
import android.content.Intent

object PeerMeshProGate {
    const val FEATURE_MAP = "map"
    const val FEATURE_TRAIL = "trail"
    const val FEATURE_SHARED_LIVE_TRAIL = "shared_live_trail"

    fun canUse(feature: String): Boolean {
        return when (feature) {
            FEATURE_MAP -> PeerMeshCapabilities.canUseMap
            FEATURE_TRAIL -> PeerMeshCapabilities.canUseTrail
            FEATURE_SHARED_LIVE_TRAIL -> PeerMeshCapabilities.canUseSharedLiveTrail
            else -> PeerMeshCapabilities.hasPremiumFeatureAccess
        }
    }

    fun require(activity: Activity, feature: String): Boolean {
        if (canUse(feature)) return true
        open(activity, feature)
        return false
    }

    fun open(context: Context, feature: String) {
        context.startActivity(
            Intent(context, ProUnlockActivity::class.java).apply {
                putExtra(ProUnlockActivity.EXTRA_FEATURE, feature)
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        )
    }
}
