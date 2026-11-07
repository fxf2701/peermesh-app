package app.peermesh.beta

import app.peermesh.EntitlementState
import app.peermesh.EntitlementStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerMeshCapabilityResolverTest {
    @Test
    fun `beta maps features to plus and limits to free`() {
        val profile = PeerMeshCapabilityResolver.resolve(
            entitlementState = EntitlementState(
                status = EntitlementStatus.FREE,
                source = "default",
                expiresAtMs = null,
                updatedAtMs = 0L,
            ),
            betaProgramConfig = BetaProgramConfig(enabled = true),
        )

        assertEquals(PeerMeshCapabilityProfile.TIER_PLUS, profile.featureTier)
        assertEquals(PeerMeshCapabilityProfile.TIER_FREE, profile.usageLimitTier)
        assertTrue(profile.unlocksPremiumFeatures)
        assertTrue(profile.betaEnabled)
    }

    @Test
    fun `pro still wins over beta`() {
        val profile = PeerMeshCapabilityResolver.resolve(
            entitlementState = EntitlementState(
                status = EntitlementStatus.PRO,
                source = "billing",
                expiresAtMs = null,
                updatedAtMs = 0L,
            ),
            betaProgramConfig = BetaProgramConfig(enabled = true),
        )

        assertEquals(PeerMeshCapabilityProfile.TIER_PRO, profile.featureTier)
        assertEquals(PeerMeshCapabilityProfile.TIER_PRO, profile.usageLimitTier)
    }

    @Test
    fun `free without beta stays free`() {
        val profile = PeerMeshCapabilityResolver.resolve(
            entitlementState = EntitlementState(
                status = EntitlementStatus.FREE,
                source = "default",
                expiresAtMs = null,
                updatedAtMs = 0L,
            ),
            betaProgramConfig = null,
        )

        assertEquals(PeerMeshCapabilityProfile.TIER_FREE, profile.featureTier)
        assertEquals(PeerMeshCapabilityProfile.TIER_FREE, profile.usageLimitTier)
        assertFalse(profile.unlocksPremiumFeatures)
    }
}
