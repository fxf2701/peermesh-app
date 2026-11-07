package app.peermesh.map.presentation.map

import app.peermesh.core.map.MapSituationLifecycleStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOwnerDeleteAvailabilityPolicyTest {
    private val now = 100L

    @Test
    fun `active owner item inside all windows may be deleted`() {
        assertTrue(canDelete(state()))
        assertTrue(canDelete(state(lifecycle = null)))
    }

    @Test
    fun `resolved expired and edit-window-closed items cannot be deleted`() {
        assertFalse(canDelete(state(lifecycle = MapSituationLifecycleStatus.NO_LONGER_ACTIVE.wireValue)))
        assertFalse(canDelete(state(expiresAt = now)))
        assertFalse(canDelete(state(ownerEditUntil = now)))
    }

    @Test
    fun `non-owner removed actor and invalid canonical state cannot delete`() {
        assertFalse(canDelete(state(ownerDid = "peer")))
        assertFalse(canDelete(state(), actorRemoved = true))
        assertFalse(canDelete(state(lifecycle = "unknown")))
        assertFalse(canDelete(null))
    }

    private fun canDelete(
        state: MapOwnerDeleteAvailabilityPolicy.State?,
        actorRemoved: Boolean = false,
    ): Boolean = MapOwnerDeleteAvailabilityPolicy.canDelete(
        state = state,
        currentActorDid = "owner",
        nowSeconds = now,
        currentActorRemoved = actorRemoved,
    )

    private fun state(
        ownerDid: String = "owner",
        lifecycle: String? = MapSituationLifecycleStatus.ACTIVE.wireValue,
        expiresAt: Long? = now + 1,
        ownerEditUntil: Long? = now + 1,
    ) = MapOwnerDeleteAvailabilityPolicy.State(
        ownerDid = ownerDid,
        lifecycleWireValue = lifecycle,
        expiresAtSeconds = expiresAt,
        ownerEditUntilSeconds = ownerEditUntil,
    )
}
