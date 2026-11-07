package app.peermesh.beta

import app.peermesh.EntitlementState

data class PeerMeshCapabilityProfile(
    val featureTier: String,
    val usageLimitTier: String,
    val betaEnabled: Boolean,
) {
    val unlocksPremiumFeatures: Boolean
        get() = featureTier == TIER_PLUS || featureTier == TIER_PRO

    companion object {
        const val TIER_FREE = "free"
        const val TIER_PLUS = "plus"
        const val TIER_PRO = "pro"
    }
}

object PeerMeshCapabilityResolver {
    fun resolve(
        entitlementState: EntitlementState,
        betaProgramConfig: BetaProgramConfig?,
    ): PeerMeshCapabilityProfile {
        return when {
            entitlementState.unlocksPro -> PeerMeshCapabilityProfile(
                featureTier = PeerMeshCapabilityProfile.TIER_PRO,
                usageLimitTier = PeerMeshCapabilityProfile.TIER_PRO,
                betaEnabled = betaProgramConfig?.enabled == true,
            )

            betaProgramConfig?.enabled == true -> PeerMeshCapabilityProfile(
                featureTier = PeerMeshCapabilityProfile.TIER_PLUS,
                usageLimitTier = PeerMeshCapabilityProfile.TIER_FREE,
                betaEnabled = true,
            )

            else -> PeerMeshCapabilityProfile(
                featureTier = PeerMeshCapabilityProfile.TIER_FREE,
                usageLimitTier = PeerMeshCapabilityProfile.TIER_FREE,
                betaEnabled = false,
            )
        }
    }
}
