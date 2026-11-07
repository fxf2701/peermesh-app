package app.peermesh

import app.peermesh.beta.PeerMeshBetaProgram
import app.peermesh.beta.PeerMeshCapabilityProfile
import app.peermesh.beta.PeerMeshCapabilityResolver

object PeerMeshCapabilities {
    val tier: String
        get() = profile.featureTier

    val usageLimitTier: String
        get() = profile.usageLimitTier

    val isFree: Boolean
        get() = tier == TIER_FREE

    val isPlus: Boolean
        get() = tier == TIER_PLUS

    val isPro: Boolean
        get() = tier == TIER_PRO

    val isBetaEnabled: Boolean
        get() = profile.betaEnabled

    val hasPremiumFeatureAccess: Boolean
        get() = profile.unlocksPremiumFeatures

    val canUseMap: Boolean
        get() = hasPremiumFeatureAccess

    val canUseTrail: Boolean
        get() = hasPremiumFeatureAccess

    val canUseSharedLiveTrail: Boolean
        get() = hasPremiumFeatureAccess

    val canUseAutoTrail: Boolean
        get() = canUseTrail && BuildConfig.ENABLE_AUTO_TRAIL

    val canUseMovementSync: Boolean
        get() = hasPremiumFeatureAccess

    val canUseRelaySync: Boolean
        get() = true

    val canUseEnhancedRetry: Boolean
        get() = hasPremiumFeatureAccess

    val canUseEnhancedBackgroundRecovery: Boolean
        get() = hasPremiumFeatureAccess

    val canUseReliabilityDiagnostics: Boolean
        get() = hasPremiumFeatureAccess

    val canUseEnhancedReliability: Boolean
        get() = canUseEnhancedRetry ||
            canUseEnhancedBackgroundRecovery ||
            canUseRelaySync ||
            canUseMovementSync ||
            canUseReliabilityDiagnostics

    private val profile: PeerMeshCapabilityProfile
        get() = PeerMeshCapabilityResolver.resolve(
            entitlementState = PeerMeshEntitlements.current(),
            betaProgramConfig = PeerMeshBetaProgram.currentConfig(),
        )

    const val TIER_FREE = "free"
    const val TIER_PLUS = "plus"
    const val TIER_PRO = "pro"
}
