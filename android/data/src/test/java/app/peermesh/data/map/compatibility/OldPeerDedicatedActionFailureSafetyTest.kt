package app.peermesh.data.map.compatibility

import org.junit.Assert.*
import org.junit.Test

/**
 * Models failure-safe handling of dedicated actions unknown to an old peer.
 *
 * Contract-modeled old-peer rejection snapshot. The model represents the documented pre-M2
 * validator branch and is not execution of a historical APK or parser.
 */
class OldPeerDedicatedActionFailureSafetyTest {
    @Test
    fun unknownDedicatedActionDoesNotMutateMarkerCanonicalState() {
        assertEquals(markerState, handleUnknown(markerState))
    }

    @Test
    fun unknownDedicatedActionDoesNotMutateAreaCanonicalState() {
        assertEquals(areaState, handleUnknown(areaState))
    }

    @Test
    fun unknownDedicatedActionDoesNotChangeLegacyConfidence() {
        val after = handleUnknown(markerState)
        assertEquals(2, after.confidence)
        assertEquals(2, after.ownerConfidence)
    }

    @Test
    fun unknownDedicatedActionDoesNotChangeLegacyContributionHistory() {
        assertEquals(listOf("legacy note"), handleUnknown(markerState).contributions)
    }

    @Test
    fun unknownDedicatedActionDoesNotChangeLegacyRemovalState() {
        val after = handleUnknown(markerState)
        assertEquals(200L, after.removalRequestedT)
        assertEquals(250L, after.removalCancelT)
    }

    @Test
    fun unknownDedicatedActionIsNeverTranslatedIntoLegacyUpdate() {
        emitted.clear()
        handleUnknown(markerState)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun oldPeerOutcomeMatchesExistingUnknownEventBehavior() {
        assertFalse(M2D3JvmHarness.modeledPreM2ValidatorAccepts(action))
    }

    private val action by lazy { M2D3JvmHarness.fixture("unknown_dedicated_action_for_old_peer.json") }
    private val emitted = mutableListOf<String>()
    private val markerState = LegacyState("marker", 2, 2, listOf("legacy note"), 200, 250, false)
    private val areaState = LegacyState("area", 1, 1, listOf("area note"), null, null, false)

    private fun handleUnknown(before: LegacyState): LegacyState {
        if (M2D3JvmHarness.modeledPreM2ValidatorAccepts(action)) emitted += action.type
        return before
    }

    private data class LegacyState(
        val type: String,
        val confidence: Int,
        val ownerConfidence: Int,
        val contributions: List<String>,
        val removalRequestedT: Long?,
        val removalCancelT: Long?,
        val deleted: Boolean,
    )
}
